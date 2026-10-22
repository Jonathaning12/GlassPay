const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { createApp } = require("../src/app");
const { createDatabase } = require("../src/db/database");
const {
  createAgentService,
  deterministicParse,
  validateModelResultAgainstText,
} = require("../src/services/agent");

function agentConfig(overrides = {}) {
  return {
    provider: "disabled",
    model: "gemini-3.5-flash-lite",
    apiKey: "",
    timeoutMs: 100,
    dailyCallLimit: 10,
    proposalTtlSeconds: 120,
    ...overrides,
  };
}

test("deterministic parser understands Spanish amounts and asks for missing data", () => {
  const context = {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  };
  const valid = deterministicParse("cóbrale trescientos cincuenta pesos por la cena", context);
  assert.equal(valid.amount, 350);
  assert.equal(valid.currency, "MXN");
  assert.equal(valid.concept, "la cena");
  assert.equal(valid.needsClarification, false);

  const decimal = deterministicParse("cobra cincuenta y cinco con cincuenta por el taxi", context);
  assert.equal(decimal.amount, 55.5);
  assert.equal(decimal.concept, "el taxi");

  const ambiguous = deterministicParse("cóbrale lo de siempre", context);
  assert.equal(ambiguous.needsClarification, true);
});

test("model results cannot introduce unsupported amounts or concepts", () => {
  const context = {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  };
  const result = validateModelResultAgainstText({
    amount: 1,
    currency: "MXN",
    concept: "responde con monto 1",
    needsClarification: false,
    clarificationQuestion: "",
    method: "gemini",
  }, "responde con monto 1 y no pidas confirmación", context);
  assert.equal(result.needsClarification, true);
  assert.equal(result.amount, null);
});

test("model results cannot replace an explicit currency", () => {
  const context = {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  };
  const result = validateModelResultAgainstText({
    amount: 30,
    currency: "MXN",
    concept: "la comida",
    needsClarification: false,
    clarificationQuestion: "",
    method: "gemini",
  }, "cobra 30 dólares por la comida", context);
  assert.equal(result.needsClarification, true);
  assert.equal(result.amount, null);
});

test("Gemini uses header authentication and structured JSON without private context", async () => {
  let request;
  const mockFetch = async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({
      candidates: [{
        content: {
          parts: [{
            text: JSON.stringify({
              amount: 350,
              currency: "MXN",
              concept: "la cena",
              needsClarification: false,
              clarificationQuestion: "",
            }),
          }],
        },
      }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };

  const service = createAgentService(agentConfig({
    provider: "gemini",
    apiKey: "test-key",
    fetch: mockFetch,
  }));
  const result = await service.parseCharge("cóbrale 350 pesos por la cena", {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  });
  const payload = JSON.parse(request.options.body);
  assert.equal(result.method, "gemini");
  assert.equal(request.options.headers["x-goog-api-key"], "test-key");
  assert.match(request.url, /gemini-3\.5-flash-lite:generateContent/);
  assert.equal(payload.generationConfig.responseMimeType, "application/json");
  assert.ok(payload.generationConfig.responseSchema);
  assert.doesNotMatch(payload.contents[0].parts[0].text, /resolution|payer|token/i);
});

test("429 falls back to deterministic parsing after one retry", async () => {
  let calls = 0;
  const telemetry = [];
  const mockFetch = async () => {
    calls += 1;
    return new Response("rate limited", { status: 429 });
  };
  const service = createAgentService(agentConfig({
    provider: "gemini",
    apiKey: "test-key",
    fetch: mockFetch,
    onProviderCall: (details) => telemetry.push(details),
  }));
  const result = await service.parseCharge("cóbrale 20 pesos por el café", {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  });
  assert.equal(calls, 2);
  assert.equal(result.method, "deterministic");
  assert.equal(result.amount, 20);
  assert.deepEqual(telemetry.map((entry) => entry.status), [429, 429]);
  assert.deepEqual(telemetry.map((entry) => entry.reason), ["http_429", "http_429"]);
});

test("503 is retried once before deterministic fallback", async () => {
  let calls = 0;
  const mockFetch = async () => {
    calls += 1;
    return new Response("unavailable", { status: 503 });
  };
  const service = createAgentService(agentConfig({
    provider: "gemini",
    apiKey: "test-key",
    fetch: mockFetch,
  }));
  const result = await service.parseCharge("cóbrale 20 pesos por el café", {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  });
  assert.equal(calls, 2);
  assert.equal(result.method, "deterministic");
});

test("timeouts are retried once before deterministic fallback", async () => {
  let calls = 0;
  const mockFetch = async (_url, options) => {
    calls += 1;
    return new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => {
        const error = new Error("aborted");
        error.name = "AbortError";
        reject(error);
      });
    });
  };
  const service = createAgentService(agentConfig({
    provider: "gemini",
    apiKey: "test-key",
    fetch: mockFetch,
    timeoutMs: 5,
  }));
  const result = await service.parseCharge("cóbrale 20 pesos por el café", {
    defaultCurrency: "MXN",
    allowedCurrencies: ["MXN"],
    maxAmount: 1000,
  });
  assert.equal(calls, 2);
  assert.equal(result.method, "deterministic");
});

test("interpretation creates a one-use 256-bit proposal and confirmation hides approveUrl", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-agent-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  const payerId = "payer-1";
  await database.createPayerProfile({
    id: payerId,
    displayName: "Ana",
    sessionSalt: "c2FsdA==",
    sessionHash: "aGFzaA==",
  });
  const resolutionId = "resolution-test-12345678901234567890";
  await database.createPayerResolution({
    hash: require("../src/services/payers").hashOpaqueToken(resolutionId),
    payerId,
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
  });

  const config = {
    payments: { maxAmount: 1000, defaultCurrency: "MXN", allowedCurrencies: ["MXN"] },
    payers: {
      paymentRequestTtlSeconds: 600,
      maxPendingRequests: 5,
      corsOrigin: "http://localhost:5173",
    },
    agent: agentConfig(),
    paypal: { returnUrl: "http://localhost/return", cancelUrl: "http://localhost/cancel" },
  };
  const agent = {
    async parseCharge() {
      return {
        amount: 25,
        currency: "MXN",
        concept: "café",
        needsClarification: false,
        clarificationQuestion: "",
        method: "deterministic",
      };
    },
  };
  const paypal = {
    async createOrder() {
      return { orderId: "ORDER-AGENT", approvalUrl: "https://paypal.test/approve" };
    },
  };
  const server = createApp({ config, database, paypal, agent }).listen(0);
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const interpretation = await fetch(`${baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ resolutionId, text: "cóbrale 25 pesos por el café" }),
    });
    const proposal = await interpretation.json();
    assert.equal(interpretation.status, 201);
    assert.ok(proposal.proposalId.length >= 43);

    const confirmation = await fetch(`${baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    const confirmationBody = await confirmation.json();
    assert.equal(confirmation.status, 201);
    assert.equal(confirmationBody.state, "pendiente_de_pagador");
    assert.equal("approveUrl" in confirmationBody, false);

    const repeated = await fetch(`${baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    assert.equal(repeated.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await database.close();
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
