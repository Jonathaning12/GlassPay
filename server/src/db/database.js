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
  let writeQueue = Promise.resolve();
  const now = () => new Date().toISOString();
  const get = (query, parameters = []) => {
    const statement = database.prepare(query);
    statement.bind(parameters);
    const result = statement.step() ? statement.getAsObject() : null;
    statement.free();
    return result;
  };
  const enqueueWrite = (operation) => {
    const result = writeQueue.then(() => {
      const value = operation();
      save();
      return value;
    });
    writeQueue = result.catch(() => undefined);
    return result;
  };

  save();

  return {
    createTransaction(transaction) {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
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
      });
    },

    getTransaction(id) {
      return get("SELECT * FROM transactions WHERE id = ?", [id]);
    },

    findByOrderId(orderId) {
      return get("SELECT * FROM transactions WHERE paypal_order_id = ?", [orderId]);
    },

    updateOrder(id, order) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transactions
          SET paypal_order_id = ?, approval_url = ?, state = 'enviada', updated_at = ?
          WHERE id = ? AND state = 'creada'
        `, [order.orderId, order.approvalUrl, now(), id]);
      });
    },

    transitionState(id, state) {
      if (!TRANSACTION_STATES.has(state)) {
        throw new Error(`Invalid transaction state: ${state}`);
      }

      if (!["enviada", "aprobada", "pagada", "fallida", "expirada"].includes(state)) {
        throw new Error(`State cannot be transitioned: ${state}`);
      }

      return enqueueWrite(() => {
        if (["fallida", "expirada"].includes(state)) {
          database.run(`
            UPDATE transactions
            SET state = ?, updated_at = ?
            WHERE id = ? AND state NOT IN ('pagada', 'fallida', 'expirada')
          `, [state, now(), id]);
        } else {
          const ranks = {
            creada: 0,
            enviada: 1,
            aprobada: 2,
            pagada: 3,
          };
          database.run(`
            UPDATE transactions
            SET state = ?, updated_at = ?
            WHERE id = ?
              AND state IN ('creada', 'enviada', 'aprobada')
              AND CASE state
                WHEN 'creada' THEN 0
                WHEN 'enviada' THEN 1
                WHEN 'aprobada' THEN 2
                ELSE 3
              END < ?
          `, [state, now(), id, ranks[state]]);
        }
        return database.getRowsModified() > 0;
      });
    },

    setCapture(id, captureId) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transactions
          SET paypal_capture_id = ?, updated_at = ?
          WHERE id = ? AND paypal_capture_id IS NULL
        `, [captureId, now(), id]);
      });
    },

    startWebhookEvent(eventId, eventType) {
      return enqueueWrite(() => {
        const existing = get("SELECT status FROM webhook_events WHERE id = ?", [eventId]);
        if (existing) {
          return false;
        }
        database.run(`
          INSERT INTO webhook_events (id, event_type, status, received_at)
          VALUES (?, ?, 'processing', ?)
        `, [eventId, eventType, now()]);
        return true;
      });
    },

    completeWebhookEvent(eventId) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE webhook_events
          SET status = 'processed', processed_at = ?
          WHERE id = ?
        `, [now(), eventId]);
      });
    },

    failWebhookEvent(eventId) {
      return enqueueWrite(() => {
        database.run("DELETE FROM webhook_events WHERE id = ?", [eventId]);
      });
    },

    async close() {
      await writeQueue;
      save();
      database.close();
    },
  };
}

module.exports = { createDatabase };
