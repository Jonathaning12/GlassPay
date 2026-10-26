const { randomBytes } = require("node:crypto");
const express = require("express");
const { createRateLimiter, hashOpaqueToken } = require("../services/payers");
const { authenticateCollector } = require("./collectors");
const { PaymentRequestError, createPaymentRequestFromResolutionHash } = require("../services/payment-requests");
const { sanitizeConcept } = require("./payments");

function isResolutionId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{32,128}$/.test(value);
}

function isProposalId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{43,128}$/.test(value);
}

function validateProposal(proposal, config) {
  const concept = sanitizeConcept(proposal.concept);
  if (proposal.needsClarification) {
    return {
      needsClarification: true,
      clarificationQuestion: proposal.clarificationQuestion || "¿Qué dato debo aclarar?",
      method: proposal.method,
    };
  }
  if (
    typeof proposal.amount !== "number"
    || !Number.isFinite(proposal.amount)
    || proposal.amount <= 0
    || proposal.amount > config.payments.maxAmount
    || !config.payments.allowedCurrencies.includes(proposal.currency)
    || !concept
  ) {
    return {
      needsClarification: true,
      clarificationQuestion: "¿Qué monto positivo, moneda permitida y concepto debo cobrar?",
      method: proposal.method,
    };
  }
  return {
    needsClarification: false,
    amount: proposal.amount.toFixed(2),
    currency: proposal.currency,
    concept,
    method: proposal.method,
  };
}

function createAgentRouter({ database, agent, paypal, config }) {
  const router = express.Router();
  const interpretLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const confirmLimiter = createRateLimiter({ maxRequests: 30, windowMs: 60_000 });

  router.post("/interpret", interpretLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const resolutionId = request.body?.resolutionId;
    const text = typeof request.body?.text === "string"
      ? request.body.text.normalize("NFKC").trim().slice(0, 500)
      : "";
    if (!isResolutionId(resolutionId) || !text) {
      return response.status(400).json({ error: "interpretation request is invalid" });
    }

    const resolutionHash = hashOpaqueToken(resolutionId);
    if (!database.findPayerResolution(resolutionHash, collector.id)) {
      return response.status(404).json({ error: "interpretation request not found" });
    }

    const startedAt = Date.now();
    const proposal = await agent.parseCharge(text, {
      defaultCurrency: config.payments.defaultCurrency,
      allowedCurrencies: config.payments.allowedCurrencies,
      maxAmount: config.payments.maxAmount,
    });
    const result = validateProposal(proposal, config);
    console.info("Charge interpretation completed", {
      method: result.method,
      valid: !result.needsClarification,
      latencyMs: Date.now() - startedAt,
    });

    if (result.needsClarification) {
      return response.status(200).json(result);
    }

    const proposalId = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + config.agent.proposalTtlSeconds * 1000).toISOString();
    await database.createChargeProposal({
      id: proposalId,
      resolutionHash,
      amount: result.amount,
      currency: result.currency,
      concept: result.concept,
      method: result.method,
      expiresAt,
    });

    return response.status(201).json({
      proposalId,
      amount: result.amount,
      currency: result.currency,
      concept: result.concept,
      needsClarification: false,
      method: result.method,
      expiresAt,
    });
  });

  router.post("/confirm", confirmLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const proposalId = request.body?.proposalId;
    if (!isProposalId(proposalId)) {
      return response.status(404).json({ error: "proposal not found" });
    }

    const proposal = await database.consumeChargeProposal(proposalId, collector.id);
    if (!proposal) {
      return response.status(404).json({ error: "proposal not found" });
    }

    try {
      const result = await createPaymentRequestFromResolutionHash({
        database,
        paypal,
        config,
        resolutionHash: proposal.resolution_hash,
        collectorId: collector.id,
        amount: proposal.amount,
        currency: proposal.currency,
        concept: proposal.concept,
      });
      return response.status(201).json(result);
    } catch (error) {
      if (error instanceof PaymentRequestError) {
        return response.status(error.status).json({ error: error.message });
      }
      return response.status(500).json({ error: "payment request failed" });
    }
  });

  return router;
}

module.exports = { createAgentRouter, validateProposal };
