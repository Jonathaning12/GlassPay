// Convierte una intención del asistente en una propuesta validada por el servidor. Nada aquí
// ejecuta acciones: la propuesta se guarda y solo se aplica cuando el cobrador la confirma.
const {
  amountFallback,
  computeRunAt,
  conceptFallback,
  detectRecurrence,
  foldWords,
  localDay,
  matchContact,
  numbersInText,
  parseWhenFallback,
} = require("./scheduling");

const MIN_LEAD_MS = 60 * 1000;
const MAX_OCCURRENCES = 12;
const MAX_SPLIT_CONTACTS = 10;
const INCLUDES_ME_PATTERN = /\b(y yo|conmigo|incluyendome|contandome|entre nosotros)\b/;

// Se importa al usarse para no crear un ciclo collectors → assistant-actions → payments → collectors.
const validators = () => require("../routes/payments");
const ACTIVE_STATES = new Set(["programado", "esperando_confirmacion"]);

function fold(text) {
  return text.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
}

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

/** Fecha futura dentro del horizonte, o el texto de aclaración. */
function checkRunAt(runAt, config, now) {
  if (runAt.getTime() < now.getTime() + MIN_LEAD_MS) {
    return "Esa hora ya pasó o es muy pronto. Dime una hora al menos un minuto en el futuro.";
  }
  if (runAt.getTime() > now.getTime() + config.scheduling.maxHorizonDays * 24 * 60 * 60 * 1000) {
    return `Solo puedo programar hasta ${config.scheduling.maxHorizonDays} días en el futuro.`;
  }
  return null;
}

/** Monto del mensaje. El de la IA solo vale si aparece literalmente en el texto del usuario. */
function textAmount(interpretation, text) {
  if (interpretation.amount > 0 && numbersInText(text).has(interpretation.amount)) {
    return interpretation.amount;
  }
  return amountFallback(text);
}

function resolveAmount(interpretation, text, maxAmount) {
  const amount = textAmount(interpretation, text);
  return amount === null ? null : validators().parseAmount(amount, maxAmount);
}

function resolveConcept(interpretation, text, fallbackConcept) {
  return validators().sanitizeConcept(interpretation.concept || conceptFallback(text) || "")?.slice(0, 60)
    || fallbackConcept;
}

function amountQuestion(config, what = "¿Cuánto le cobro?") {
  return clarification(
    `${what} Escribe el monto en el mensaje (máximo $${config.payments.maxAmount.toLocaleString("en-US")} por persona).`,
  );
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
  const timeProblem = checkRunAt(runAt, config, now);
  if (timeProblem) return clarification(timeProblem);

  const amount = resolveAmount(interpretation, text, config.payments.maxAmount);
  if (!amount) return amountQuestion(config);

  const match = matchContact(interpretation.contactName, text, contacts);
  if (match.status !== "match") return contactProblem(match);

  return {
    type: "proposal",
    kind: "schedule_charge",
    payload: {
      contactId: match.contact.contactId,
      contactName: match.contact.displayName,
      amount,
      currency: config.payments.defaultCurrency,
      concept: resolveConcept(interpretation, text, "Cobro programado"),
      runAt: runAt.toISOString(),
    },
  };
}

/**
 * Pago programado (sale dinero del cobrador): contacto guardado, monto del texto, fecha futura.
 * El correo de cobro del destinatario y el saldo se revisan en la ruta (dependen de la base) y
 * el saldo se vuelve a verificar de forma atómica al enviar.
 */
function prepareSchedulePayment({ interpretation, text, contacts, config, now = new Date() }) {
  const runAt = resolveWhen(interpretation, text, now);
  if (!runAt) {
    return clarification("¿Para cuándo programo el pago? Por ejemplo: «mañana a las 6».");
  }
  const timeProblem = checkRunAt(runAt, config, now);
  if (timeProblem) return clarification(timeProblem);

  const amount = resolveAmount(interpretation, text, config.payments.maxAmount);
  if (!amount) return amountQuestion(config, "¿Cuánto le pago?");

  const match = matchContact(interpretation.contactName, text, contacts);
  if (match.status !== "match") return contactProblem(match);

  return {
    type: "proposal",
    kind: "schedule_payment",
    payload: {
      contactId: match.contact.contactId,
      contactName: match.contact.displayName,
      amount,
      currency: config.payments.defaultCurrency,
      concept: resolveConcept(interpretation, text, "Envío GlassPay"),
      runAt: runAt.toISOString(),
    },
  };
}

/** Cuántas repeticiones: "6 veces" / "por 6 meses"; si no lo dice, el máximo (12). */
function resolveOccurrences(interpretation, text) {
  const match = fold(text).match(/\b(\d{1,2})\s+(?:veces|meses|semanas)\b/);
  const fromText = match ? Number(match[1]) : null;
  const fromModel = interpretation.occurrences > 0 && numbersInText(text).has(interpretation.occurrences)
    ? interpretation.occurrences
    : null;
  return fromText ?? fromModel ?? MAX_OCCURRENCES;
}

/** Cobro recurrente: semanal o mensual, máximo 12 repeticiones, la primera dentro del horizonte. */
function prepareRecurringCharge({ interpretation, text, contacts, config, now = new Date() }) {
  // La recurrencia la decide el texto ("cada mes", "cada viernes"), no la IA.
  const recurrence = detectRecurrence(text);
  if (!recurrence) {
    return clarification("¿Cada cuánto? Dime «cada semana» o «cada mes».");
  }
  const occurrences = resolveOccurrences(interpretation, text);
  if (occurrences < 2 || occurrences > MAX_OCCURRENCES) {
    return clarification(`Un cobro recurrente puede repetirse de 2 a ${MAX_OCCURRENCES} veces.`);
  }
  const runAt = resolveWhen(interpretation, text, now);
  if (!runAt) {
    return clarification("¿Cuándo es el primer cobro? Por ejemplo: «cada mes, empezando el 1 de noviembre a las 10».");
  }
  const timeProblem = checkRunAt(runAt, config, now);
  if (timeProblem) return clarification(timeProblem);

  const amount = resolveAmount(interpretation, text, config.payments.maxAmount);
  if (!amount) return amountQuestion(config);

  const match = matchContact(interpretation.contactName, text, contacts);
  if (match.status !== "match") return contactProblem(match);

  return {
    type: "proposal",
    kind: "recurring_charge",
    payload: {
      contactId: match.contact.contactId,
      contactName: match.contact.displayName,
      amount,
      currency: config.payments.defaultCurrency,
      concept: resolveConcept(interpretation, text, "Cobro recurrente"),
      runAt: runAt.toISOString(),
      recurrence,
      occurrencesTotal: occurrences,
      anchorDay: localDay(runAt),
    },
  };
}

/** Contactos nombrados en el texto, en el orden en que aparecen; sin duplicados. */
function splitParticipants(interpretation, text, contacts) {
  const folded = ` ${foldWords(text)} `;
  const names = interpretation.contactNames.length > 0
    ? interpretation.contactNames
    : contacts
      .filter((contact) => folded.includes(` ${foldWords(contact.displayName).split(" ")[0]} `))
      .map((contact) => contact.displayName);
  const participants = [];
  for (const name of names) {
    const match = matchContact(name, text, contacts);
    if (match.status === "ambiguous") return { problem: contactProblem(match) };
    if (match.status !== "match") {
      return { problem: clarification(`No encontré a «${name}» en tus contactos.`) };
    }
    if (!participants.some((entry) => entry.contactId === match.contact.contactId)) {
      participants.push(match.contact);
    }
  }
  const position = (contact) => folded.indexOf(` ${foldWords(contact.displayName).split(" ")[0]} `);
  return { participants: participants.sort((a, b) => position(a) - position(b)) };
}

/**
 * Dividir cuenta: partes iguales entre los contactos nombrados (y el cobrador si dice "y yo");
 * los centavos sobrantes van al primero. Cada parte es un cobro normal que se aprueba en PayPal.
 */
function prepareSplitBill({ interpretation, text, contacts, config, now = new Date() }) {
  const { participants, problem } = splitParticipants(interpretation, text, contacts);
  if (problem) return problem;
  if (participants.length === 0) {
    return clarification("¿Entre quiénes la divido? Nombra a tus contactos, por ejemplo: «entre Ana, Luis y yo».");
  }
  if (participants.length > MAX_SPLIT_CONTACTS) {
    return clarification(`Puedo dividir entre ${MAX_SPLIT_CONTACTS} contactos como máximo.`);
  }
  // "y yo" lo decide el texto, no la IA.
  const includesMe = INCLUDES_ME_PATTERN.test(fold(text));
  const people = participants.length + (includesMe ? 1 : 0);
  if (people < 2) {
    return clarification("Para dividir necesito al menos dos personas. Si es solo una, pídeme un cobro normal.");
  }

  const total = textAmount(interpretation, text);
  const totalAmount = total === null ? null : validators().parseAmount(total, config.payments.maxAmount * people);
  if (!totalAmount) return amountQuestion(config, "¿Cuánto fue el total?");
  const totalCents = Math.round(Number(totalAmount) * 100);
  const baseCents = Math.floor(totalCents / people);
  const remainder = totalCents - baseCents * people;
  const shares = participants.map((contact, index) => ({
    contactId: contact.contactId,
    contactName: contact.displayName,
    amount: ((baseCents + (index === 0 ? remainder : 0)) / 100).toFixed(2),
  }));
  if (baseCents <= 0 || shares.some((share) => Number(share.amount) > config.payments.maxAmount)) {
    return amountQuestion(config, "Con ese total alguna parte queda fuera del rango permitido.");
  }

  // Si dice cuándo, cada parte se programa; si no, se cobra al confirmar.
  const when = parseWhenFallback(text, now) || (interpretation.whenType !== "none" ? interpretation : null);
  let runAt = null;
  if (when) {
    runAt = resolveWhen(interpretation, text, now);
    if (!runAt) return clarification("No entendí para cuándo. Por ejemplo: «mañana a las 6».");
    const timeProblem = checkRunAt(runAt, config, now);
    if (timeProblem) return clarification(timeProblem);
  }

  return {
    type: "proposal",
    kind: "split_bill",
    payload: {
      participants: shares,
      total: totalAmount,
      myShare: includesMe ? (baseCents / 100).toFixed(2) : null,
      currency: config.payments.defaultCurrency,
      concept: resolveConcept(interpretation, text, "Cuenta dividida"),
      runAt: runAt ? runAt.toISOString() : null,
    },
  };
}

/** Recordatorio: contacto guardado; monto, concepto y fecha opcionales. Sin fecha, se envía al confirmar. */
function prepareReminder({ interpretation, text, contacts, config, now = new Date() }) {
  const match = matchContact(interpretation.contactName, text, contacts);
  if (match.status !== "match") return contactProblem(match);

  const rawAmount = textAmount(interpretation, text);
  const amount = rawAmount === null ? null : validators().parseAmount(rawAmount, config.payments.maxAmount);
  if (rawAmount !== null && !amount) return amountQuestion(config, "Ese monto no es válido.");

  const when = parseWhenFallback(text, now) || (interpretation.whenType !== "none" ? interpretation : null);
  let runAt = null;
  if (when) {
    runAt = resolveWhen(interpretation, text, now);
    if (!runAt) return clarification("No entendí para cuándo. Por ejemplo: «mañana a las 10».");
    const timeProblem = checkRunAt(runAt, config, now);
    if (timeProblem) return clarification(timeProblem);
  }

  return {
    type: "proposal",
    kind: "reminder",
    payload: {
      contactId: match.contact.contactId,
      contactName: match.contact.displayName,
      amount,
      currency: config.payments.defaultCurrency,
      concept: resolveConcept(interpretation, text, ""),
      runAt: runAt ? runAt.toISOString() : null,
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

module.exports = {
  prepareCancel,
  prepareRecurringCharge,
  prepareReminder,
  prepareScheduleCharge,
  prepareSchedulePayment,
  prepareSplitBill,
};
