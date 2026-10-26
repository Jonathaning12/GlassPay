const { randomUUID } = require("node:crypto");
const express = require("express");
const { createRateLimiter } = require("../services/payers");
const { authenticateCollector } = require("./collectors");

function sanitizeConcept(value) {
  if (typeof value !== "string") {
    return null;
  }

  const concept = value
    .normalize("NFKC")
    .replace(/[\u0000-\u001F\u007F]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 127);
  return concept || null;
}

function parseAmount(value, maxAmount) {
  if (typeof value !== "number" && typeof value !== "string") {
    return null;
  }
  const raw = String(value).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) {
    return null;
  }

  const amount = Number.parseFloat(raw);
  if (!Number.isFinite(amount) || amount <= 0 || amount > maxAmount) {
    return null;
  }
  return amount.toFixed(2);
}

function isPayPalOrderToken(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

function createPaymentsRouter({ database, paypal, config }) {
  const router = express.Router();
  const createLimiter = createRateLimiter({ maxRequests: 10, windowMs: 60_000 });

  // Endpoint de prueba de la Fase 2: también exige la sesión del cobrador.
  router.post("/", createLimiter, async (request, response) => {
    if (!authenticateCollector(request, database)) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const amount = parseAmount(request.body?.amount, config.payments.maxAmount);
    const currency = typeof request.body?.currency === "string"
      ? request.body.currency.toUpperCase()
      : config.payments.defaultCurrency;
    const concept = sanitizeConcept(request.body?.concept);

    if (!amount || !config.payments.allowedCurrencies.includes(currency) || !concept) {
      return response.status(400).json({
        error: "amount, currency or concept is invalid",
      });
    }

    const paymentId = randomUUID();
    const referenceId = `glasspay-${paymentId}`;
    await database.createTransaction({
      id: paymentId,
      referenceId,
      amount,
      currency,
      concept,
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
      await database.updateOrder(paymentId, order);
      return response.status(201).json({
        paymentId,
        approveUrl: order.approvalUrl,
      });
    } catch (error) {
      await database.transitionState(paymentId, "fallida");
      console.error("PayPal order creation failed", { paymentId });
      return response.status(502).json({ error: "payment provider unavailable" });
    }
  });

  router.get("/status", (request, response) => {
    const token = request.query.token;
    if (!isPayPalOrderToken(token)) {
      return response.status(400).json({ error: "invalid payment token" });
    }

    const transaction = database.findByOrderId(token);
    if (!transaction) {
      return response.status(404).json({ error: "payment not found" });
    }

    response.set("Cache-Control", "no-store");
    return response.status(200).json({
      state: transaction.state,
      amount: transaction.amount,
      currency: transaction.currency,
      concept: transaction.concept,
    });
  });

  router.get("/:id", (request, response) => {
    const transaction = database.getTransaction(request.params.id);
    if (!transaction) {
      return response.status(404).json({ error: "payment not found" });
    }

    return response.status(200).json({
      paymentId: transaction.id,
      state: transaction.state,
      amount: transaction.amount,
      currency: transaction.currency,
      concept: transaction.concept,
      approveUrl: transaction.approval_url,
      paypalOrderId: transaction.paypal_order_id,
      paypalCaptureId: transaction.paypal_capture_id,
      createdAt: transaction.created_at,
      updatedAt: transaction.updated_at,
    });
  });

  return router;
}

module.exports = { createPaymentsRouter, parseAmount, sanitizeConcept };
