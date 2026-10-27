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
    payers: {
      qrTokenTtlSeconds: Number.parseInt(process.env.QR_TOKEN_TTL_SECONDS || "180", 10),
      resolutionTtlSeconds: Number.parseInt(process.env.QR_RESOLUTION_TTL_SECONDS || "300", 10),
      paymentRequestTtlSeconds: Number.parseInt(process.env.PAYMENT_REQUEST_TTL_SECONDS || "600", 10),
      statusTokenFinalMarginSeconds: Number.parseInt(
        process.env.PAYMENT_STATUS_TOKEN_FINAL_MARGIN_SECONDS || "300",
        10,
      ),
      maxPendingRequests: Number.parseInt(process.env.MAX_PENDING_REQUESTS || "5", 10),
      corsOrigin: process.env.PAYER_WEB_ORIGIN || "http://localhost:5173",
    },
    agent: {
      provider: (process.env.AI_PROVIDER || "gemini").toLowerCase(),
      model: process.env.AI_MODEL || "gemini-3.5-flash-lite",
      apiKey: process.env.GEMINI_API_KEY || "",
      timeoutMs: Number.parseInt(process.env.AI_TIMEOUT_MS || "5000", 10),
      dailyCallLimit: Number.parseInt(process.env.AI_DAILY_CALL_LIMIT || "100", 10),
      proposalTtlSeconds: Number.parseInt(process.env.CHARGE_PROPOSAL_TTL_SECONDS || "120", 10),
    },
    scheduling: {
      // PayPal da por defecto 6 h para mandar al pagador a aprobar una orden recién creada:
      // la vigencia de un cobro programado nunca supera ese límite.
      chargeTtlSeconds: Math.min(
        Number.parseInt(process.env.SCHEDULED_CHARGE_TTL_SECONDS || "18000", 10),
        6 * 60 * 60,
      ),
      maxActive: Number.parseInt(process.env.SCHEDULED_MAX_ACTIVE || "10", 10),
      maxAutoPayments: Number.parseInt(process.env.SCHEDULED_MAX_AUTO_PAYMENTS || "3", 10),
      maxHorizonDays: Number.parseInt(process.env.SCHEDULED_MAX_HORIZON_DAYS || "30", 10),
      lateToleranceSeconds: Number.parseInt(process.env.SCHEDULED_LATE_TOLERANCE_SECONDS || "7200", 10),
      proposalTtlSeconds: Number.parseInt(process.env.ASSISTANT_PROPOSAL_TTL_SECONDS || "120", 10),
      intervalMs: Number.parseInt(process.env.SCHEDULER_INTERVAL_MS || "60000", 10),
    },
    reminders: {
      maxPerDay: Number.parseInt(process.env.REMINDERS_MAX_PER_DAY || "2", 10),
    },
    databasePath: path.resolve(process.env.DATABASE_PATH || ".data/glasspay.db"),
  };
}

module.exports = { createConfig };
