// Respuestas del asistente, siempre redactadas por el servidor a partir de datos propios.
const { computeStats } = require("./stats");
const { t } = require("./i18n");

const DEBT_STATES = new Set(["pendiente_de_pagador", "aprobada"]);
const PERIOD_PHRASES = { week: "esta semana", month: "este mes", year: "este año" };
const SUGGESTION_KEYS = ["¿Cuánto cobré esta semana?", "¿Quién me debe?", "¿Qué tengo programado?", "¿Qué puedes hacer?"];
const SUGGESTIONS = SUGGESTION_KEYS;

function suggestionsFor(language = "es") {
  return SUGGESTION_KEYS.map((text) => t(language, text));
}
const COMING_SOON = {};

function formatMoney(value) {
  const [integer, decimals] = Number(value).toFixed(2).split(".");
  return `$${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${decimals}`;
}

function toCents(amount) {
  return Math.round(Number.parseFloat(amount) * 100);
}

function summaryReply(database, collectorId, period, currency, language) {
  const stats = computeStats(database, collectorId, period, { currency, language });
  const when = t(language, PERIOD_PHRASES[period]);
  const text = stats.paidCount === 0 && stats.sentCount === 0
    ? t(language, "No tienes cobros pagados ni envíos completados {when}.", { when })
    : t(language, "{When} cobraste {paid} y enviaste {sent}. Tu balance es {net}.", {
      When: `${when[0].toUpperCase()}${when.slice(1)}`,
      paid: formatMoney(stats.paidTotal),
      sent: formatMoney(stats.sentTotal),
      net: formatMoney(stats.netTotal),
    });
  return {
    kind: "summary",
    text,
    summary: {
      period,
      currency,
      paidTotal: stats.paidTotal,
      paidCount: stats.paidCount,
      sentTotal: stats.sentTotal,
      sentCount: stats.sentCount,
      netTotal: stats.netTotal,
    },
  };
}

function debtorsReply(database, collectorId, currency, language) {
  const byName = new Map();
  for (const movement of database.listCollectorMovements(collectorId, 200)) {
    if (!DEBT_STATES.has(movement.state) || movement.currency !== currency) continue;
    const entry = byName.get(movement.payerName) || { name: movement.payerName, cents: 0, count: 0 };
    entry.cents += toCents(movement.amount);
    entry.count += 1;
    byName.set(movement.payerName, entry);
  }
  const debtors = [...byName.values()].sort((a, b) => b.cents - a.cents);
  if (debtors.length === 0) {
    return { kind: "text", text: t(language, "Nadie te debe en este momento.") };
  }
  const total = debtors.reduce((sum, entry) => sum + entry.cents, 0);
  return {
    kind: "debtors",
    text: debtors.length === 1
      ? t(language, "{name} te debe {total}.", { name: debtors[0].name, total: formatMoney(total / 100) })
      : t(language, "Te deben {total} entre {count} personas.", { total: formatMoney(total / 100), count: debtors.length }),
    debtors: debtors.map((entry) => ({ name: entry.name, total: (entry.cents / 100).toFixed(2), count: entry.count })),
  };
}

function buildAssistantReply({ database, collectorId, interpretation, currency, language = "es" }) {
  const { intent, period } = interpretation;
  if (intent === "summary") {
    return summaryReply(database, collectorId, period === "none" ? "week" : period, currency, language);
  }
  if (intent === "debtors") {
    return debtorsReply(database, collectorId, currency, language);
  }
  if (intent === "scheduled") {
    return { kind: "text", text: t(language, "Aún no tienes nada programado.") };
  }
  if (intent === "help") {
    return {
      kind: "text",
      text: language === "en" ? HELP_EN : "Puedo decirte cuánto cobraste o enviaste por semana, mes o año y quién te debe. "
        + "También programo cobros a tus contactos («cóbrale a Ana 300 por la renta mañana a las 6»), "
        + "cobros recurrentes («cóbrale a Ana 200 cada mes empezando el 1 de noviembre») "
        + "y divido cuentas («divide 900 entre Ana, Luis y yo»). "
        + "Envío recordatorios («recuérdale a Sara que me pague 200 por la cena») "
        + "y programo pagos a tus contactos («págale a Luis 200 por la renta mañana a las 6»).",
    };
  }
  if (COMING_SOON[intent]) {
    return {
      kind: "text",
      text: t(language, "Muy pronto podré {what}. Por ahora puedo decirte cuánto cobraste y quién te debe.", { what: COMING_SOON[intent] }),
    };
  }
  return {
    kind: "text",
    text: t(language, "No entendí tu mensaje. Puedo decirte cuánto cobraste o enviaste y quién te debe."),
  };
}

const HELP_EN = "I can tell you how much you collected or sent by week, month or year, and who owes you. "
  + "I also schedule charges to your contacts (“charge Ana 300 for rent tomorrow at 6 pm”), "
  + "recurring charges (“charge Ana 200 every month starting on the 1st”) "
  + "and split bills (“split 900 between Ana, Luis and me”). "
  + "I send reminders (“remind Sara to pay me 200 for dinner”) "
  + "and schedule payments to your contacts (“pay Luis 200 for rent tomorrow at 6 pm”).";

module.exports = { SUGGESTIONS, buildAssistantReply, formatMoney, suggestionsFor };
