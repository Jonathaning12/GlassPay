const assert = require("node:assert/strict");
const { test } = require("node:test");
const { createApp } = require("../src/app");

function startTestServer() {
  const transactions = new Map([
    ["ORDER-PAID", {
      paypal_order_id: "ORDER-PAID",
      state: "pagada",
      amount: "10.00",
      currency: "MXN",
      concept: "Cena familiar",
    }],
    ["ORDER-FAILED", {
      paypal_order_id: "ORDER-FAILED",
      state: "fallida",
      amount: "20.00",
      currency: "MXN",
      concept: "Prueba fallida",
    }],
  ]);
  const database = {
    findByOrderId(orderId) {
      return transactions.get(orderId);
    },
  };
  const app = createApp({
    config: { payments: {} },
    database,
    paypal: {},
  });
  const server = app.listen(0);
  const address = server.address();
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

test("status endpoint returns only public fields for paid and failed payments", async () => {
  const server = startTestServer();
  try {
    const paidResponse = await fetch(`${server.baseUrl}/api/payments/status?token=ORDER-PAID`);
    assert.equal(paidResponse.status, 200);
    assert.deepEqual(await paidResponse.json(), {
      state: "pagada",
      amount: "10.00",
      currency: "MXN",
      concept: "Cena familiar",
    });

    const failedResponse = await fetch(`${server.baseUrl}/api/payments/status?token=ORDER-FAILED`);
    assert.equal(failedResponse.status, 200);
    assert.deepEqual(await failedResponse.json(), {
      state: "fallida",
      amount: "20.00",
      currency: "MXN",
      concept: "Prueba fallida",
    });
  } finally {
    await server.close();
  }
});

test("return page polls safely and cancel page uses Spanish UTF-8 text", async () => {
  const server = startTestServer();
  try {
    const returnResponse = await fetch(
      `${server.baseUrl}/paypal/return?token=ORDER-PAID&PayerID=must-not-be-used`,
    );
    const returnHtml = await returnResponse.text();
    assert.equal(returnResponse.status, 200);
    assert.match(returnHtml, /charset="UTF-8"/);
    assert.match(returnHtml, /Confirmando tu pago…/);
    assert.match(returnHtml, /Preparando tu comprobante…/);
    assert.match(returnHtml, /Terminando detalles…/);
    assert.match(returnHtml, /Esto está tardando un poco más de lo normal…/);
    assert.match(returnHtml, /Tu pago está en proceso…/);
    assert.match(returnHtml, /El servidor aún no confirma un pago recibido/);
    assert.match(returnHtml, /api\/payments\/status\?token=/);
    assert.doesNotMatch(returnHtml, /PayerID|must-not-be-used/);

    const cancelResponse = await fetch(`${server.baseUrl}/paypal/cancel`);
    const cancelHtml = await cancelResponse.text();
    assert.equal(cancelResponse.status, 200);
    assert.match(cancelHtml, /Pago cancelado/);
    assert.match(cancelHtml, /No se hizo ningún cobro/);
  } finally {
    await server.close();
  }
});

test("status endpoint hides missing payments without internal details", async () => {
  const server = startTestServer();
  try {
    const response = await fetch(`${server.baseUrl}/api/payments/status?token=UNKNOWN`);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "payment not found" });
  } finally {
    await server.close();
  }
});
