const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const initSqlJs = require("sql.js");
const { createApp } = require("../src/app");
const { createDatabase } = require("../src/db/database");
const { hashOpaqueToken } = require("../src/services/payers");

async function createTestEnvironment() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-collectors-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  const config = {
    payments: { maxAmount: 1000, defaultCurrency: "MXN", allowedCurrencies: ["MXN"] },
    payers: {
      qrTokenTtlSeconds: 180,
      resolutionTtlSeconds: 300,
      paymentRequestTtlSeconds: 600,
      statusTokenFinalMarginSeconds: 300,
      maxPendingRequests: 5,
      corsOrigin: "http://localhost:5173",
    },
    agent: { proposalTtlSeconds: 120 },
    paypal: { returnUrl: "http://localhost/return", cancelUrl: "http://localhost/cancel" },
  };
  const orderIds = [];
  const paypal = {
    async createOrder({ referenceId }) {
      const orderId = `order-${referenceId}`;
      orderIds.push(orderId);
      return { orderId, approvalUrl: "https://www.sandbox.paypal.com/approve/test" };
    },
  };
  const agent = {
    async parseCharge() {
      return {
        amount: 150,
        currency: "MXN",
        concept: "camiseta",
        needsClarification: false,
        clarificationQuestion: "",
        method: "deterministic",
      };
    },
  };
  const server = createApp({ config, database, paypal, agent }).listen(0);
  return {
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    database,
    orderIds,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      await database.close();
      fs.rmSync(directory, { recursive: true, force: true });
    },
  };
}

async function postJson(url, body, headers = {}) {
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

async function createCollector(baseUrl, displayName = "Rodrigo") {
  const response = await postJson(`${baseUrl}/api/collectors`, { displayName });
  assert.equal(response.status, 201);
  const body = await response.json();
  return {
    ...body,
    headers: { "X-Collector-Id": body.collectorId, "X-Collector-Session": body.sessionSecret },
  };
}

async function resolvePayer(baseUrl, collectorHeaders, displayName = "Ana") {
  const payerResponse = await postJson(`${baseUrl}/api/payers`, { displayName });
  const payer = await payerResponse.json();
  const qrResponse = await fetch(`${baseUrl}/api/payers/${payer.payerId}/qr`, {
    method: "POST",
    headers: { "X-Payer-Id": payer.payerId, "X-Payer-Session": payer.sessionSecret },
  });
  const qr = await qrResponse.json();
  const response = await postJson(`${baseUrl}/api/payers/resolve`, { token: qr.token }, collectorHeaders);
  assert.equal(response.status, 200);
  return response.json();
}

async function createChargeFor(environment, collector) {
  const resolution = await resolvePayer(environment.baseUrl, collector.headers);
  const interpretation = await postJson(
    `${environment.baseUrl}/api/agent/interpret`,
    { resolutionId: resolution.resolutionId, text: "cóbrale 150 por la camiseta" },
    collector.headers,
  );
  const proposal = await interpretation.json();
  const confirmation = await postJson(
    `${environment.baseUrl}/api/agent/confirm`,
    { proposalId: proposal.proposalId },
    collector.headers,
  );
  assert.equal(confirmation.status, 201);
  return confirmation.json();
}

async function getMe(baseUrl, headers, suffix = "") {
  return fetch(`${baseUrl}/api/collectors/me${suffix}`, { headers });
}

test("creating a collector returns the session secret once and a sanitized name", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl, "  Rodrigo\u0000  ");
    assert.equal(collector.displayName, "Rodrigo");
    assert.ok(collector.sessionSecret.length >= 43);
    const stored = environment.database.getCollectorProfile(collector.collectorId);
    assert.notEqual(stored.session_hash, collector.sessionSecret);
  } finally {
    await environment.close();
  }
});

test("creating a collector rejects an empty name", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await postJson(`${environment.baseUrl}/api/collectors`, { displayName: "   " });
    assert.equal(response.status, 400);
  } finally {
    await environment.close();
  }
});

test("collector profile returns name and empty activity for a new collector", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const response = await getMe(environment.baseUrl, collector.headers);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { displayName: "Rodrigo", activity: [] });
  } finally {
    await environment.close();
  }
});

test("collector profile without session headers returns the generic 401", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await getMe(environment.baseUrl, {});
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: "collector authentication failed" });
  } finally {
    await environment.close();
  }
});

test("collector profile with a wrong secret returns the generic 401", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const response = await getMe(environment.baseUrl, {
      "X-Collector-Id": collector.collectorId,
      "X-Collector-Session": "wrong-secret-wrong-secret-wrong-secret-0000",
    });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("collector profile with an unknown id returns the generic 401", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const response = await getMe(environment.baseUrl, {
      "X-Collector-Id": "00000000-0000-0000-0000-000000000000",
      "X-Collector-Session": collector.sessionSecret,
    });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("resolve requires a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await postJson(`${environment.baseUrl}/api/payers/resolve`, { token: "glasspay:x" });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("interpret requires a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const response = await postJson(`${environment.baseUrl}/api/agent/interpret`, {
      resolutionId: resolution.resolutionId,
      text: "cóbrale 150 por la camiseta",
    });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("confirm requires a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await postJson(`${environment.baseUrl}/api/agent/confirm`, { proposalId: "x".repeat(43) });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("direct payment request creation requires a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await postJson(`${environment.baseUrl}/api/payers/payment-requests`, {
      resolutionId: "x".repeat(32),
      amount: 25,
      currency: "MXN",
      concept: "Café",
    });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("another collector cannot interpret with my resolution", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const response = await postJson(
      `${environment.baseUrl}/api/agent/interpret`,
      { resolutionId: resolution.resolutionId, text: "cóbrale 150 por la camiseta" },
      intruder.headers,
    );
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "interpretation request not found" });
  } finally {
    await environment.close();
  }
});

test("another collector cannot confirm my proposal and does not consume it", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const interpretation = await postJson(
      `${environment.baseUrl}/api/agent/interpret`,
      { resolutionId: resolution.resolutionId, text: "cóbrale 150 por la camiseta" },
      owner.headers,
    );
    const { proposalId } = await interpretation.json();
    const stolen = await postJson(`${environment.baseUrl}/api/agent/confirm`, { proposalId }, intruder.headers);
    assert.equal(stolen.status, 404);
    const legitimate = await postJson(`${environment.baseUrl}/api/agent/confirm`, { proposalId }, owner.headers);
    assert.equal(legitimate.status, 201);
  } finally {
    await environment.close();
  }
});

test("another collector cannot create a direct payment request with my resolution", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const response = await postJson(
      `${environment.baseUrl}/api/payers/payment-requests`,
      { resolutionId: resolution.resolutionId, amount: 25, currency: "MXN", concept: "Café" },
      intruder.headers,
    );
    assert.equal(response.status, 404);
  } finally {
    await environment.close();
  }
});

test("movements list my charge with payer name and no PayPal data", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const charge = await createChargeFor(environment, collector);
    const response = await getMe(environment.baseUrl, collector.headers, "/movements");
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.movements.length, 1);
    const [movement] = body.movements;
    assert.equal(movement.movementId, charge.requestId);
    assert.equal(movement.state, "pendiente_de_pagador");
    assert.equal(movement.amount, "150.00");
    assert.equal(movement.currency, "MXN");
    assert.equal(movement.concept, "camiseta");
    assert.equal(movement.payerName, "Ana");
    assert.doesNotMatch(JSON.stringify(body), /approve|paypal|order|capture|statusToken|resolution/i);
  } finally {
    await environment.close();
  }
});

test("movements of another collector are not visible", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const other = await createCollector(environment.baseUrl, "Otro");
    await createChargeFor(environment, owner);
    const response = await getMe(environment.baseUrl, other.headers, "/movements");
    assert.deepEqual(await response.json(), { movements: [] });
  } finally {
    await environment.close();
  }
});

test("movements without session headers return the generic 401", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await getMe(environment.baseUrl, {}, "/movements");
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("activity counts a pending charge as in progress", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    await createChargeFor(environment, collector);
    const response = await getMe(environment.baseUrl, collector.headers);
    assert.deepEqual((await response.json()).activity, [
      { currency: "MXN", paidTotal: "0.00", paidCount: 0, inProgressCount: 1 },
    ]);
  } finally {
    await environment.close();
  }
});

test("activity sums paid charges only after the transaction is paid", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    await createChargeFor(environment, collector);
    const transaction = environment.database.findByOrderId(environment.orderIds[0]);
    await environment.database.transitionState(transaction.id, "pagada");
    const response = await getMe(environment.baseUrl, collector.headers);
    assert.deepEqual((await response.json()).activity, [
      { currency: "MXN", paidTotal: "150.00", paidCount: 1, inProgressCount: 0 },
    ]);
  } finally {
    await environment.close();
  }
});

test("legacy databases receive collector columns during migration", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-collectors-migration-"));
  const databasePath = path.join(directory, "legacy.db");
  try {
    const SQL = await initSqlJs({
      locateFile: (file) => require.resolve(`sql.js/dist/${file}`),
    });
    const legacy = new SQL.Database();
    legacy.run(`
      CREATE TABLE payer_resolutions (
        resolution_hash TEXT PRIMARY KEY,
        payer_id TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        used_at TEXT,
        created_at TEXT NOT NULL
      );
      CREATE TABLE payment_requests (
        id TEXT PRIMARY KEY,
        payer_id TEXT NOT NULL,
        resolution_hash TEXT NOT NULL UNIQUE,
        transaction_id TEXT NOT NULL UNIQUE,
        state TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);
    fs.writeFileSync(databasePath, Buffer.from(legacy.export()));
    const database = await createDatabase(databasePath);
    await database.createPayerProfile({
      id: "payer-1",
      displayName: "Ana",
      sessionSalt: "c2FsdA==",
      sessionHash: "aGFzaA==",
    });
    await database.createPayerResolution({
      hash: "resolution-hash",
      payerId: "payer-1",
      collectorId: "collector-1",
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
    });
    assert.equal(database.findPayerResolution("resolution-hash", "collector-1").display_name, "Ana");
    assert.equal(database.findPayerResolution("resolution-hash", "collector-2"), null);
    assert.deepEqual(database.listCollectorMovements("collector-1"), []);
    await database.close();
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("Phase 2 payment creation requires a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await postJson(`${environment.baseUrl}/api/payments`, {
      amount: 350,
      currency: "MXN",
      concept: "Cena",
    });
    assert.equal(response.status, 401);
    assert.equal(environment.orderIds.length, 0);
  } finally {
    await environment.close();
  }
});

async function saveContact(baseUrl, collectorHeaders, resolutionId) {
  return postJson(`${baseUrl}/api/collectors/me/contacts`, { resolutionId }, collectorHeaders);
}

async function listContacts(baseUrl, collectorHeaders) {
  const response = await fetch(`${baseUrl}/api/collectors/me/contacts`, { headers: collectorHeaders });
  assert.equal(response.status, 200);
  return (await response.json()).contacts;
}

async function requestContactResolution(baseUrl, collectorHeaders, contactId) {
  return postJson(`${baseUrl}/api/collectors/me/contacts/${contactId}/resolution`, {}, collectorHeaders);
}

async function chargeResolution(baseUrl, collectorHeaders, resolutionId) {
  const interpretation = await postJson(
    `${baseUrl}/api/agent/interpret`,
    { resolutionId, text: "cóbrale 150 por la camiseta" },
    collectorHeaders,
  );
  const { proposalId } = await interpretation.json();
  return postJson(`${baseUrl}/api/agent/confirm`, { proposalId }, collectorHeaders);
}

test("saving a contact from my resolution returns only id, name and date", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    assert.equal(resolution.contactId, null);
    const response = await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId);
    assert.equal(response.status, 201);
    const contact = await response.json();
    assert.deepEqual(Object.keys(contact).sort(), ["contactId", "createdAt", "displayName"]);
    assert.equal(contact.displayName, "Ana");
  } finally {
    await environment.close();
  }
});

test("saving the same payer twice keeps a single contact", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const first = await (await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId)).json();
    const second = await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId);
    assert.equal(second.status, 200);
    assert.equal((await second.json()).contactId, first.contactId);
    assert.equal((await listContacts(environment.baseUrl, collector.headers)).length, 1);
  } finally {
    await environment.close();
  }
});

test("a contact can be saved after its resolution was used for a charge", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const charge = await chargeResolution(environment.baseUrl, collector.headers, resolution.resolutionId);
    assert.equal(charge.status, 201);
    const response = await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId);
    assert.equal(response.status, 201);
  } finally {
    await environment.close();
  }
});

test("resolving a payer who is already a contact returns the contactId", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const payer = await (await postJson(`${environment.baseUrl}/api/payers`, { displayName: "Ana" })).json();
    const resolveFreshQr = async () => {
      const qr = await (await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
        method: "POST",
        headers: { "X-Payer-Id": payer.payerId, "X-Payer-Session": payer.sessionSecret },
      })).json();
      return (await postJson(`${environment.baseUrl}/api/payers/resolve`, { token: qr.token }, collector.headers)).json();
    };
    const first = await resolveFreshQr();
    const contact = await (await saveContact(environment.baseUrl, collector.headers, first.resolutionId)).json();
    const second = await resolveFreshQr();
    assert.equal(second.contactId, contact.contactId);
  } finally {
    await environment.close();
  }
});

test("another collector cannot save a contact with my resolution", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const response = await saveContact(environment.baseUrl, intruder.headers, resolution.resolutionId);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "contact cannot be saved" });
  } finally {
    await environment.close();
  }
});

test("an expired resolution cannot be saved as a contact", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const payer = await (await postJson(`${environment.baseUrl}/api/payers`, { displayName: "Ana" })).json();
    const resolutionId = "expired-resolution-123456789012345678";
    await environment.database.createPayerResolution({
      hash: hashOpaqueToken(resolutionId),
      payerId: payer.payerId,
      collectorId: collector.collectorId,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    });
    const response = await saveContact(environment.baseUrl, collector.headers, resolutionId);
    assert.equal(response.status, 404);
  } finally {
    await environment.close();
  }
});

test("saving a contact with a malformed resolutionId returns the generic error", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const response = await saveContact(environment.baseUrl, collector.headers, "short");
    assert.equal(response.status, 404);
  } finally {
    await environment.close();
  }
});

test("contacts endpoints require a collector session", async () => {
  const environment = await createTestEnvironment();
  try {
    const contactUrl = `${environment.baseUrl}/api/collectors/me/contacts/${"a".repeat(22)}`;
    const list = await fetch(`${environment.baseUrl}/api/collectors/me/contacts`);
    const save = await postJson(`${environment.baseUrl}/api/collectors/me/contacts`, { resolutionId: "x".repeat(32) });
    const remove = await fetch(contactUrl, { method: "DELETE" });
    const resolve = await postJson(`${contactUrl}/resolution`, {});
    assert.deepEqual([list.status, save.status, remove.status, resolve.status], [401, 401, 401, 401]);
  } finally {
    await environment.close();
  }
});

test("contacts of another collector are not listed", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const other = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    await saveContact(environment.baseUrl, owner.headers, resolution.resolutionId);
    assert.deepEqual(await listContacts(environment.baseUrl, other.headers), []);
  } finally {
    await environment.close();
  }
});

test("another collector cannot delete my contact", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const contact = await (await saveContact(environment.baseUrl, owner.headers, resolution.resolutionId)).json();
    const response = await fetch(`${environment.baseUrl}/api/collectors/me/contacts/${contact.contactId}`, {
      method: "DELETE",
      headers: intruder.headers,
    });
    assert.equal(response.status, 404);
    assert.equal((await listContacts(environment.baseUrl, owner.headers)).length, 1);
  } finally {
    await environment.close();
  }
});

test("I can delete my contact once", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const contact = await (await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId)).json();
    const url = `${environment.baseUrl}/api/collectors/me/contacts/${contact.contactId}`;
    const first = await fetch(url, { method: "DELETE", headers: collector.headers });
    const second = await fetch(url, { method: "DELETE", headers: collector.headers });
    assert.deepEqual([first.status, second.status], [204, 404]);
    assert.deepEqual(await listContacts(environment.baseUrl, collector.headers), []);
  } finally {
    await environment.close();
  }
});

test("charging a contact creates a fresh resolution usable for the normal flow", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const contact = await (await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId)).json();
    const response = await requestContactResolution(environment.baseUrl, collector.headers, contact.contactId);
    assert.equal(response.status, 201);
    const fresh = await response.json();
    assert.deepEqual(Object.keys(fresh).sort(), ["contactId", "displayName", "expiresAt", "resolutionId"]);
    assert.notEqual(fresh.resolutionId, resolution.resolutionId);
    assert.doesNotMatch(JSON.stringify(fresh), /payer_id|payerId|token/i);
    const charge = await chargeResolution(environment.baseUrl, collector.headers, fresh.resolutionId);
    assert.equal(charge.status, 201);
  } finally {
    await environment.close();
  }
});

test("another collector cannot charge my contact", async () => {
  const environment = await createTestEnvironment();
  try {
    const owner = await createCollector(environment.baseUrl, "Dueño");
    const intruder = await createCollector(environment.baseUrl, "Otro");
    const resolution = await resolvePayer(environment.baseUrl, owner.headers);
    const contact = await (await saveContact(environment.baseUrl, owner.headers, resolution.resolutionId)).json();
    const response = await requestContactResolution(environment.baseUrl, intruder.headers, contact.contactId);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "contact not found" });
  } finally {
    await environment.close();
  }
});

test("a collector cannot exceed the contact limit but can re-save an existing contact", async () => {
  const environment = await createTestEnvironment();
  try {
    const { database } = environment;
    for (const id of ["payer-a", "payer-b"]) {
      await database.createPayerProfile({ id, displayName: id, sessionSalt: "c2FsdA==", sessionHash: "aGFzaA==" });
    }
    const first = await database.saveCollectorContact(
      { id: "contact-a-123456789012", collectorId: "collector-1", payerId: "payer-a" }, 1,
    );
    const second = await database.saveCollectorContact(
      { id: "contact-b-123456789012", collectorId: "collector-1", payerId: "payer-b" }, 1,
    );
    const again = await database.saveCollectorContact(
      { id: "contact-c-123456789012", collectorId: "collector-1", payerId: "payer-a" }, 1,
    );
    assert.equal(first.created, true);
    assert.deepEqual(second, { limitReached: true });
    assert.equal(again.contact.contactId, "contact-a-123456789012");
  } finally {
    await environment.close();
  }
});

test("charging a contact still respects the pending requests limit per payer", async () => {
  const environment = await createTestEnvironment();
  try {
    const collector = await createCollector(environment.baseUrl);
    const resolution = await resolvePayer(environment.baseUrl, collector.headers);
    const contact = await (await saveContact(environment.baseUrl, collector.headers, resolution.resolutionId)).json();
    const chargeContact = async () => {
      const fresh = await (await requestContactResolution(
        environment.baseUrl, collector.headers, contact.contactId,
      )).json();
      return chargeResolution(environment.baseUrl, collector.headers, fresh.resolutionId);
    };
    for (let index = 0; index < 5; index += 1) {
      assert.equal((await chargeContact()).status, 201);
    }
    assert.equal((await chargeContact()).status, 409);
  } finally {
    await environment.close();
  }
});
