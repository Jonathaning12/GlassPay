// Revisor de programaciones: cada minuto ejecuta los cobros programados que ya vencieron.
// Reutiliza exactamente el mismo camino que un cobro normal (resolución + solicitud de pago),
// con sus validaciones y su tope de solicitudes pendientes.
const { createOpaqueToken, hashOpaqueToken } = require("./payers");
const { PaymentRequestError, createPaymentRequestFromResolutionHash } = require("./payment-requests");

const RESOLUTION_TTL_MS = 60 * 1000;

function failureFor(error) {
  if (error instanceof PaymentRequestError && error.status === 409) return "demasiados_pendientes";
  if (error instanceof PaymentRequestError && error.status === 502) return "paypal_no_disponible";
  return "error";
}

function createScheduler({ database, paypal, config }) {
  let timer = null;
  let running = false;

  async function finish(action, result) {
    await database.finishScheduledAction(action.id, result);
    // Referencia corta: sin montos, nombres ni ids completos en el log.
    console.info("scheduled action", { ref: action.id.slice(0, 8), state: result.state, failure: result.failure || null });
  }

  /** Ejecuta una acción ya reclamada (estado "ejecutando"). */
  async function executeAction(action) {
    const contact = database.getCollectorContact(action.contact_id, action.collector_id);
    if (!contact || contact.payer_id !== action.payer_id) {
      await finish(action, { state: "fallido", failure: "contacto_eliminado" });
      return;
    }
    const resolutionHash = hashOpaqueToken(createOpaqueToken(24));
    try {
      await database.createPayerResolution({
        hash: resolutionHash,
        payerId: action.payer_id,
        collectorId: action.collector_id,
        expiresAt: new Date(Date.now() + RESOLUTION_TTL_MS).toISOString(),
      });
      const created = await createPaymentRequestFromResolutionHash({
        database,
        paypal,
        config,
        resolutionHash,
        collectorId: action.collector_id,
        amount: action.amount,
        currency: action.currency,
        concept: action.concept,
        ttlSeconds: config.scheduling.chargeTtlSeconds,
      });
      await finish(action, { state: "completado", paymentRequestId: created.requestId });
    } catch (error) {
      await finish(action, { state: "fallido", failure: failureFor(error) });
    }
  }

  async function tick(now = new Date()) {
    if (running) return 0;
    running = true;
    try {
      const lateLimit = new Date(now.getTime() - config.scheduling.lateToleranceSeconds * 1000);
      const due = await database.claimDueScheduledActions(now.toISOString(), lateLimit.toISOString());
      for (const action of due) {
        await executeAction(action);
      }
      return due.length;
    } finally {
      running = false;
    }
  }

  function start() {
    if (timer) return;
    timer = setInterval(() => {
      tick().catch(() => console.error("scheduler tick failed"));
    }, config.scheduling.intervalMs);
    timer.unref?.();
  }

  function stop() {
    clearInterval(timer);
    timer = null;
  }

  return { executeAction, start, stop, tick };
}

module.exports = { createScheduler };
