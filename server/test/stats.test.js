const assert = require("node:assert/strict");
const { test } = require("node:test");
const { computeStats, isPeriod, periodRange } = require("../src/services/stats");

test("week range starts on Monday 00:00 Mexico time", () => {
  // Miércoles 29 oct 2026 10:00 en México = 16:00 UTC.
  const range = periodRange("week", new Date("2026-10-29T16:00:00.000Z"));
  assert.equal(range.start.toISOString(), "2026-10-26T06:00:00.000Z");
  assert.equal(range.end.toISOString(), "2026-11-02T06:00:00.000Z");
  assert.equal(range.previousStart.toISOString(), "2026-10-19T06:00:00.000Z");
});

test("late night in Mexico still belongs to the local day, month and year", () => {
  // 31 dic 2026 23:30 en México = 1 ene 2027 05:30 UTC.
  const now = new Date("2027-01-01T05:30:00.000Z");
  assert.equal(periodRange("month", now).start.toISOString(), "2026-12-01T06:00:00.000Z");
  assert.equal(periodRange("year", now).start.toISOString(), "2026-01-01T06:00:00.000Z");
  assert.equal(periodRange("year", now).previousStart.toISOString(), "2025-01-01T06:00:00.000Z");
});

test("only known periods are accepted", () => {
  assert.equal(isPeriod("week"), true);
  assert.equal(isPeriod("month"), true);
  assert.equal(isPeriod("year"), true);
  assert.equal(isPeriod("day"), false);
  assert.equal(isPeriod(["week"]), false);
});

function fakeDatabase(current, previous = { charges: [], transfers: [] }) {
  const calls = [];
  return {
    calls,
    listCollectorActivityBetween(collectorId, from) {
      calls.push(from);
      return calls.length === 1 ? current : previous;
    },
  };
}

test("stats count paid charges and completed transfers only", () => {
  const now = new Date("2026-10-29T16:00:00.000Z");
  const database = fakeDatabase({
    charges: [
      { state: "pagada", currency: "MXN", payerName: "Ana", cents: 35000, createdAt: "2026-10-26T18:00:00.000Z" },
      { state: "pagada", currency: "MXN", payerName: "Ana", cents: 10000, createdAt: "2026-10-29T15:00:00.000Z" },
      { state: "pagada", currency: "MXN", payerName: "Luis", cents: 20000, createdAt: "2026-10-28T15:00:00.000Z" },
      { state: "pendiente_de_pagador", currency: "MXN", payerName: "Sara", cents: 99900, createdAt: "2026-10-28T15:00:00.000Z" },
      { state: "rechazada", currency: "MXN", payerName: "Sara", cents: 5000, createdAt: "2026-10-28T15:00:00.000Z" },
    ],
    transfers: [
      { state: "completada", currency: "MXN", cents: 12000, createdAt: "2026-10-27T15:00:00.000Z" },
      { state: "fallida", currency: "MXN", cents: 50000, createdAt: "2026-10-27T15:00:00.000Z" },
    ],
  }, {
    charges: [{ state: "pagada", currency: "MXN", payerName: "Ana", cents: 10000, createdAt: "2026-10-20T15:00:00.000Z" }],
    transfers: [],
  });

  const stats = computeStats(database, "collector", "week", { now });

  assert.equal(stats.paidTotal, "650.00");
  assert.equal(stats.paidCount, 3);
  assert.equal(stats.sentTotal, "120.00");
  assert.equal(stats.sentCount, 1);
  assert.equal(stats.netTotal, "530.00");
  assert.equal(stats.previousNetTotal, "100.00");
  assert.deepEqual(stats.byStatus, { paid: 3, pending: 1, notCompleted: 1 });
  assert.deepEqual(stats.topPayers, [
    { name: "Ana", total: "450.00", count: 2 },
    { name: "Luis", total: "200.00", count: 1 },
  ]);
  assert.deepEqual(stats.buckets.map((bucket) => bucket.label), ["L", "M", "M", "J", "V", "S", "D"]);
  assert.equal(stats.buckets[0].paidTotal, "350.00");
  assert.equal(stats.buckets[1].sentTotal, "120.00");
  assert.equal(stats.buckets[2].paidTotal, "200.00");
  assert.equal(stats.buckets[3].paidTotal, "100.00");
});

test("month buckets are weeks of the month and year buckets are months", () => {
  const now = new Date("2026-10-29T16:00:00.000Z");
  const empty = { charges: [], transfers: [] };
  assert.deepEqual(
    computeStats(fakeDatabase(empty), "c", "month", { now }).buckets.map((bucket) => bucket.label),
    ["S1", "S2", "S3", "S4", "S5"],
  );
  assert.equal(computeStats(fakeDatabase(empty), "c", "year", { now }).buckets.length, 12);
});
