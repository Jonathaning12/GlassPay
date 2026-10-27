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
