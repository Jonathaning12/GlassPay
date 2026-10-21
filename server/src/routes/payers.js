const { randomUUID } = require("node:crypto");
const express = require("express");
const {
  createOpaqueToken,
  createRateLimiter,
  createSalt,
  createSessionSecret,
  hashOpaqueToken,
  hashSessionSecret,
  verifySessionSecret,
} = require("../services/payers");
const { parseAmount, sanitizeConcept } = require("./payments");

function sanitizeDisplayName(value) {
  if (typeof value !== "string") {
    return null;
  }
  const displayName = value
    .normalize("NFKC")
    .replace(/[\u0000-\u001F\u007F]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 60);
  return displayName || null;
}

function authenticatePayer(request, database) {
  const payerId = request.params.id || request.get("X-Payer-Id");
  const secret = request.get("X-Payer-Session");
  const profile = database.getPayerProfile(payerId);
  if (!profile || !verifySessionSecret(secret, profile.session_salt, profile.session_hash)) {
    return null;
  }
  return profile;
}

function createPayersRouter({ database, paypal, config }) {
  const router = express.Router();
  const profileLimiter = createRateLimiter({ maxRequests: 5, windowMs: 60_000 });
  const resolverLimiter = createRateLimiter({ maxRequests: 30, windowMs: 60_000 });

  router.post("/", profileLimiter, async (request, response) => {
    const displayName = sanitizeDisplayName(request.body?.displayName);
    if (!displayName) {
      return response.status(400).json({ error: "displayName is invalid" });
    }

    const payerId = randomUUID();
    const sessionSecret = createSessionSecret();
    const sessionSalt = createSalt();
    await database.createPayerProfile({
      id: payerId,
      displayName,
      sessionSalt,
      sessionHash: hashSessionSecret(sessionSecret, sessionSalt),
    });

    return response.status(201).json({ payerId, sessionSecret });
  });

  router.post("/resolve", resolverLimiter, async (request, response) => {
    const token = request.body?.token;
    if (typeof token !== "string" || token.length < 20) {
      return response.status(404).json({ error: "payer token not found" });
    }

    const payer = database.findPayerByQrHash(hashOpaqueToken(token));
    if (!payer) {
      return response.status(404).json({ error: "payer token not found" });
    }

    const resolutionId = createOpaqueToken(24);
    const expiresAt = new Date(Date.now() + config.payers.resolutionTtlSeconds * 1000).toISOString();
    await database.createPayerResolution({
      hash: hashOpaqueToken(resolutionId),
      payerId: payer.id,
      expiresAt,
    });

    return response.status(200).json({
      displayName: payer.display_name,
      resolutionId,
      expiresAt,
    });
  });

  router.post("/:id/qr", async (request, response) => {
    const payer = authenticatePayer(request, database);
    if (!payer) {
      return response.status(401).json({ error: "payer authentication failed" });
    }

    const token = createOpaqueToken(32);
    const expiresAt = new Date(Date.now() + config.payers.qrTokenTtlSeconds * 1000).toISOString();
    await database.createQrToken({
      hash: hashOpaqueToken(token),
      payerId: payer.id,
      expiresAt,
    });

    return response.status(201).json({ token, expiresAt });
  });

  router.get("/me/payment-requests", async (request, response) => {
    const payer = authenticatePayer(request, database);
    if (!payer) {
      return response.status(401).json({ error: "payer authentication failed" });
    }

    await database.expirePaymentRequests();
    const requests = database.listPayerRequests(payer.id).map((paymentRequest) => ({
      requestId: paymentRequest.id,
      state: paymentRequest.state,
      amount: paymentRequest.amount,
      currency: paymentRequest.currency,
      concept: paymentRequest.concept,
      approveUrl: paymentRequest.approvalUrl,
      expiresAt: paymentRequest.expiresAt,
      createdAt: paymentRequest.createdAt,
    }));
    return response.status(200).json({ requests });
  });

  router.post("/payment-requests", resolverLimiter, async (request, response) => {
    const amount = parseAmount(request.body?.amount, config.payments.maxAmount);
    const currency = typeof request.body?.currency === "string"
      ? request.body.currency.toUpperCase()
      : config.payments.defaultCurrency;
    const concept = sanitizeConcept(request.body?.concept);
    const resolutionId = request.body?.resolutionId;

    if (
      !amount
      || !config.payments.allowedCurrencies.includes(currency)
      || !concept
      || typeof resolutionId !== "string"
    ) {
      return response.status(400).json({ error: "payment request is invalid" });
    }

    await database.expirePaymentRequests();
    const resolution = await database.consumePayerResolution(hashOpaqueToken(resolutionId));
    if (!resolution) {
      return response.status(404).json({ error: "payment request cannot be created" });
    }
    if (database.countPendingRequests(resolution.payer_id) >= config.payers.maxPendingRequests) {
      return response.status(409).json({ error: "payer has too many pending requests" });
    }

    const transactionId = randomUUID();
    const requestId = randomUUID();
    const referenceId = `glasspay-${transactionId}`;
    const expiresAt = new Date(Date.now() + config.payers.paymentRequestTtlSeconds * 1000).toISOString();
    await database.createTransaction({
      id: transactionId,
      payerId: resolution.payer_id,
      referenceId,
      amount,
      currency,
      concept,
      state: "pendiente_de_pagador",
    });

    try {
      const order = await paypal.createOrder({
        amount,
        currency,
        description: concept,
        referenceId,
        returnUrl: config.paypal.returnUrl,
        cancelUrl: config.paypal.cancelUrl,
      });
      await database.updateOrder(transactionId, order, "pendiente_de_pagador");
      await database.createPaymentRequest({
        id: requestId,
        payerId: resolution.payer_id,
        resolutionHash: hashOpaqueToken(resolutionId),
        transactionId,
        expiresAt,
      });
      return response.status(201).json({
        requestId,
        state: "pendiente_de_pagador",
      });
    } catch {
      await database.transitionState(transactionId, "fallida");
      console.error("PayPal payment request creation failed", { requestId });
      return response.status(502).json({ error: "payment provider unavailable" });
    }
  });

  router.post("/payment-requests/:requestId/reject", async (request, response) => {
    const payer = authenticatePayer(request, database);
    if (!payer) {
      return response.status(401).json({ error: "payer authentication failed" });
    }

    await database.expirePaymentRequests();
    const rejected = await database.rejectPaymentRequest(request.params.requestId, payer.id);
    if (!rejected) {
      return response.status(404).json({ error: "payment request not found" });
    }
    return response.status(200).json({ requestId: request.params.requestId, state: "rechazada" });
  });

  return router;
}

module.exports = { createPayersRouter, sanitizeDisplayName };
