const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { createApp } = require("../src/app");
const { createDatabase } = require("../src/db/database");
const { createOpaqueToken, hashOpaqueToken } = require("../src/services/payers");
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

async function createAgentTestEnvironment({ resolutionTtlSeconds = 60 } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-agent-resolution-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  const resolutionId = "resolution-test-12345678901234567890";
  await database.createPayerProfile({
    id: "payer-resolution-test",
    displayName: "Ana",
    sessionSalt: "c2FsdA==",
    sessionHash: "aGFzaA==",
  });
  await database.createPayerResolution({
    hash: hashOpaqueToken(resolutionId),
    payerId: "payer-resolution-test",
    expiresAt: new Date(Date.now() + resolutionTtlSeconds * 1000).toISOString(),
  });
  const config = {
    payments: { maxAmount: 1000, defaultCurrency: "MXN", allowedCurrencies: ["MXN"] },
    payers: {
      paymentRequestTtlSeconds: 600,
      statusTokenFinalMarginSeconds: 300,
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
  return {
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    database,
    resolutionId,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      await database.close();
      fs.rmSync(directory, { recursive: true, force: true });
    },
  };
}

async function assertGenericAgentError(response, expectedMessage) {
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: expectedMessage });
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

const parserContext = {
  defaultCurrency: "MXN",
  allowedCurrencies: ["MXN"],
  maxAmount: 1000,
};

test("deterministic parser reads a dollar-sign amount written by the voice recognizer", () => {
  const result = deterministicParse("Cóbrale $150 por la camiseta", parserContext);
  assert.equal(result.amount, 150);
  assert.equal(result.currency, "MXN");
  assert.equal(result.needsClarification, false);
});

test("deterministic parser reads a dollar-sign amount followed by pesos", () => {
  const result = deterministicParse("Cóbrale $150 pesos por la camiseta", parserContext);
  assert.equal(result.amount, 150);
  assert.equal(result.currency, "MXN");
});

test("deterministic parser reads a dollar-sign amount with decimals", () => {
  assert.equal(deterministicParse("cobra $55.50 por el taxi", parserContext).amount, 55.5);
});

test("deterministic parser reads a thousands separator as thousands", () => {
  assert.equal(deterministicParse("cóbrale $1,200 por la renta", parserContext).amount, 1200);
});

test("deterministic parser keeps a decimal comma as decimals", () => {
  assert.equal(deterministicParse("cobra 55,50 por el taxi", parserContext).amount, 55.5);
});

test("deterministic parser still reads plain amounts", () => {
  assert.equal(deterministicParse("Cobrale 150 pesos por la camiseta", parserContext).amount, 150);
});

test("deterministic parser keeps negative dollar-sign amounts invalid", () => {
  const result = deterministicParse("cóbrale -$150 por la camiseta", parserContext);
  assert.equal(result.amount, -150);
  assert.equal(result.needsClarification, true);
});

test("deterministic parser keeps a spaced negative dollar-sign amount invalid", () => {
  const result = deterministicParse("cóbrale - $150 por la camiseta", parserContext);
  assert.equal(result.amount, -150);
  assert.equal(result.needsClarification, true);
});

test("deterministic parser keeps a Unicode minus amount invalid", () => {
  const result = deterministicParse("cóbrale −150 por la camiseta", parserContext);
  assert.equal(result.amount, -150);
  assert.equal(result.needsClarification, true);
});

test("deterministic parser keeps negative plain amounts invalid", () => {
  const result = deterministicParse("cóbrale -150 por la camiseta", parserContext);
  assert.equal(result.amount, -150);
  assert.equal(result.needsClarification, true);
});

test("deterministic parser treats dollar sign with dólares as USD", () => {
  const result = deterministicParse("cóbrale $30 dólares por la comida", parserContext);
  assert.equal(result.amount, 30);
  assert.equal(result.currency, "USD");
});

test("model result matching a dollar-sign amount is accepted", () => {
  const result = validateModelResultAgainstText({
    amount: 150,
    currency: "MXN",
    concept: "camiseta",
    needsClarification: false,
    clarificationQuestion: "",
    method: "gemini",
  }, "Cóbrale $150 por la camiseta", parserContext);
  assert.equal(result.needsClarification, false);
  assert.equal(result.amount, 150);
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

test("interpretation creates a one-use 256-bit proposal and confirmation returns only statusToken", async () => {
  const environment = await createAgentTestEnvironment();
  try {
    const interpretation = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: environment.resolutionId,
        text: "cóbrale 25 pesos por el café",
      }),
    });
    const proposal = await interpretation.json();
    assert.equal(interpretation.status, 201);
    assert.ok(proposal.proposalId.length >= 43);

    const confirmation = await fetch(`${environment.baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    const confirmationBody = await confirmation.json();
    assert.equal(confirmation.status, 201);
    assert.equal(confirmationBody.state, "pendiente_de_pagador");
    assert.equal("approveUrl" in confirmationBody, false);
    assert.equal(confirmationBody.statusToken.length >= 43, true);
    assert.doesNotMatch(JSON.stringify(confirmationBody), /paypal|order|capture/i);

    const repeated = await fetch(`${environment.baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    assert.equal(repeated.status, 404);
  } finally {
    await environment.close();
  }
});

test("interpret rejects a nonexistent resolution with a generic error", async () => {
  const environment = await createAgentTestEnvironment();
  try {
    const response = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: "resolution-missing-12345678901234567890",
        text: "cóbrale 25 pesos por el café",
      }),
    });
    await assertGenericAgentError(response, "interpretation request not found");
  } finally {
    await environment.close();
  }
});

test("interpret rejects an expired resolution with a generic error", async () => {
  const environment = await createAgentTestEnvironment({ resolutionTtlSeconds: 1 });
  try {
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const response = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: environment.resolutionId,
        text: "cóbrale 25 pesos por el café",
      }),
    });
    await assertGenericAgentError(response, "interpretation request not found");
  } finally {
    await environment.close();
  }
});

test("confirm rejects a proposal whose resolution expired with a generic error", async () => {
  const environment = await createAgentTestEnvironment({ resolutionTtlSeconds: 1 });
  try {
    const interpretation = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: environment.resolutionId,
        text: "cóbrale 25 pesos por el café",
      }),
    });
    const proposal = await interpretation.json();
    assert.equal(interpretation.status, 201);
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const response = await fetch(`${environment.baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    await assertGenericAgentError(response, "payment request cannot be created");
  } finally {
    await environment.close();
  }
});

test("interpret rejects a statusToken used as a resolutionId", async () => {
  const environment = await createAgentTestEnvironment();
  try {
    const response = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: createOpaqueToken(32),
        text: "cóbrale 25 pesos por el café",
      }),
    });
    await assertGenericAgentError(response, "interpretation request not found");
  } finally {
    await environment.close();
  }
});

test("confirm rejects a statusToken used as a proposalId", async () => {
  const environment = await createAgentTestEnvironment();
  try {
    const interpretation = await fetch(`${environment.baseUrl}/api/agent/interpret`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        resolutionId: environment.resolutionId,
        text: "cóbrale 25 pesos por el café",
      }),
    });
    const proposal = await interpretation.json();
    const confirmation = await fetch(`${environment.baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: proposal.proposalId }),
    });
    const confirmationBody = await confirmation.json();
    assert.equal(confirmation.status, 201);
    const response = await fetch(`${environment.baseUrl}/api/agent/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ proposalId: confirmationBody.statusToken }),
    });
    await assertGenericAgentError(response, "proposal not found");
  } finally {
    await environment.close();
  }
});
