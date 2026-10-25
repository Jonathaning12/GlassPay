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
      statusTokenFinalMarginSeconds: 300,
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

async function createPaymentRequestFixture(environment, displayName = "Ana") {
  const payer = await createPayer(environment.baseUrl, displayName);
  const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
    method: "POST",
    headers: {
      "X-Payer-Id": payer.payerId,
      "X-Payer-Session": payer.sessionSecret,
    },
  });
  assert.equal(qrResponse.status, 201);
  const qr = await qrResponse.json();
  const resolutionResponse = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: qr.token }),
  });
  assert.equal(resolutionResponse.status, 200);
  const resolution = await resolutionResponse.json();
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
  const paymentRequest = await collectorResponse.json();
  return { payer, qr, resolution, paymentRequest };
}

async function assertGenericStatusError(response) {
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: "payment request not found" });
}

test("payer QR uses the glasspay prefix and resolves the display name", async () => {
  const environment = await createTestEnvironment();
  try {
    const fixture = await createPaymentRequestFixture(environment);
    assert.match(fixture.qr.token, /^glasspay:/);
    assert.deepEqual(Object.keys(fixture.resolution).sort(), [
      "displayName",
      "expiresAt",
      "resolutionId",
    ]);
    assert.equal(fixture.resolution.displayName, "Ana");
  } finally {
    await environment.close();
  }
});

test("collector payment request does not expose approveUrl", async () => {
  const environment = await createTestEnvironment();
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    assert.equal(paymentRequest.state, "pendiente_de_pagador");
    assert.equal("approveUrl" in paymentRequest, false);
  } finally {
    await environment.close();
  }
});

test("status token exposes only public fields without PayPal identifiers", async () => {
  const environment = await createTestEnvironment();
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    const response = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
      { headers: { "X-Status-Token": paymentRequest.statusToken } },
    );
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.deepEqual(Object.keys(body).sort(), ["amount", "concept", "currency", "state"]);
    assert.doesNotMatch(JSON.stringify(body), /approveUrl|paypal|order|capture/i);
  } finally {
    await environment.close();
  }
});

test("status without X-Status-Token returns the generic error", async () => {
  const environment = await createTestEnvironment();
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    const response = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
    );
    await assertGenericStatusError(response);
  } finally {
    await environment.close();
  }
});

test("status token remains valid after the resolution expires", async () => {
  const environment = await createTestEnvironment({ resolutionTtlSeconds: 1 });
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const response = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
      { headers: { "X-Status-Token": paymentRequest.statusToken } },
    );
    assert.equal(response.status, 200);
    assert.equal((await response.json()).state, "pendiente_de_pagador");
  } finally {
    await environment.close();
  }
});

test("status with another request token returns the generic error", async () => {
  const environment = await createTestEnvironment();
  try {
    const first = await createPaymentRequestFixture(environment, "Ana");
    const second = await createPaymentRequestFixture(environment, "Luis");
    const otherPayerResponse = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${first.paymentRequest.requestId}/status`,
      { headers: { "X-Status-Token": second.paymentRequest.statusToken } },
    );
    await assertGenericStatusError(otherPayerResponse);
    const otherRequestResponse = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${second.paymentRequest.requestId}/status`,
      { headers: { "X-Status-Token": first.paymentRequest.statusToken } },
    );
    await assertGenericStatusError(otherRequestResponse);
  } finally {
    await environment.close();
  }
});

test("status by a payer without a resolution ID returns the generic error", async () => {
  const environment = await createTestEnvironment();
  try {
    const { payer, paymentRequest } = await createPaymentRequestFixture(environment);
    const response = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
      {
        headers: {
          "X-Payer-Id": payer.payerId,
          "X-Payer-Session": payer.sessionSecret,
        },
      },
    );
    await assertGenericStatusError(response);
  } finally {
    await environment.close();
  }
});

test("expired status token returns the generic error", async () => {
  const environment = await createTestEnvironment({
    paymentRequestTtlSeconds: 1,
    statusTokenFinalMarginSeconds: 0,
  });
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const response = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
      { headers: { "X-Status-Token": paymentRequest.statusToken } },
    );
    await assertGenericStatusError(response);
  } finally {
    await environment.close();
  }
});

test("status token allows one request per second for 30 seconds", async () => {
  const environment = await createTestEnvironment();
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment);
    for (let index = 0; index < 30; index += 1) {
      const response = await fetch(
        `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/status`,
        { headers: { "X-Status-Token": paymentRequest.statusToken } },
      );
      assert.equal(response.status, 200);
      await new Promise((resolve) => setTimeout(resolve, 1_010));
    }
  } finally {
    await environment.close();
  }
});

test("authenticated payer can view approveUrl in their request list", async () => {
  const environment = await createTestEnvironment();
  try {
    const { payer } = await createPaymentRequestFixture(environment);
    const response = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).requests[0].approveUrl, "https://www.sandbox.paypal.com/approve/test");
  } finally {
    await environment.close();
  }
});

test("another payer cannot view or reject a payment request", async () => {
  const environment = await createTestEnvironment();
  try {
    const { paymentRequest } = await createPaymentRequestFixture(environment, "Ana");
    const otherPayer = await createPayer(environment.baseUrl, "Luis");
    const listResponse = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": otherPayer.payerId,
        "X-Payer-Session": otherPayer.sessionSecret,
      },
    });
    assert.deepEqual((await listResponse.json()).requests, []);
    const rejectResponse = await fetch(
      `${environment.baseUrl}/api/payers/payment-requests/${paymentRequest.requestId}/reject`,
      {
        method: "POST",
        headers: {
          "X-Payer-Id": otherPayer.payerId,
          "X-Payer-Session": otherPayer.sessionSecret,
        },
      },
    );
    assert.equal(rejectResponse.status, 404);
  } finally {
    await environment.close();
  }
});

test("invalid payer session is rejected", async () => {
  const environment = await createTestEnvironment();
  try {
    const { payer } = await createPaymentRequestFixture(environment);
    const response = await fetch(`${environment.baseUrl}/api/payers/me/payment-requests`, {
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": "incorrecto",
      },
    });
    assert.equal(response.status, 401);
  } finally {
    await environment.close();
  }
});

test("payment request creation rejects a nonexistent resolution generically", async () => {
  const environment = await createTestEnvironment();
  try {
    const response = await fetch(`${environment.baseUrl}/api/payers/payment-requests`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: "resolution-missing-12345678901234567890",
        amount: 25,
        currency: "MXN",
        concept: "Café",
      }),
    });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "payment request cannot be created" });
  } finally {
    await environment.close();
  }
});

test("payment request creation rejects an expired resolution generically", async () => {
  const environment = await createTestEnvironment({ resolutionTtlSeconds: 1 });
  try {
    const payer = await createPayer(environment.baseUrl, "Ana");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const qr = await qrResponse.json();
    const resolutionResponse = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: qr.token }),
    });
    const resolution = await resolutionResponse.json();
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const response = await fetch(`${environment.baseUrl}/api/payers/payment-requests`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: resolution.resolutionId,
        amount: 25,
        currency: "MXN",
        concept: "Café",
      }),
    });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "payment request cannot be created" });
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

test("legacy QR token without the glasspay prefix is rejected generically", async () => {
  const environment = await createTestEnvironment();
  try {
    const payer = await createPayer(environment.baseUrl, "Marta");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const qr = await qrResponse.json();
    const response = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: qr.token.slice("glasspay:".length) }),
    });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "payer token not found" });
  } finally {
    await environment.close();
  }
});

test("QR token with the glasspay prefix is accepted", async () => {
  const environment = await createTestEnvironment();
  try {
    const payer = await createPayer(environment.baseUrl, "Sofía");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const qr = await qrResponse.json();
    const response = await fetch(`${environment.baseUrl}/api/payers/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: qr.token }),
    });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).displayName, "Sofía");
  } finally {
    await environment.close();
  }
});

test("legacy, unknown, and expired QR tokens share the same generic error", async () => {
  const environment = await createTestEnvironment({ qrTokenTtlSeconds: 0 });
  try {
    const payer = await createPayer(environment.baseUrl, "Nora");
    const qrResponse = await fetch(`${environment.baseUrl}/api/payers/${payer.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": payer.payerId,
        "X-Payer-Session": payer.sessionSecret,
      },
    });
    const qr = await qrResponse.json();
    const requests = [qr.token.slice("glasspay:".length), "unknown-token-value-1234567890", qr.token]
      .map((token) => fetch(`${environment.baseUrl}/api/payers/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token }),
      }));
    const responses = await Promise.all(requests);
    const payloads = await Promise.all(responses.map((response) => response.json()));
    assert.deepEqual(payloads[0], payloads[1]);
    assert.deepEqual(payloads[1], payloads[2]);
    assert.deepEqual(payloads[0], { error: "payer token not found" });
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
