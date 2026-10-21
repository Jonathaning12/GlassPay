const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { createApp } = require("../src/app");
const { createDatabase } = require("../src/db/database");

async function createTestEnvironment(overrides = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-payers-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  const config = {
    payments: {
      maxAmount: 1000,
      defaultCurrency: "MXN",
      allowedCurrencies: ["MXN"],
    },
    payers: {
      qrTokenTtlSeconds: 180,
      resolutionTtlSeconds: 300,
      paymentRequestTtlSeconds: 600,
      maxPendingRequests: 5,
      corsOrigin: "http://localhost:5173",
      ...overrides,
    },
    paypal: {
      returnUrl: "http://localhost:3000/paypal/return",
      cancelUrl: "http://localhost:3000/paypal/cancel",
    },
  };
  const paypal = {
    async createOrder({ referenceId }) {
      return {
        orderId: `order-${referenceId}`,
        approvalUrl: "https://www.sandbox.paypal.com/approve/test",
      };
    },
  };
  const server = createApp({ config, database, paypal }).listen(0);
  const address = server.address();
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    database,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      await database.close();
      fs.rmSync(directory, { recursive: true, force: true });
    },
  };
}

async function createPayer(baseUrl, displayName) {
  const response = await fetch(`${baseUrl}/api/payers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName }),
  });
  assert.equal(response.status, 201);
  return response.json();
}

test("payer QR flow keeps approveUrl private to the payer", async () => {
  const environment = await createTestEnvironment();
  try {
    const payer = await createPayer(environment.baseUrl, "Ana");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    assert.equal(qrResponse.status, 201);
    const qr = await qrResponse.json();
    assert.ok(qr.token);
    assert.ok(qr.token.length >= 43);

    const resolutionResponse = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: qr.token }),
    });
    assert.equal(resolutionResponse.status, 200);
    const resolution = await resolutionResponse.json();
    assert.deepEqual(Object.keys(resolution).sort(), ["displayName", "expiresAt", "resolutionId"]);
    assert.equal(resolution.displayName, "Ana");

    const collectorResponse = await fetch(`${environment.baseUrl}/api/payers/payment-requests`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: resolution.resolutionId,
        amount: 25,
        currency: "MXN",
        concept: "Café",
      }),
    });
    assert.equal(collectorResponse.status, 201);
    const collectorBody = await collectorResponse.json();
    assert.equal(collectorBody.state, "pendiente_de_pagador");
    assert.equal("approveUrl" in collectorBody, false);

    const payerRequestsResponse = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const payerRequests = await payerRequestsResponse.json();
    assert.equal(payerRequestsResponse.status, 200);
    assert.equal(payerRequests.requests[0].approveUrl, "https://www.sandbox.paypal.com/approve/test");

    const otherPayer = await createPayer(environment.baseUrl, "Luis");
    const otherRequestsResponse = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": otherPayer.payerId,
        "X-Payer-Session": otherPayer.sessionSecret,
      },
    });
    assert.deepEqual((await otherRequestsResponse.json()).requests, []);
    const unauthorizedReject = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${collectorBody.requestId}/reject`,
      {
        method: "POST",
        headers: {
          "X-Payer-Id": otherPayer.payerId,
          "X-Payer-Session": otherPayer.sessionSecret,
        },
      },
    );
    assert.equal(unauthorizedReject.status, 404);

    const wrongSecretResponse = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": "incorrecto",
      },
    });
    assert.equal(wrongSecretResponse.status, 401);
  } finally {
    await environment.close();
  }
});

test("expired or unknown QR tokens return the same generic error", async () => {
  const environment = await createTestEnvironment({ qrTokenTtlSeconds: 0 });
  try {
    const payer = await createPayer(environment.baseUrl, "Luis");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const qr = await qrResponse.json();
    const expiredResponse = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: qr.token }),
    });
    const unknownResponse = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: "unknown-token-value-1234567890" }),
    });
    assert.equal(expiredResponse.status, 404);
    assert.deepEqual(await expiredResponse.json(), await unknownResponse.json());
  } finally {
    await environment.close();
  }
});

test("profile creation is rate limited", async () => {
  const environment = await createTestEnvironment();
  try {
    const responses = [];
    for (let index = 0; index < 6; index += 1) {
      responses.push(await fetch(`${environment.baseUrl}/api/payers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ displayName: `Persona ${index}` }),
      }));
    }
    assert.equal(responses[5].status, 429);
  } finally {
    await environment.close();
  }
});
