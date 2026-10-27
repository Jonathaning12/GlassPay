// Revisor de programaciones: cada minuto ejecuta lo programado que ya venció.
// Reutiliza exactamente el mismo camino que un cobro normal (resolución + solicitud de pago),
// con sus validaciones y su tope de solicitudes pendientes.
const { createOpaqueToken, hashOpaqueToken } = require("./payers");
const { PaymentRequestError, createPaymentRequestFromResolutionHash } = require("./payment-requests");
const { nextOccurrence } = require("./scheduling");
const { sendReminder } = require("./reminders");
const { logRef, syncTransfer } = require("./transfers");

const RESOLUTION_TTL_MS = 60 * 1000;

function failureFor(error) {
  if (error instanceof PaymentRequestError && error.status === 409) return "demasiados_pendientes";
  if (error instanceof PaymentRequestError && error.status === 502) return "paypal_no_disponible";
  return "error";
}

function createScheduler({ database, paypal, config }) {
  let timer = null;
  let running = false;

  /**
   * Crea ya un cobro a un contacto guardado del cobrador. Lo usan el revisor y "dividir cuenta".
   * Devuelve { ok, requestId } o { ok: false, failure }.
   */
  async function chargeNow({ collectorId, contactId, payerId, amount, currency, concept }) {
    const contact = database.getCollectorContact(contactId, collectorId);
    if (!contact || contact.payer_id !== payerId) {
      return { ok: false, failure: "contacto_eliminado" };
    }
    const resolutionHash = hashOpaqueToken(createOpaqueToken(24));
    try {
      await database.createPayerResolution({
        hash: resolutionHash,
        payerId,
        collectorId,
        expiresAt: new Date(Date.now() + RESOLUTION_TTL_MS).toISOString(),
      });
      const created = await createPaymentRequestFromResolutionHash({
        database,
        paypal,
        config,
        resolutionHash,
        collectorId,
        amount,
        currency,
        concept,
        // El pagador no está mirando en ese momento: el cobro dura más que uno en persona.
        ttlSeconds: config.scheduling.chargeTtlSeconds,
      });
      return { ok: true, requestId: created.requestId };
    } catch (error) {
      return { ok: false, failure: failureFor(error) };
    }
  }

  async function remindNow(action) {
    const contact = database.getCollectorContact(action.contact_id, action.collector_id);
    if (!contact || contact.payer_id !== action.payer_id) {
      return { ok: false, failure: "contacto_eliminado" };
    }
    const sent = await sendReminder({
      database,
      config,
      collectorId: action.collector_id,
      payerId: action.payer_id,
      amount: action.amount || null,
      currency: action.currency,
      concept: action.concept,
    });
    return sent ? { ok: true } : { ok: false, failure: "limite_recordatorios" };
  }

  /**
   * Pago programado: usa el mismo camino que "Enviar dinero" (propuesta de envío + confirmación
   * atómica que verifica y reserva el saldo, Payouts idempotente). Sin saldo no se envía.
   */
  async function payNow(action) {
    const contact = database.getCollectorContact(action.contact_id, action.collector_id);
    if (!contact || contact.payer_id !== action.payer_id) {
      return { ok: false, failure: "contacto_eliminado" };
    }
    const transferId = createOpaqueToken(32);
    await database.createTransferProposal({
      id: transferId,
      senderCollectorId: action.collector_id,
      recipientPayerId: action.payer_id,
      amount: action.amount,
      currency: action.currency,
      note: action.concept,
      expiresAt: new Date(Date.now() + RESOLUTION_TTL_MS).toISOString(),
    });
    const result = await database.confirmTransferProposal(transferId, action.collector_id);
    if (!result) return { ok: false, failure: "error" };
    if (result.insufficient) return { ok: false, failure: "saldo_insuficiente" };
    if (result.cannotReceive) return { ok: false, failure: "sin_correo" };
    console.info("Scheduled transfer confirmed", { transfer: logRef(transferId) });
    await syncTransfer({ database, paypal, transferId });
    return { ok: true, requestId: transferId };
  }

  /** Ejecuta una acción ya reclamada (estado "ejecutando") y cierra su ocurrencia. */
  async function executeAction(action) {
    const runners = { reminder: remindNow, payment: payNow };
    const result = runners[action.kind] ? await runners[action.kind](action) : await chargeNow({
      collectorId: action.collector_id,
      contactId: action.contact_id,
      payerId: action.payer_id,
      amount: action.amount,
      currency: action.currency,
      concept: action.concept,
    });
    const outcome = result.ok
      ? { state: "completado", paymentRequestId: result.requestId || null }
      // Sin contacto no tiene sentido seguir con la serie.
      : { state: "fallido", failure: result.failure, terminal: result.failure === "contacto_eliminado" };
    await database.finishScheduledAction(action.id, outcome, nextOccurrence);
    // Referencia corta: sin montos, nombres ni ids completos en el log.
    console.info("scheduled action", { ref: action.id.slice(0, 8), state: outcome.state, failure: outcome.failure || null });
  }

  async function tick(now = new Date()) {
    if (running) return 0;
    running = true;
    try {
      const lateLimit = new Date(now.getTime() - config.scheduling.lateToleranceSeconds * 1000);
      const due = await database.claimDueScheduledActions(now.toISOString(), lateLimit.toISOString(), nextOccurrence);
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

  return { chargeNow, executeAction, start, stop, tick };
}

module.exports = { createScheduler };
