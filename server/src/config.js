const path = require("node:path");

const SANDBOX_HOST = "api-m.sandbox.paypal.com";

function createConfig() {
  const paypalBaseUrl = process.env.PAYPAL_BASE_URL || `https://${SANDBOX_HOST}`;
  const parsedBaseUrl = new URL(paypalBaseUrl);

  if (parsedBaseUrl.protocol !== "https:" || parsedBaseUrl.hostname !== SANDBOX_HOST) {
    throw new Error("PAYPAL_BASE_URL must use the PayPal Sandbox API");
  }

  const maxAmount = Number.parseFloat(process.env.PAYMENT_MAX_AMOUNT || "1000");
  if (!Number.isFinite(maxAmount) || maxAmount <= 0) {
    throw new Error("PAYMENT_MAX_AMOUNT must be a positive number");
  }

  const defaultCurrency = (process.env.PAYMENT_DEFAULT_CURRENCY || "MXN").toUpperCase();
  const allowedCurrencies = (process.env.PAYMENT_ALLOWED_CURRENCIES || defaultCurrency)
    .split(",")
    .map((currency) => currency.trim().toUpperCase())
    .filter(Boolean);

  if (!allowedCurrencies.includes(defaultCurrency)) {
    throw new Error("PAYMENT_DEFAULT_CURRENCY must be in PAYMENT_ALLOWED_CURRENCIES");
  }

  return {
    port: Number.parseInt(process.env.PORT || "3000", 10),
    paypal: {
      baseUrl: paypalBaseUrl,
      clientId: process.env.PAYPAL_CLIENT_ID || "",
      clientSecret: process.env.PAYPAL_CLIENT_SECRET || "",
      webhookId: process.env.PAYPAL_WEBHOOK_ID || "",
      returnUrl: process.env.PAYPAL_RETURN_URL || "http://localhost:3000/paypal/return",
      cancelUrl: process.env.PAYPAL_CANCEL_URL || "http://localhost:3000/paypal/cancel",
    },
    payments: {
      maxAmount,
      defaultCurrency,
      allowedCurrencies,
    },
    databasePath: path.resolve(process.env.DATABASE_PATH || ".data/glasspay.db"),
  };
}

module.exports = { createConfig };
