// Convierte una intención del asistente en una propuesta validada por el servidor. Nada aquí
// ejecuta acciones: la propuesta se guarda y solo se aplica cuando el cobrador la confirma.
const {
  amountFallback,
  computeRunAt,
  conceptFallback,
  matchContact,
  numbersInText,
  parseWhenFallback,
} = require("./scheduling");

const MIN_LEAD_MS = 60 * 1000;

// Se importa al usarse para no crear un ciclo collectors → assistant-actions → payments → collectors.
const validators = () => require("../routes/payments");
const ACTIVE_STATES = new Set(["programado", "esperando_confirmacion"]);

function clarification(text) {
  return { type: "clarification", text };
}

function resolveWhen(interpretation, text, now) {
  if (interpretation.method === "gemini" && interpretation.whenType !== "none") {
    const fromModel = computeRunAt(interpretation, now);
    if (fromModel) return fromModel;
  }
  const fallback = parseWhenFallback(text, now);
  return fallback ? computeRunAt(fallback, now) : null;
}

function resolveAmount(interpretation, text, maxAmount, now) {
  // El monto de la IA solo vale si aparece literalmente en el texto del usuario.
  if (interpretation.amount > 0 && numbersInText(text).has(interpretation.amount)) {
    return validators().parseAmount(interpretation.amount, maxAmount);
  }
  const fallback = amountFallback(text, parseWhenFallback(text, now)?.matched);
  return fallback === null ? null : validators().parseAmount(fallback, maxAmount);
}

function contactProblem(match) {
  if (match.status === "ambiguous") {
    const names = match.candidates.slice(0, 3).map((contact) => contact.displayName).join(", ");
    return clarification(`Tienes varios contactos con ese nombre (${names}). Escribe su nombre completo.`);
  }
  return clarification(
    "No encontré a esa persona en tus contactos. Agrégala primero escaneando su QR en Contactos.",
  );
}

/** Cobro programado: contacto guardado, monto del texto, fecha futura dentro del horizonte. */
function prepareScheduleCharge({ interpretation, text, contacts, config, now = new Date() }) {
  const runAt = resolveWhen(interpretation, text, now);
  if (!runAt) {
    return clarification("¿Para cuándo lo programo? Por ejemplo: «mañana a las 6» o «en 2 horas».");
  }
  const maxRunAt = now.getTime() + config.scheduling.maxHorizonDays * 24 * 60 * 60 * 1000;
  if (runAt.getTime() < now.getTime() + MIN_LEAD_MS) {
    return clarification("Esa hora ya pasó o es muy pronto. Dime una hora al menos un minuto en el futuro.");
  }
  if (runAt.getTime() > maxRunAt) {
    return clarification(`Solo puedo programar hasta ${config.scheduling.maxHorizonDays} días en el futuro.`);
  }

  const amount = resolveAmount(interpretation, text, config.payments.maxAmount, now);
  if (!amount) {
    return clarification(
      `¿Cuánto le cobro? Escribe el monto en el mensaje (máximo $${config.payments.maxAmount.toLocaleString("en-US")}).`,
    );
  }

  const match = matchContact(interpretation.contactName, text, contacts);
  if (match.status !== "match") {
    return contactProblem(match);
  }

  const concept = validators().sanitizeConcept(interpretation.concept || conceptFallback(text) || "")?.slice(0, 60)
    || "Cobro programado";
  return {
    type: "proposal",
    kind: "schedule_charge",
    payload: {
      contactId: match.contact.contactId,
      contactName: match.contact.displayName,
      amount,
      currency: config.payments.defaultCurrency,
      concept,
      runAt: runAt.toISOString(),
    },
  };
}

/** Cancelar: si hay exactamente una programación activa que coincide, se propone cancelarla. */
function prepareCancel({ interpretation, text, scheduled }) {
  const active = scheduled.filter((item) => ACTIVE_STATES.has(item.state));
  if (active.length === 0) {
    return { type: "text", text: "No tienes nada programado para cancelar." };
  }
  const people = [...new Map(active.map((item) => [item.contactName, { displayName: item.contactName }])).values()];
  const match = matchContact(interpretation.contactName, text, people);
  const candidates = match.status === "match"
    ? active.filter((item) => item.contactName === match.contact.displayName)
    : active.length === 1 ? active : [];
  if (candidates.length === 1) {
    const item = candidates[0];
    return {
      type: "proposal",
      kind: "cancel_scheduled",
      payload: {
        scheduledId: item.scheduledId,
        contactName: item.contactName,
        amount: item.amount,
        currency: item.currency,
        concept: item.concept,
        runAt: item.runAt,
      },
    };
  }
  return {
    type: "scheduled",
    text: "Tienes varias programaciones que podrían coincidir. Elige cuál cancelar en la lista.",
  };
}

module.exports = { prepareCancel, prepareScheduleCharge };
