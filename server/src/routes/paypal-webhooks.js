const express = require("express");

class RetryableWebhookError extends Error {}

function orderIdFromLink(link) {
  if (typeof link !== "string") {
    return null;
  }
  const match = link.match(/\/v2\/checkout\/orders\/([^/?]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

function getOrderId(event) {
  const relatedOrderId = event.resource?.supplementary_data?.related_ids?.order_id;
  if (relatedOrderId) {
    return relatedOrderId;
  }

  const upLink = event.resource?.links?.find((link) => link.rel === "up");
  const linkedOrderId = orderIdFromLink(upLink?.href);
  if (linkedOrderId) {
    return linkedOrderId;
  }

  return event.event_type === "CHECKOUT.ORDER.APPROVED" ? event.resource?.id : null;
}

function getCaptureId(event) {
  return event.event_type.startsWith("PAYMENT.CAPTURE.")
    ? event.resource?.id
    : null;
}

function createCaptureCoordinator(paypal) {
  const inFlight = new Map();

  return async (orderId) => {
    if (!inFlight.has(orderId)) {
      const capturePromise = paypal.captureOrder(orderId)
        .finally(() => inFlight.delete(orderId));
      inFlight.set(orderId, capturePromise);
    }
    return inFlight.get(orderId);
  };
}

async function processPayPalEvent(event, { database, paypal, captureOrder }) {
  const orderId = getOrderId(event);
  if (!orderId) {
    throw new RetryableWebhookError("Webhook has no correlated PayPal order ID");
  }

  const transaction = database.findByOrderId(orderId);
  if (!transaction) {
    throw new RetryableWebhookError("Transaction is not ready for this PayPal order");
  }

  const started = await database.startWebhookEvent(event.id, event.event_type);
  if (!started) {
    console.info("PayPal webhook ignored as duplicate", {
      eventId: event.id,
      eventType: event.event_type,
    });
    return { duplicate: true };
  }

  try {
    if (event.event_type === "CHECKOUT.ORDER.APPROVED") {
      const changed = await database.transitionState(transaction.id, "aprobada");
      console.info("PayPal order approval processed", {
        eventId: event.id,
        orderId,
        stateChanged: changed,
      });

      const current = database.getTransaction(transaction.id);
      if (!current.paypal_capture_id && current.state !== "pagada") {
        console.info("PayPal capture requested", { eventId: event.id, orderId });
        const capture = await captureOrder(orderId);
        const captureResource = capture.purchase_units?.[0]?.payments?.captures?.[0];
        if (captureResource?.id) {
          await database.setCapture(transaction.id, captureResource.id);
        }
      }
    } else if (event.event_type === "PAYMENT.CAPTURE.COMPLETED") {
      const captureId = getCaptureId(event);
      if (!captureId) {
        throw new RetryableWebhookError("Capture webhook has no capture ID");
      }
      await database.setCapture(transaction.id, captureId);
      const changed = await database.transitionState(transaction.id, "pagada");
      console.info("PayPal capture marked as paid", {
        eventId: event.id,
        orderId,
        stateChanged: changed,
      });
    } else if (event.event_type === "PAYMENT.CAPTURE.DENIED") {
      const captureId = getCaptureId(event);
      if (captureId) {
        await database.setCapture(transaction.id, captureId);
      }
      const changed = await database.transitionState(transaction.id, "fallida");
      console.info("PayPal capture marked as failed", {
        eventId: event.id,
        orderId,
        stateChanged: changed,
      });
    } else {
      console.info("PayPal webhook ignored as unsupported", {
        eventId: event.id,
        eventType: event.event_type,
      });
    }

    await database.completeWebhookEvent(event.id);
    return { duplicate: false };
  } catch (error) {
    await database.failWebhookEvent(event.id);
    throw error;
  }
}

function createPayPalWebhooksRouter({ database, paypal }) {
  const router = express.Router();
  const captureOrder = createCaptureCoordinator(paypal);

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
      console.warn("PayPal webhook verification failed");
      return response.status(401).json({ error: "webhook verification failed" });
    }
    if (!verified) {
      console.warn("PayPal webhook signature rejected");
      return response.status(401).json({ error: "webhook verification failed" });
    }

    const eventId = typeof event.id === "string" ? event.id : null;
    if (!eventId || typeof event.event_type !== "string") {
      return response.status(400).json({ error: "invalid webhook event" });
    }

    console.info("PayPal webhook verified", {
      eventId,
      eventType: event.event_type,
    });

    try {
      await processPayPalEvent(event, { database, paypal, captureOrder });
      return response.sendStatus(200);
    } catch (error) {
      if (error instanceof RetryableWebhookError) {
        console.warn("PayPal webhook deferred for retry", {
          eventId,
          eventType: event.event_type,
        });
        return response.sendStatus(503);
      }

      console.error("PayPal webhook processing failed", {
        eventId,
        eventType: event.event_type,
      });
      return response.sendStatus(500);
    }
  });

  return router;
}

module.exports = {
  RetryableWebhookError,
  createPayPalWebhooksRouter,
  getOrderId,
  processPayPalEvent,
};
