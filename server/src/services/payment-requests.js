const { randomUUID } = require("node:crypto");
const { createOpaqueToken, hashOpaqueToken } = require("./payers");

class PaymentRequestError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

async function createPaymentRequestFromResolutionHash({
  database,
  paypal,
  config,
  resolutionHash,
  amount,
  currency,
  concept,
}) {
  await database.expirePaymentRequests();
  const resolution = await database.consumePayerResolution(resolutionHash);
  if (!resolution) {
    throw new PaymentRequestError("payment request cannot be created", 404);
  }
  if (database.countPendingRequests(resolution.payer_id) >= config.payers.maxPendingRequests) {
    throw new PaymentRequestError("payer has too many pending requests", 409);
  }

  const transactionId = randomUUID();
  const requestId = randomUUID();
  const statusToken = createOpaqueToken(32);
  const referenceId = `glasspay-${transactionId}`;
  const expiresAt = new Date(Date.now() + config.payers.paymentRequestTtlSeconds * 1000).toISOString();
  const statusTokenExpiresAt = new Date(
    Date.parse(expiresAt) + config.payers.statusTokenFinalMarginSeconds * 1000,
  ).toISOString();
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
      resolutionHash,
      transactionId,
      statusTokenHash: hashOpaqueToken(statusToken),
      statusTokenExpiresAt,
      expiresAt,
    });
    return { requestId, state: "pendiente_de_pagador", statusToken };
  } catch {
    await database.transitionState(transactionId, "fallida");
    console.error("PayPal payment request creation failed", { requestId });
    throw new PaymentRequestError("payment provider unavailable", 502);
  }
}

module.exports = { PaymentRequestError, createPaymentRequestFromResolutionHash };
