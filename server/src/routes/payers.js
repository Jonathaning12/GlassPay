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
const {
  PaymentRequestError,
  createPaymentRequestFromResolutionHash,
} = require("../services/payment-requests");
const { parseAmount, sanitizeConcept } = require("./payments");
const { authenticateCollector } = require("./collectors");
const { toVisibleReminder } = require("../services/reminders");

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

function normalizeQrToken(value) {
  if (typeof value !== "string" || !value.startsWith("glasspay:")) {
    return null;
  }
  const token = value.slice("glasspay:".length);
  return token.length >= 20 ? token : null;
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
  const statusLimiter = createRateLimiter({
    maxRequests: 2,
    windowMs: 1_000,
    key: (request) => request.get("X-Status-Token") || `missing:${request.ip || "unknown"}`,
  });

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
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const token = normalizeQrToken(request.body?.token);
    if (!token) {
      return response.status(404).json({ error: "payer token not found" });
    }

    const payer = database.findPayerByQrHash(hashOpaqueToken(token));
    if (!payer) {
      return response.status(404).json({ error: "payer token not found" });
    }
    if (collector.payer_id && payer.id === collector.payer_id) {
      return response.status(409).json({ error: "cannot charge yourself" });
    }

    const resolutionId = createOpaqueToken(24);
    const expiresAt = new Date(Date.now() + config.payers.resolutionTtlSeconds * 1000).toISOString();
    await database.createPayerResolution({
      hash: hashOpaqueToken(resolutionId),
      payerId: payer.id,
      collectorId: collector.id,
      expiresAt,
    });

    return response.status(200).json({
      displayName: payer.display_name,
      resolutionId,
      expiresAt,
      contactId: database.findContactIdForPayer(collector.id, payer.id),
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

    return response.status(201).json({ token: `glasspay:${token}`, expiresAt });
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
      // Solo una solicitud pendiente puede aprobarse; el enlace no se expone después.
      approveUrl: paymentRequest.state === "pendiente_de_pagador" ? paymentRequest.approvalUrl : null,
      expiresAt: paymentRequest.expiresAt,
      createdAt: paymentRequest.createdAt,
      fromName: paymentRequest.collectorName,
    }));
    const reminders = database.listPayerReminders(payer.id).map(toVisibleReminder);
    return response.status(200).json({ requests, reminders });
  });

  router.post("/me/reminders/:reminderId/dismiss", async (request, response) => {
    const payer = authenticatePayer(request, database);
    if (!payer) {
      return response.status(401).json({ error: "payer authentication failed" });
    }
    const reminderId = request.params.reminderId;
    const dismissed = typeof reminderId === "string" && /^[A-Za-z0-9_-]{24,64}$/.test(reminderId)
      && await database.dismissPayerReminder(reminderId, payer.id);
    return dismissed
      ? response.status(200).json({ dismissed: true })
      : response.status(404).json({ error: "reminder not found" });
  });

  router.post("/payment-requests", resolverLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
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

    try {
      const result = await createPaymentRequestFromResolutionHash({
        database,
        paypal,
        config,
        resolutionHash: hashOpaqueToken(resolutionId),
        collectorId: collector.id,
        amount,
        currency,
        concept,
      });
      return response.status(201).json(result);
    } catch (error) {
      if (error instanceof PaymentRequestError) {
        return response.status(error.status).json({ error: error.message });
      }
      return response.status(500).json({ error: "payment request failed" });
    }
  });

  router.get("/payment-requests/:requestId/status", statusLimiter, async (request, response) => {
    const statusToken = request.get("X-Status-Token");
    if (typeof statusToken !== "string" || statusToken.length < 20) {
      return response.status(404).json({ error: "payment request not found" });
    }

    await database.expirePaymentRequests();
    const paymentRequest = database.getPaymentRequestStatus(
      request.params.requestId,
      hashOpaqueToken(statusToken),
    );
    if (!paymentRequest) {
      return response.status(404).json({ error: "payment request not found" });
    }

    return response.status(200).json({
      state: paymentRequest.transaction_state,
      amount: paymentRequest.amount,
      currency: paymentRequest.currency,
      concept: paymentRequest.concept,
    });
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
