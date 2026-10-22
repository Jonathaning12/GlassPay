const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const initSqlJs = require("sql.js");
const { createDatabase } = require("../src/db/database");
const { processPayPalEvent } = require("../src/routes/paypal-webhooks");

function createFakeDatabase() {
  const transaction = {
    id: "payment-1",
    paypal_order_id: "ORDER-1",
    paypal_capture_id: null,
    state: "enviada",
  };
  const events = new Set();
  const transitions = [];

  return {
    transaction,
    transitions,
    findByOrderId(orderId) {
      return orderId === transaction.paypal_order_id ? transaction : null;
    },
    getTransaction() {
      return transaction;
    },
    async startWebhookEvent(eventId) {
      if (events.has(eventId)) {
        return false;
      }
      events.add(eventId);
      return true;
    },
    async completeWebhookEvent() {},
    async failWebhookEvent() {},
    async setCapture(_id, captureId) {
      if (!transaction.paypal_capture_id) {
        transaction.paypal_capture_id = captureId;
      }
    },
    async transitionState(_id, state) {
      const rank = { enviada: 1, aprobada: 2, pagada: 3 };
      if (rank[state] > rank[transaction.state]) {
        transitions.push({ from: transaction.state, to: state });
        transaction.state = state;
        return true;
      }
      return false;
    },
  };
}

test("capture webhook wins a race with the approval-triggered capture", async () => {
  const database = createFakeDatabase();
  let captureStarted;
  const captureStartedPromise = new Promise((resolve) => {
    captureStarted = resolve;
  });
  let releaseCapture;
  const captureReleasePromise = new Promise((resolve) => {
    releaseCapture = resolve;
  });
  const captureOrder = async () => {
    captureStarted();
    await captureReleasePromise;
    return {
      purchase_units: [{
        payments: {
          captures: [{ id: "CAPTURE-1" }],
        },
      }],
    };
  };
  const dependencies = { database, paypal: {}, captureOrder };
  const approvedEvent = {
    id: "event-approved",
    event_type: "CHECKOUT.ORDER.APPROVED",
    resource: { id: "ORDER-1" },
  };
  const captureEvent = {
    id: "event-capture",
    event_type: "PAYMENT.CAPTURE.COMPLETED",
    resource: {
      id: "CAPTURE-1",
      links: [{
        rel: "up",
        href: "https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-1",
      }],
    },
  };

  const approvedPromise = processPayPalEvent(approvedEvent, dependencies);
  await captureStartedPromise;
  await processPayPalEvent(captureEvent, dependencies);
  releaseCapture();
  await approvedPromise;

  assert.equal(database.transaction.state, "pagada");
  assert.equal(database.transaction.paypal_capture_id, "CAPTURE-1");
  assert.deepEqual(database.transitions, [
    { from: "enviada", to: "aprobada" },
    { from: "aprobada", to: "pagada" },
  ]);
  assert.deepEqual(
    await processPayPalEvent(captureEvent, dependencies),
    { duplicate: true },
  );
});

test("database transition cannot move a paid transaction backward", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-db-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  await database.createTransaction({
    id: "payment-2",
    referenceId: "glasspay-payment-2",
    amount: "10.00",
    currency: "MXN",
    concept: "Race test",
  });

  await database.updateOrder("payment-2", {
    orderId: "ORDER-2",
    approvalUrl: "https://example.test/approve",
  });

  assert.equal(await database.transitionState("payment-2", "aprobada"), true);
  assert.equal(await database.transitionState("payment-2", "pagada"), true);
  assert.equal(await database.transitionState("payment-2", "aprobada"), false);
  assert.equal(database.getTransaction("payment-2").state, "pagada");

  await database.close();
  fs.rmSync(directory, { recursive: true, force: true });
});

test("expiration does not overwrite an approved payment request", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-expiry-approved-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  await database.createTransaction({
    id: "payment-approved",
    referenceId: "glasspay-payment-approved",
    amount: "10.00",
    currency: "MXN",
    concept: "Approved",
  });
  await database.transitionState("payment-approved", "aprobada");
  await database.createPaymentRequest({
    id: "request-approved",
    payerId: "payer-1",
    resolutionHash: "resolution-approved",
    transactionId: "payment-approved",
    statusTokenHash: "token-approved",
    statusTokenExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    expiresAt: new Date(Date.now() - 1_000).toISOString(),
  });
  await database.expirePaymentRequests();
  assert.equal(database.getTransaction("payment-approved").state, "aprobada");
  assert.equal(database.getPaymentRequestStatus("request-approved", "token-approved").transaction_state, "aprobada");
  await database.close();
  fs.rmSync(directory, { recursive: true, force: true });
});

test("expiration does not overwrite a paid payment request", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-expiry-paid-"));
  const database = await createDatabase(path.join(directory, "test.db"));
  await database.createTransaction({
    id: "payment-paid",
    referenceId: "glasspay-payment-paid",
    amount: "10.00",
    currency: "MXN",
    concept: "Paid",
  });
  await database.transitionState("payment-paid", "aprobada");
  await database.transitionState("payment-paid", "pagada");
  await database.createPaymentRequest({
    id: "request-paid",
    payerId: "payer-1",
    resolutionHash: "resolution-paid",
    transactionId: "payment-paid",
    statusTokenHash: "token-paid",
    statusTokenExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    expiresAt: new Date(Date.now() - 1_000).toISOString(),
  });
  await database.expirePaymentRequests();
  assert.equal(database.getTransaction("payment-paid").state, "pagada");
  assert.equal(database.getPaymentRequestStatus("request-paid", "token-paid").transaction_state, "pagada");
  await database.close();
  fs.rmSync(directory, { recursive: true, force: true });
});

test("existing databases receive status-token columns during migration", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "glasspay-migration-"));
  const databasePath = path.join(directory, "legacy.db");
  const SQL = await initSqlJs({
    locateFile: (file) => require.resolve(`sql.js/dist/${file}`),
  });
  const legacyDatabase = new SQL.Database();
  legacyDatabase.run(`
    CREATE TABLE payment_requests (
      id TEXT PRIMARY KEY,
      payer_id TEXT NOT NULL,
      resolution_hash TEXT NOT NULL UNIQUE,
      transaction_id TEXT NOT NULL UNIQUE,
      state TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
  fs.writeFileSync(databasePath, Buffer.from(legacyDatabase.export()));
  const database = await createDatabase(databasePath);
  await database.createTransaction({
    id: "payment-migrated",
    referenceId: "glasspay-payment-migrated",
    amount: "10.00",
    currency: "MXN",
    concept: "Migrated",
  });
  await database.createPaymentRequest({
    id: "request-migrated",
    payerId: "payer-1",
    resolutionHash: "resolution-migrated",
    transactionId: "payment-migrated",
    statusTokenHash: "token-migrated",
    statusTokenExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
  });
  assert.equal(
    database.getPaymentRequestStatus("request-migrated", "token-migrated").concept,
    "Migrated",
  );
  await database.close();
  fs.rmSync(directory, { recursive: true, force: true });
});
