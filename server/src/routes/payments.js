const { randomUUID } = require("node:crypto");
const express = require("express");

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

function createPaymentsRouter({ database, paypal, config }) {
  const router = express.Router();

  router.post("/", async (request, response) => {
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
    database.createTransaction({
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
      database.updateOrder(paymentId, order);
      return response.status(201).json({
        paymentId,
        approveUrl: order.approvalUrl,
      });
    } catch (error) {
      database.updateState(paymentId, "fallida");
      console.error("PayPal order creation failed", { paymentId });
      return response.status(502).json({ error: "payment provider unavailable" });
    }
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

module.exports = { createPaymentsRouter };
