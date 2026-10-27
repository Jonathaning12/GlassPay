// Recordatorios de pago a contactos. El texto es una plantilla fija (sin texto libre del cobrador)
// para evitar abusos, y hay un tope diario por contacto.
const { createOpaqueToken } = require("./payers");

// Mismo criterio que stats.js y scheduling.js: Ciudad de México en UTC−6 fijo.
const MEXICO_OFFSET_MS = -6 * 60 * 60 * 1000;

/** Inicio del día de hoy en México, como instante ISO. */
function mexicoDayStart(now = new Date()) {
  const local = new Date(now.getTime() + MEXICO_OFFSET_MS);
  return new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()) - MEXICO_OFFSET_MS)
    .toISOString();
}

function formatAmount(amount) {
  const [integer, decimals] = Number(amount).toFixed(2).split(".");
  return `$${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${decimals}`;
}

/** "Ana te recuerda pagar $200.00 por la renta." */
function reminderText({ fromName, amount, concept }) {
  const who = fromName || "Un contacto";
  if (amount && concept) return `${who} te recuerda pagar ${formatAmount(amount)} por ${concept}.`;
  if (amount) return `${who} te recuerda pagar ${formatAmount(amount)}.`;
  if (concept) return `${who} te recuerda un pago pendiente: ${concept}.`;
  return `${who} te recuerda un pago pendiente.`;
}

function toVisibleReminder(reminder) {
  return { reminderId: reminder.reminderId, text: reminderText(reminder), createdAt: reminder.createdAt };
}

/** Envía (guarda) un recordatorio; devuelve false si ya se alcanzó el tope del día. */
async function sendReminder({ database, config, collectorId, payerId, amount, currency, concept, now = new Date() }) {
  return database.createPayerReminder({
    id: createOpaqueToken(18),
    collectorId,
    payerId,
    amount,
    currency,
    concept,
  }, { maxPerDay: config.reminders.maxPerDay, dayStartIso: mexicoDayStart(now) });
}

module.exports = { mexicoDayStart, reminderText, sendReminder, toVisibleReminder };
