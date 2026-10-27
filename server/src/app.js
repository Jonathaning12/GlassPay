const express = require("express");
const { createAgentRouter } = require("./routes/agent");
const { createAssistantService } = require("./services/assistant");
const { createScheduler } = require("./services/scheduler");
const { createCollectorsRouter } = require("./routes/collectors");
const { createPayPalPagesRouter } = require("./routes/paypal-pages");
const { createPaymentsRouter } = require("./routes/payments");
const { createPayersRouter } = require("./routes/payers");
const { createPayPalWebhooksRouter } = require("./routes/paypal-webhooks");

function createApp({
  config,
  database,
  paypal,
  agent,
  assistant = createAssistantService({ provider: "none" }),
  scheduler = createScheduler({ database, paypal, config }),
}) {
  const app = express();
  const payerConfig = config.payers || { corsOrigin: "" };

  app.use((request, response, next) => {
    const origin = request.get("Origin");
    if (origin && origin === payerConfig.corsOrigin) {
      response.set("Access-Control-Allow-Origin", origin);
      response.set("Access-Control-Allow-Headers", "Content-Type, X-Payer-Id, X-Payer-Session");
      response.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      response.set("Vary", "Origin");
    }
    if (request.method === "OPTIONS") {
      return response.sendStatus(204);
    }
    return next();
  });

  app.get("/health", (_request, response) => {
    response.status(200).json({
      status: "ok",
      service: "glasspay-server",
    });
  });

  app.use(
    "/webhooks/paypal",
    express.raw({ type: "application/json", limit: "1mb" }),
    createPayPalWebhooksRouter({ database, paypal }),
  );
  app.use(express.json({ limit: "100kb" }));
  app.use("/api/payers", createPayersRouter({ database, paypal, config }));
  app.use("/api/collectors", createCollectorsRouter({ database, config, paypal, assistant, scheduler }));
  app.use("/api/agent", createAgentRouter({ database, agent, paypal, config }));
  app.use("/api/payments", createPaymentsRouter({ database, paypal, config }));
  app.use("/paypal", createPayPalPagesRouter());

  return app;
}

module.exports = { createApp };
