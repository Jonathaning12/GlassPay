// Estados de un envío en GlassPay. "procesando", "completada" y "no_reclamada"
// reservan saldo; "fallida" lo libera.
const FINAL_FAILURE_ITEM_STATUSES = new Set(["FAILED", "RETURNED", "REFUNDED", "REVERSED", "BLOCKED"]);

// Un solo intento hacia PayPal por envío a la vez (el servidor corre en un proceso).
const inFlight = new Set();

function transferStateFromPayPal({ batchStatus, itemStatus }) {
  if (itemStatus === "SUCCESS") {
    return "completada";
  }
  if (itemStatus === "UNCLAIMED") {
    return "no_reclamada";
  }
  if (FINAL_FAILURE_ITEM_STATUSES.has(itemStatus) || batchStatus === "DENIED" || batchStatus === "CANCELED") {
    return "fallida";
  }
  return "procesando";
}

function isDefinitiveRejection(error) {
  return Number.isInteger(error?.status) && error.status >= 400 && error.status < 500 && error.status !== 429;
}

function logRef(transferId) {
  return String(transferId).slice(0, 8);
}

async function createPayoutOnce({ database, paypal, transfer }) {
  const transferId = transfer.id;
  const attempt = await database.beginPayoutAttempt(transferId);
  if (!attempt) {
    return;
  }
  try {
    const created = await paypal.createPayout({
      transferId,
      receiverEmail: transfer.receiver_email,
      amount: transfer.amount,
      currency: transfer.currency,
      note: transfer.note,
    });
    await database.updateTransferPayout(transferId, {
      batchId: created.batchId,
      paypalStatus: created.batchStatus,
      state: transferStateFromPayPal({ batchStatus: created.batchStatus, itemStatus: null }),
    });
  } catch (error) {
    if (isDefinitiveRejection(error) && attempt === 1) {
      // Rechazo en el primer intento: PayPal no movió dinero, se libera el saldo.
      await database.markTransferRejected(transferId, `HTTP_${error.status}`);
    } else if (isDefinitiveRejection(error)) {
      // Un reintento rechazado puede significar que el primero sí llegó a PayPal:
      // el saldo sigue reservado y se concilia a mano.
      await database.markTransferForReview(transferId, `REVIEW_HTTP_${error.status}`);
    }
    // Red o 5xx: queda reservado y se reintenta con la misma clave de idempotencia.
    console.error("Transfer payout failed", { transfer: logRef(transferId), attempt, status: error?.status || "network" });
  }
}

async function refreshPayoutStatus({ database, paypal, transfer }) {
  try {
    const status = await paypal.getPayout(transfer.paypal_batch_id);
    await database.updateTransferPayout(transfer.id, {
      paypalStatus: status.itemStatus || status.batchStatus,
      state: transferStateFromPayPal(status),
    });
  } catch (error) {
    console.error("Transfer status check failed", { transfer: logRef(transfer.id), status: error?.status || "network" });
  }
}

// Envía (o reintenta con la misma clave de idempotencia) y sincroniza el estado.
async function syncTransfer({ database, paypal, transferId }) {
  if (inFlight.has(transferId)) {
    return;
  }
  inFlight.add(transferId);
  try {
    const transfer = database.getTransferForPayout(transferId);
    if (!transfer || !["procesando", "no_reclamada"].includes(transfer.state)) {
      return;
    }
    if (transfer.paypal_status?.startsWith("REVIEW_")) {
      return;
    }
    if (transfer.paypal_batch_id) {
      await refreshPayoutStatus({ database, paypal, transfer });
    } else if (transfer.receiver_email) {
      await createPayoutOnce({ database, paypal, transfer });
    }
  } finally {
    inFlight.delete(transferId);
  }
}

module.exports = { logRef, syncTransfer, transferStateFromPayPal };
