const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const initSqlJs = require("sql.js");
const { createApp } = require("../src/app");
const { createDatabase } = require("../src/db/database");

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
