const fs = require("node:fs");
const path = require("node:path");
const initSqlJs = require("sql.js");

const TRANSACTION_STATES = new Set([
  "creada",
  "enviada",
  "aprobada",
  "pagada",
  "fallida",
  "expirada",
]);

async function createDatabase(databasePath) {
  fs.mkdirSync(path.dirname(databasePath), { recursive: true });
  const SQL = await initSqlJs({
    locateFile: (file) => require.resolve(`sql.js/dist/${file}`),
  });
  const database = fs.existsSync(databasePath)
    ? new SQL.Database(fs.readFileSync(databasePath))
    : new SQL.Database();

  database.run(`
    CREATE TABLE IF NOT EXISTS transactions (
      id TEXT PRIMARY KEY,
      paypal_order_id TEXT UNIQUE,
      paypal_capture_id TEXT UNIQUE,
      reference_id TEXT NOT NULL UNIQUE,
      amount TEXT NOT NULL,
      currency TEXT NOT NULL,
      concept TEXT NOT NULL,
      approval_url TEXT,
      state TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS webhook_events (
      id TEXT PRIMARY KEY,
      event_type TEXT NOT NULL,
      status TEXT NOT NULL,
      received_at TEXT NOT NULL,
      processed_at TEXT
    );
  `);

  const save = () => {
    fs.writeFileSync(databasePath, Buffer.from(database.export()));
  };
  const now = () => new Date().toISOString();
  const get = (query, parameters = []) => {
    const statement = database.prepare(query);
    statement.bind(parameters);
    const result = statement.step() ? statement.getAsObject() : null;
    statement.free();
    return result;
  };
  const run = (query, parameters = []) => {
    database.run(query, parameters);
    save();
  };

  save();

  return {
    createTransaction(transaction) {
      const timestamp = now();
      run(`
        INSERT INTO transactions
          (id, reference_id, amount, currency, concept, state, created_at, updated_at)
        VALUES
          (?, ?, ?, ?, ?, 'creada', ?, ?)
      `, [
        transaction.id,
        transaction.referenceId,
        transaction.amount,
        transaction.currency,
        transaction.concept,
        timestamp,
        timestamp,
      ]);
    },

    getTransaction(id) {
      return get("SELECT * FROM transactions WHERE id = ?", [id]);
    },

    findByOrderId(orderId) {
      return get("SELECT * FROM transactions WHERE paypal_order_id = ?", [orderId]);
    },

    findByCaptureId(captureId) {
      return get("SELECT * FROM transactions WHERE paypal_capture_id = ?", [captureId]);
    },

    updateOrder(id, order) {
      run(`
        UPDATE transactions
        SET paypal_order_id = ?, approval_url = ?, state = 'enviada', updated_at = ?
        WHERE id = ?
      `, [order.orderId, order.approvalUrl, now(), id]);
    },

    updateState(id, state) {
      if (!TRANSACTION_STATES.has(state)) {
        throw new Error(`Invalid transaction state: ${state}`);
      }
      run("UPDATE transactions SET state = ?, updated_at = ? WHERE id = ?", [state, now(), id]);
    },

    setCapture(id, captureId) {
      run(`
        UPDATE transactions
        SET paypal_capture_id = ?, updated_at = ?
        WHERE id = ?
      `, [captureId, now(), id]);
    },

    startWebhookEvent(eventId, eventType) {
      const existing = get("SELECT status FROM webhook_events WHERE id = ?", [eventId]);
      if (existing) {
        return false;
      }
      run(`
        INSERT INTO webhook_events (id, event_type, status, received_at)
        VALUES (?, ?, 'processing', ?)
      `, [eventId, eventType, now()]);
      return true;
    },

    completeWebhookEvent(eventId) {
      run(`
        UPDATE webhook_events
        SET status = 'processed', processed_at = ?
        WHERE id = ?
      `, [now(), eventId]);
    },

    failWebhookEvent(eventId) {
      run("DELETE FROM webhook_events WHERE id = ?", [eventId]);
    },

    close() {
      save();
      database.close();
    },
  };
}

module.exports = { createDatabase };
