const express = require("express");

function createPayPalPagesRouter() {
  const router = express.Router();

  router.get("/return", (_request, response) => {
    response.type("html").send("<h1>Pago aprobado</h1><p>Estamos esperando la confirmacion de PayPal.</p>");
  });

  router.get("/cancel", (_request, response) => {
    response.type("html").send("<h1>Pago cancelado</h1><p>No se realizo ningun cobro.</p>");
  });

  return router;
}

module.exports = { createPayPalPagesRouter };
