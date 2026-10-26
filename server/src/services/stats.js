// Estadísticas del cobrador por periodo. Solo cuentan cobros "pagada" y envíos "completada":
// lo pendiente o fallido no es dinero que se haya movido.

// México dejó el horario de verano en 2022: la Ciudad de México está fija en UTC−6.
const MEXICO_OFFSET_MS = -6 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const PERIODS = new Set(["week", "month", "year"]);
const DAY_LABELS = ["L", "M", "M", "J", "V", "S", "D"];
const MONTH_LABELS = ["E", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"];
const PENDING_STATES = new Set(["creada", "enviada", "pendiente_de_pagador", "aprobada"]);
const NOT_COMPLETED_STATES = new Set(["rechazada", "expirada", "fallida"]);

function isPeriod(value) {
  return PERIODS.has(value);
}

// Convierte un instante a la "hora de pared" de México representada como Date en UTC.
function toLocal(date) {
  return new Date(date.getTime() + MEXICO_OFFSET_MS);
}

function fromLocal(localDate) {
  return new Date(localDate.getTime() - MEXICO_OFFSET_MS);
}

/** Límites [inicio, fin) del periodo actual y del anterior, como instantes UTC. */
function periodRange(period, now = new Date()) {
  const local = toLocal(now);
  const y = local.getUTCFullYear();
  const m = local.getUTCMonth();
  const d = local.getUTCDate();
  let start;
  let end;
  let previousStart;
  if (period === "week") {
    const mondayOffset = (local.getUTCDay() + 6) % 7;
    start = Date.UTC(y, m, d - mondayOffset);
    end = start + 7 * DAY_MS;
    previousStart = start - 7 * DAY_MS;
  } else if (period === "month") {
    start = Date.UTC(y, m, 1);
    end = Date.UTC(y, m + 1, 1);
    previousStart = Date.UTC(y, m - 1, 1);
  } else {
    start = Date.UTC(y, 0, 1);
    end = Date.UTC(y + 1, 0, 1);
    previousStart = Date.UTC(y - 1, 0, 1);
  }
  return {
    start: fromLocal(new Date(start)),
    end: fromLocal(new Date(end)),
    previousStart: fromLocal(new Date(previousStart)),
  };
}

function bucketLabels(period, start) {
  if (period === "week") return DAY_LABELS;
  if (period === "year") return MONTH_LABELS;
  const local = toLocal(start);
  const daysInMonth = new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth() + 1, 0)).getUTCDate();
  return Array.from({ length: Math.ceil(daysInMonth / 7) }, (_, index) => `S${index + 1}`);
}

function bucketIndex(period, createdAt, start) {
  const local = toLocal(new Date(createdAt));
  if (period === "week") return Math.floor((local.getTime() - toLocal(start).getTime()) / DAY_MS);
  if (period === "year") return local.getUTCMonth();
  return Math.floor((local.getUTCDate() - 1) / 7);
}

const money = (cents) => (cents / 100).toFixed(2);

function totals(activity, currency) {
  let paidCents = 0;
  let paidCount = 0;
  let sentCents = 0;
  let sentCount = 0;
  for (const charge of activity.charges) {
    if (charge.currency === currency && charge.state === "pagada") {
      paidCents += charge.cents;
      paidCount += 1;
    }
  }
  for (const transfer of activity.transfers) {
    if (transfer.currency === currency && transfer.state === "completada") {
      sentCents += transfer.cents;
      sentCount += 1;
    }
  }
  return { paidCents, paidCount, sentCents, sentCount };
}

function computeStats(database, collectorId, period, { currency = "MXN", now = new Date() } = {}) {
  const range = periodRange(period, now);
  const current = database.listCollectorActivityBetween(collectorId, range.start.toISOString(), range.end.toISOString());
  const previous = database.listCollectorActivityBetween(
    collectorId,
    range.previousStart.toISOString(),
    range.start.toISOString(),
  );
  const currentTotals = totals(current, currency);
  const previousTotals = totals(previous, currency);

  const labels = bucketLabels(period, range.start);
  const buckets = labels.map((label) => ({ label, paidCents: 0, sentCents: 0 }));
  for (const charge of current.charges) {
    if (charge.currency !== currency || charge.state !== "pagada") continue;
    const bucket = buckets[bucketIndex(period, charge.createdAt, range.start)];
    if (bucket) bucket.paidCents += charge.cents;
  }
  for (const transfer of current.transfers) {
    if (transfer.currency !== currency || transfer.state !== "completada") continue;
    const bucket = buckets[bucketIndex(period, transfer.createdAt, range.start)];
    if (bucket) bucket.sentCents += transfer.cents;
  }

  const byStatus = { paid: 0, pending: 0, notCompleted: 0 };
  const payers = new Map();
  for (const charge of current.charges) {
    if (charge.currency !== currency) continue;
    if (charge.state === "pagada") {
      byStatus.paid += 1;
      const entry = payers.get(charge.payerName) || { name: charge.payerName, cents: 0, count: 0 };
      entry.cents += charge.cents;
      entry.count += 1;
      payers.set(charge.payerName, entry);
    } else if (PENDING_STATES.has(charge.state)) {
      byStatus.pending += 1;
    } else if (NOT_COMPLETED_STATES.has(charge.state)) {
      byStatus.notCompleted += 1;
    }
  }

  return {
    period,
    currency,
    from: range.start.toISOString(),
    to: range.end.toISOString(),
    paidTotal: money(currentTotals.paidCents),
    paidCount: currentTotals.paidCount,
    sentTotal: money(currentTotals.sentCents),
    sentCount: currentTotals.sentCount,
    netTotal: money(currentTotals.paidCents - currentTotals.sentCents),
    previousNetTotal: money(previousTotals.paidCents - previousTotals.sentCents),
    buckets: buckets.map((bucket) => ({
      label: bucket.label,
      paidTotal: money(bucket.paidCents),
      sentTotal: money(bucket.sentCents),
    })),
    byStatus,
    topPayers: [...payers.values()]
      .sort((a, b) => b.cents - a.cents)
      .slice(0, 3)
      .map((entry) => ({ name: entry.name, total: money(entry.cents), count: entry.count })),
  };
}

module.exports = { computeStats, isPeriod, periodRange };
