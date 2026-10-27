const assert = require("node:assert/strict");
const { test } = require("node:test");
const {
  computeRunAt,
  formatRunAt,
  matchContact,
  numbersInText,
  parseWhenFallback,
} = require("../src/services/scheduling");
const { prepareCancel, prepareScheduleCharge } = require("../src/services/assistant-actions");

// Jueves 29 oct 2026, 10:00 en México (16:00 UTC).
const NOW = new Date("2026-10-29T16:00:00.000Z");
const CONFIG = {
  payments: { maxAmount: 1000, defaultCurrency: "MXN" },
  scheduling: { maxHorizonDays: 30 },
};
const CONTACTS = [
  { contactId: "c-ana", displayName: "Ana López" },
  { contactId: "c-luis", displayName: "Luis" },
  { contactId: "c-sara1", displayName: "Sara Ruiz" },
  { contactId: "c-sara2", displayName: "Sara Gómez" },
];
const runAtOf = (text) => computeRunAt(parseWhenFallback(text, NOW), NOW)?.toISOString();
const fallback = (overrides = {}) => ({
  intent: "schedule_charge",
  contactName: "",
  amount: 0,
  concept: "",
  whenType: "none",
  minutesFromNow: 0,
  date: "",
  time: "",
  method: "deterministic",
  ...overrides,
});

test("relative times are counted from now", () => {
  assert.equal(runAtOf("cóbrale a Ana 300 en 2 horas"), "2026-10-29T18:00:00.000Z");
  assert.equal(runAtOf("en 15 minutos"), "2026-10-29T16:15:00.000Z");
  assert.equal(runAtOf("dentro de 3 días"), "2026-11-01T16:00:00.000Z");
  assert.equal(runAtOf("en media hora"), "2026-10-29T16:30:00.000Z");
});

test("days and hours are read in Mexico time", () => {
  assert.equal(runAtOf("mañana a las 6"), "2026-10-31T00:00:00.000Z"); // 30 oct 18:00 MX
  assert.equal(runAtOf("mañana a las 9 de la mañana"), "2026-10-30T15:00:00.000Z");
  assert.equal(runAtOf("hoy a las 5 pm"), "2026-10-29T23:00:00.000Z");
  assert.equal(runAtOf("el viernes a las 18:30"), "2026-10-31T00:30:00.000Z");
  assert.equal(runAtOf("pasado mañana"), "2026-10-31T16:00:00.000Z"); // 10:00 por defecto
  assert.equal(runAtOf("el jueves a las 11"), "2026-11-05T17:00:00.000Z"); // hoy es jueves: el próximo
});

test("a time that already passed today moves to tomorrow", () => {
  assert.equal(runAtOf("a las 9"), "2026-10-30T15:00:00.000Z");
});

test("text without a date gives no time", () => {
  assert.equal(parseWhenFallback("cóbrale a Ana 300", NOW), null);
});

test("model dates are validated", () => {
  assert.equal(computeRunAt({ whenType: "absolute", date: "2026-02-31", time: "10:00" }, NOW), null);
  assert.equal(computeRunAt({ whenType: "absolute", date: "", time: "25:00" }, NOW), null);
  assert.equal(computeRunAt({ whenType: "absolute", date: "mañana", time: "" }, NOW), null);
  assert.equal(computeRunAt({ whenType: "relative", minutesFromNow: -5 }, NOW), null);
  assert.equal(
    computeRunAt({ whenType: "absolute", date: "2026-11-02", time: "18:00" }, NOW).toISOString(),
    "2026-11-03T00:00:00.000Z",
  );
});

test("run time label is in Spanish and Mexico time", () => {
  assert.equal(formatRunAt(new Date("2026-10-31T00:00:00.000Z")), "vie 30 oct, 18:00");
});

test("numbers are found in digits and words", () => {
  const numbers = numbersInText("cóbrale trescientos cincuenta a Ana y $1,200 a Luis");
  assert.equal(numbers.has(350), true);
  assert.equal(numbers.has(1200), true);
  assert.equal(numbers.has(5000), false);
});

test("contacts must be mentioned in the text and be unique", () => {
  assert.equal(matchContact("Ana", "cóbrale a ana 300", CONTACTS).contact.contactId, "c-ana");
  assert.equal(matchContact("", "cóbrale a Luis 300 mañana", CONTACTS).contact.contactId, "c-luis");
  assert.equal(matchContact("Sara", "cóbrale a Sara 300", CONTACTS).status, "ambiguous");
  assert.equal(matchContact("Sara Gómez", "cóbrale a Sara Gómez 300", CONTACTS).contact.contactId, "c-sara2");
  // La IA no puede inventar un contacto que el usuario no nombró.
  assert.equal(matchContact("Luis", "cóbrale a Pedro 300", CONTACTS).status, "none");
});

test("a complete message becomes a validated proposal", () => {
  const result = prepareScheduleCharge({
    interpretation: fallback(),
    text: "cóbrale a Ana 300 por la renta mañana a las 6",
    contacts: CONTACTS,
    config: CONFIG,
    now: NOW,
  });
  assert.equal(result.type, "proposal");
  assert.deepEqual(result.payload, {
    contactId: "c-ana",
    contactName: "Ana López",
    amount: "300.00",
    currency: "MXN",
    concept: "la renta",
    runAt: "2026-10-31T00:00:00.000Z",
  });
});

test("an amount invented by the model is replaced by the one in the text", () => {
  const result = prepareScheduleCharge({
    interpretation: fallback({ method: "gemini", amount: 999, contactName: "Ana", whenType: "relative", minutesFromNow: 60 }),
    text: "cóbrale a Ana 300 en una hora",
    contacts: CONTACTS,
    config: CONFIG,
    now: NOW,
  });
  assert.equal(result.payload.amount, "300.00");
});

test("missing or invalid data asks for clarification", () => {
  const ask = (text) => prepareScheduleCharge({ interpretation: fallback(), text, contacts: CONTACTS, config: CONFIG, now: NOW });
  assert.equal(ask("cóbrale a Ana 300").type, "clarification");
  assert.equal(ask("cóbrale a Ana mañana a las 6").type, "clarification");
  assert.equal(ask("cóbrale a Ana 5000 mañana a las 6").type, "clarification");
  assert.equal(ask("cóbrale a Pedro 300 mañana a las 6").type, "clarification");
  assert.equal(ask("cóbrale a Sara 300 mañana a las 6").type, "clarification");
  assert.equal(ask("cóbrale a Ana 300 en 40 días").type, "clarification");
});

test("cancel proposes the single matching active item", () => {
  const scheduled = [
    { scheduledId: "s1", contactName: "Ana López", state: "programado", amount: "300.00", currency: "MXN", concept: "renta", runAt: "x" },
    { scheduledId: "s2", contactName: "Luis", state: "programado", amount: "100.00", currency: "MXN", concept: "cena", runAt: "y" },
    { scheduledId: "s3", contactName: "Luis", state: "cancelado", amount: "100.00", currency: "MXN", concept: "cena", runAt: "z" },
  ];
  const cancelAna = prepareCancel({ interpretation: fallback({ contactName: "Ana" }), text: "cancela el cobro a Ana", scheduled });
  assert.equal(cancelAna.type, "proposal");
  assert.equal(cancelAna.payload.scheduledId, "s1");
  assert.equal(prepareCancel({ interpretation: fallback(), text: "cancela lo programado", scheduled }).type, "scheduled");
  assert.equal(prepareCancel({ interpretation: fallback(), text: "cancela", scheduled: [] }).type, "text");
});

test("day-of-month dates resolve to this or next month", () => {
  assert.equal(runAtOf("el 15 a las 10"), "2026-11-15T16:00:00.000Z");
  assert.equal(runAtOf("el 30 a las 10"), "2026-10-30T16:00:00.000Z");
  assert.equal(runAtOf("el 1 de noviembre a las 9"), "2026-11-01T15:00:00.000Z");
  assert.equal(runAtOf("el 5 de enero"), "2027-01-05T16:00:00.000Z");
  assert.equal(parseWhenFallback("el 31 de febrero", NOW), null);
});

test("a weekday series starts on that weekday", () => {
  assert.equal(runAtOf("cada viernes a las 6"), "2026-10-31T00:00:00.000Z");
});

test("monthly series keep their day and fall back to the last day of short months", () => {
  const { nextOccurrence } = require("../src/services/scheduling");
  assert.equal(
    nextOccurrence({ recurrence: "monthly", run_at: "2027-01-31T16:00:00.000Z", anchor_day: 31 }),
    "2027-02-28T16:00:00.000Z",
  );
  assert.equal(
    nextOccurrence({ recurrence: "monthly", run_at: "2027-02-28T16:00:00.000Z", anchor_day: 31 }),
    "2027-03-31T16:00:00.000Z",
  );
  assert.equal(
    nextOccurrence({ recurrence: "weekly", run_at: "2026-10-31T00:00:00.000Z" }),
    "2026-11-07T00:00:00.000Z",
  );
  assert.equal(nextOccurrence({ recurrence: null, run_at: "2026-10-31T00:00:00.000Z" }), null);
});

test("amounts are not taken from the time or the number of occurrences", () => {
  const { amountFallback } = require("../src/services/scheduling");
  assert.equal(amountFallback("cóbrale a Ana 600 mañana a las 6"), 600);
  assert.equal(amountFallback("cada semana por 4 semanas cóbrale a Luis 50"), 50);
  assert.equal(amountFallback("cóbrale a Ana 250 el 15 a las 10"), 250);
});

test("names match even with punctuation next to them", () => {
  assert.equal(matchContact("", "divide 900 entre Luis, y yo", CONTACTS).contact.contactId, "c-luis");
});

test("reminder text is a fixed template", () => {
  const { reminderText } = require("../src/services/reminders");
  assert.equal(reminderText({ fromName: "Rodrigo", amount: "1200", concept: "la renta" }), "Rodrigo te recuerda pagar $1,200.00 por la renta.");
  assert.equal(reminderText({ fromName: "Rodrigo", amount: "50", concept: "" }), "Rodrigo te recuerda pagar $50.00.");
  assert.equal(reminderText({ fromName: "Rodrigo", amount: null, concept: "la cena" }), "Rodrigo te recuerda un pago pendiente: la cena.");
  assert.equal(reminderText({ fromName: null, amount: null, concept: "" }), "Un contacto te recuerda un pago pendiente.");
});

test("the reminder day starts at midnight Mexico time", () => {
  const { mexicoDayStart } = require("../src/services/reminders");
  // 29 oct 21:00 en México (30 oct 03:00 UTC) sigue siendo el 29.
  assert.equal(mexicoDayStart(new Date("2026-10-30T03:00:00.000Z")), "2026-10-29T06:00:00.000Z");
});

test("a reminder needs a saved contact; amount, concept and date are optional", () => {
  const { prepareReminder } = require("../src/services/assistant-actions");
  const ask = (text) => prepareReminder({ interpretation: fallback({ intent: "reminder" }), text, contacts: CONTACTS, config: CONFIG, now: NOW });
  assert.deepEqual(ask("recuérdale a Luis que me pague 200 por la cena").payload, {
    contactId: "c-luis", contactName: "Luis", amount: "200.00", currency: "MXN", concept: "la cena", runAt: null,
  });
  assert.equal(ask("recuérdale a Luis que me pague").payload.amount, null);
  assert.equal(ask("recuérdale a Luis que me pague mañana a las 10").payload.runAt, "2026-10-30T16:00:00.000Z");
  assert.equal(ask("recuérdale a Pedro que me pague").type, "clarification");
  assert.equal(ask("recuérdale a Luis que me pague 5000").type, "clarification");
});
