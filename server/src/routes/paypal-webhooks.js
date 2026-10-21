const express = require("express");

function getOrderId(event) {
  return event.resource?.supplementary_data?.related_ids?.order_id
    || (event.event_type === "CHECKOUT.ORDER.APPROVED" ? event.resource?.id : null);
}

function getCaptureId(event) {
  return event.resource?.id
    || event.resource?.supplementary_data?.related_ids?.capture_id;
}

async function processEvent(event, { database, paypal }) {
  const orderId = getOrderId(event);
  const captureId = getCaptureId(event);
  const transaction = (orderId && database.findByOrderId(orderId))
    || (captureId && database.findByCaptureId(captureId));

  if (!transaction) {
    return;
  }

  if (event.event_type === "CHECKOUT.ORDER.APPROVED") {
    database.updateState(transaction.id, "aprobada");
    if (!transaction.paypal_capture_id) {
      const capture = await paypal.captureOrder(orderId);
      const captureResource = capture.purchase_units?.[0]?.payments?.captures?.[0];
      if (captureResource?.id) {
        database.setCapture(transaction.id, captureResource.id);
      }
    }
    return;
  }

  if (event.event_type === "PAYMENT.CAPTURE.COMPLETED") {
    if (captureId) {
      database.setCapture(transaction.id, captureId);
    }
    database.updateState(transaction.id, "pagada");
    return;
  }

  if (event.event_type === "PAYMENT.CAPTURE.DENIED") {
    if (captureId) {
      database.setCapture(transaction.id, captureId);
    }
    database.updateState(transaction.id, "fallida");
  }
}

function createPayPalWebhooksRouter({ database, paypal }) {
  const router = express.Router();

  router.post("/", async (request, response) => {
    if (!Buffer.isBuffer(request.body)) {
      return response.status(400).json({ error: "invalid webhook body" });
    }

    let event;
    try {
      event = JSON.parse(request.body.toString("utf8"));
    } catch {
      return response.status(400).json({ error: "invalid webhook body" });
    }

    let verified;
    try {
      verified = await paypal.verifyWebhookSignature(request.headers, event);
    } catch {
      return response.status(401).json({ error: "webhook verification failed" });
    }
    if (!verified) {
      return response.status(401).json({ error: "webhook verification failed" });
    }

    const eventId = typeof event.id === "string" ? event.id : null;
    if (!eventId || typeof event.event_type !== "string") {
      return response.status(400).json({ error: "invalid webhook event" });
    }

    if (!database.startWebhookEvent(eventId, event.event_type)) {
      return response.sendStatus(200);
    }

    response.sendStatus(200);
    try {
      await processEvent(event, { database, paypal });
      database.completeWebhookEvent(eventId);
    } catch {
      database.failWebhookEvent(eventId);
      console.error("PayPal webhook processing failed", { eventId, eventType: event.event_type });
    }
  });

  return router;
}

module.exports = { createPayPalWebhooksRouter };
