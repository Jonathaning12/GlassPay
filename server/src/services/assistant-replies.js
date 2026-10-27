// Respuestas del asistente, siempre redactadas por el servidor a partir de datos propios.
const { computeStats } = require("./stats");

const DEBT_STATES = new Set(["pendiente_de_pagador", "aprobada"]);
const PERIOD_PHRASES = { week: "esta semana", month: "este mes", year: "este año" };
const SUGGESTIONS = ["¿Cuánto cobré esta semana?", "¿Quién me debe?", "¿Qué tengo programado?", "¿Qué puedes hacer?"];
const COMING_SOON = {};

function formatMoney(value) {
  const [integer, decimals] = Number(value).toFixed(2).split(".");
  return `$${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${decimals}`;
}

function toCents(amount) {
  return Math.round(Number.parseFloat(amount) * 100);
}

function summaryReply(database, collectorId, period, currency) {
  const stats = computeStats(database, collectorId, period, { currency });
  const when = PERIOD_PHRASES[period];
  const text = stats.paidCount === 0 && stats.sentCount === 0
    ? `No tienes cobros pagados ni envíos completados ${when}.`
    : `${when[0].toUpperCase()}${when.slice(1)} cobraste ${formatMoney(stats.paidTotal)} y enviaste `
      + `${formatMoney(stats.sentTotal)}. Tu balance es ${formatMoney(stats.netTotal)}.`;
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

function debtorsReply(database, collectorId, currency) {
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
    return { kind: "text", text: "Nadie te debe en este momento." };
  }
  const total = debtors.reduce((sum, entry) => sum + entry.cents, 0);
  return {
    kind: "debtors",
    text: debtors.length === 1
      ? `${debtors[0].name} te debe ${formatMoney(total / 100)}.`
      : `Te deben ${formatMoney(total / 100)} entre ${debtors.length} personas.`,
    debtors: debtors.map((entry) => ({ name: entry.name, total: (entry.cents / 100).toFixed(2), count: entry.count })),
  };
}

function buildAssistantReply({ database, collectorId, interpretation, currency }) {
  const { intent, period } = interpretation;
  if (intent === "summary") {
    return summaryReply(database, collectorId, period === "none" ? "week" : period, currency);
  }
  if (intent === "debtors") {
    return debtorsReply(database, collectorId, currency);
  }
  if (intent === "scheduled") {
    return { kind: "text", text: "Aún no tienes nada programado." };
  }
  if (intent === "help") {
    return {
      kind: "text",
      text: "Puedo decirte cuánto cobraste o enviaste por semana, mes o año y quién te debe. "
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
      text: `Muy pronto podré ${COMING_SOON[intent]}. Por ahora puedo decirte cuánto cobraste y quién te debe.`,
    };
  }
  return {
    kind: "text",
    text: "No entendí tu mensaje. Puedo decirte cuánto cobraste o enviaste y quién te debe.",
  };
}

module.exports = { SUGGESTIONS, buildAssistantReply, formatMoney };
