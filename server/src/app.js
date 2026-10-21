const express = require("express");
const { createPayPalPagesRouter } = require("./routes/paypal-pages");
const { createPaymentsRouter } = require("./routes/payments");
const { createPayPalWebhooksRouter } = require("./routes/paypal-webhooks");

function createApp({ config, database, paypal }) {
  const app = express();

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
  app.use("/api/payments", createPaymentsRouter({ database, paypal, config }));
  app.use("/paypal", createPayPalPagesRouter());

  return app;
}

module.exports = { createApp };
