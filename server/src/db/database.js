const fs = require("node:fs");
const path = require("node:path");
const initSqlJs = require("sql.js");

const TRANSACTION_STATES = new Set([
  "creada",
  "pendiente_de_pagador",
  "enviada",
  "aprobada",
  "pagada",
  "fallida",
  "expirada",
  "rechazada",
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
      payer_id TEXT,
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

    CREATE TABLE IF NOT EXISTS payer_profiles (
      id TEXT PRIMARY KEY,
      display_name TEXT NOT NULL,
      session_salt TEXT NOT NULL,
      session_hash TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS collector_profiles (
      id TEXT PRIMARY KEY,
      display_name TEXT NOT NULL,
      session_salt TEXT NOT NULL,
      session_hash TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS qr_tokens (
      token_hash TEXT PRIMARY KEY,
      payer_id TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS payer_resolutions (
      resolution_hash TEXT PRIMARY KEY,
      payer_id TEXT NOT NULL,
      collector_id TEXT,
      expires_at TEXT NOT NULL,
      used_at TEXT,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS payment_requests (
      id TEXT PRIMARY KEY,
      payer_id TEXT NOT NULL,
      collector_id TEXT,
      resolution_hash TEXT NOT NULL UNIQUE,
      transaction_id TEXT NOT NULL UNIQUE,
      status_token_hash TEXT UNIQUE,
      status_token_expires_at TEXT,
      state TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS charge_proposals (
      id TEXT PRIMARY KEY,
      resolution_hash TEXT NOT NULL,
      amount TEXT NOT NULL,
      currency TEXT NOT NULL,
      concept TEXT NOT NULL,
      method TEXT NOT NULL,
      status TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      used_at TEXT,
      created_at TEXT NOT NULL
    );
  `);
  const transactionColumns = database.exec("PRAGMA table_info(transactions)")[0]?.values || [];
  if (!transactionColumns.some(([, column]) => column === "payer_id")) {
    database.run("ALTER TABLE transactions ADD COLUMN payer_id TEXT");
  }
  const paymentRequestColumns = database.exec("PRAGMA table_info(payment_requests)")[0]?.values || [];
  if (!paymentRequestColumns.some(([, column]) => column === "status_token_hash")) {
    database.run("ALTER TABLE payment_requests ADD COLUMN status_token_hash TEXT");
  }
  if (!paymentRequestColumns.some(([, column]) => column === "status_token_expires_at")) {
    database.run("ALTER TABLE payment_requests ADD COLUMN status_token_expires_at TEXT");
  }
  if (!paymentRequestColumns.some(([, column]) => column === "collector_id")) {
    database.run("ALTER TABLE payment_requests ADD COLUMN collector_id TEXT");
  }
  const resolutionColumns = database.exec("PRAGMA table_info(payer_resolutions)")[0]?.values || [];
  if (!resolutionColumns.some(([, column]) => column === "collector_id")) {
    database.run("ALTER TABLE payer_resolutions ADD COLUMN collector_id TEXT");
  }
  database.run(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_requests_status_token_hash
    ON payment_requests(status_token_hash)
    WHERE status_token_hash IS NOT NULL
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
  const all = (query, parameters = []) => {
    const statement = database.prepare(query);
    statement.bind(parameters);
    const results = [];
    while (statement.step()) {
      results.push(statement.getAsObject());
    }
    statement.free();
    return results;
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
            (id, reference_id, payer_id, amount, currency, concept, state, created_at, updated_at)
          VALUES
            (?, ?, ?, ?, ?, ?, ?, ?, ?)
        `, [
          transaction.id,
          transaction.referenceId,
          transaction.payerId || null,
          transaction.amount,
          transaction.currency,
          transaction.concept,
          transaction.state || "creada",
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

    updateOrder(id, order, state = "enviada") {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transactions
          SET paypal_order_id = ?, approval_url = ?, state = ?, updated_at = ?
          WHERE id = ? AND state IN ('creada', 'pendiente_de_pagador')
        `, [order.orderId, order.approvalUrl, state, now(), id]);
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
            pendiente_de_pagador: 1,
            enviada: 2,
            aprobada: 3,
            pagada: 4,
          };
          database.run(`
            UPDATE transactions
            SET state = ?, updated_at = ?
            WHERE id = ?
              AND state IN ('creada', 'pendiente_de_pagador', 'enviada', 'aprobada')
              AND CASE state
                WHEN 'creada' THEN 0
                WHEN 'pendiente_de_pagador' THEN 1
                WHEN 'enviada' THEN 2
                WHEN 'aprobada' THEN 3
                ELSE 4
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

    createPayerProfile(profile) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO payer_profiles (id, display_name, session_salt, session_hash, created_at)
          VALUES (?, ?, ?, ?, ?)
        `, [profile.id, profile.displayName, profile.sessionSalt, profile.sessionHash, now()]);
      });
    },

    getPayerProfile(id) {
      return get("SELECT * FROM payer_profiles WHERE id = ?", [id]);
    },

    createCollectorProfile(profile) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO collector_profiles (id, display_name, session_salt, session_hash, created_at)
          VALUES (?, ?, ?, ?, ?)
        `, [profile.id, profile.displayName, profile.sessionSalt, profile.sessionHash, now()]);
      });
    },

    getCollectorProfile(id) {
      if (typeof id !== "string") {
        return null;
      }
      return get("SELECT * FROM collector_profiles WHERE id = ?", [id]);
    },

    getCollectorSummary(collectorId) {
      return all(`
        SELECT t.currency,
          COALESCE(SUM(CASE WHEN t.state = 'pagada'
            THEN CAST(ROUND(CAST(t.amount AS REAL) * 100) AS INTEGER) ELSE 0 END), 0)
            AS paid_cents,
          SUM(CASE WHEN t.state = 'pagada' THEN 1 ELSE 0 END) AS paid_count,
          SUM(CASE WHEN t.state IN ('pendiente_de_pagador', 'enviada', 'aprobada') THEN 1 ELSE 0 END)
            AS in_progress_count
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        WHERE r.collector_id = ?
        GROUP BY t.currency
        ORDER BY t.currency
      `, [collectorId]).map((row) => ({
        currency: row.currency,
        paidTotal: (row.paid_cents / 100).toFixed(2),
        paidCount: row.paid_count,
        inProgressCount: row.in_progress_count,
      }));
    },

    listCollectorMovements(collectorId, limit = 50) {
      return all(`
        SELECT r.id, t.state, t.amount, t.currency, t.concept, p.display_name,
          r.created_at, t.updated_at
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        JOIN payer_profiles p ON p.id = r.payer_id
        WHERE r.collector_id = ?
        ORDER BY r.created_at DESC
        LIMIT ?
      `, [collectorId, limit]).map((row) => ({
        movementId: row.id,
        state: row.state,
        amount: row.amount,
        currency: row.currency,
        concept: row.concept,
        payerName: row.display_name,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
      }));
    },

    createQrToken(token) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO qr_tokens (token_hash, payer_id, expires_at, created_at)
          VALUES (?, ?, ?, ?)
        `, [token.hash, token.payerId, token.expiresAt, now()]);
      });
    },

    findPayerByQrHash(hash) {
      return get(`
        SELECT p.id, p.display_name
        FROM qr_tokens q
        JOIN payer_profiles p ON p.id = q.payer_id
        WHERE q.token_hash = ? AND q.expires_at > ?
      `, [hash, now()]);
    },

    createPayerResolution(resolution) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO payer_resolutions
            (resolution_hash, payer_id, collector_id, expires_at, created_at)
          VALUES (?, ?, ?, ?, ?)
        `, [resolution.hash, resolution.payerId, resolution.collectorId, resolution.expiresAt, now()]);
      });
    },

    consumePayerResolution(hash, collectorId) {
      return enqueueWrite(() => {
        const resolution = get(`
          SELECT r.payer_id, p.display_name
          FROM payer_resolutions r
          JOIN payer_profiles p ON p.id = r.payer_id
          WHERE r.resolution_hash = ?
            AND r.collector_id = ?
            AND r.used_at IS NULL
            AND r.expires_at > ?
        `, [hash, collectorId, now()]);
        if (!resolution) {
          return null;
        }
        database.run(`
          UPDATE payer_resolutions
          SET used_at = ?
          WHERE resolution_hash = ? AND used_at IS NULL
        `, [now(), hash]);
        return resolution;
      });
    },

    findPayerResolution(hash, collectorId) {
      return get(`
        SELECT r.payer_id, p.display_name
        FROM payer_resolutions r
        JOIN payer_profiles p ON p.id = r.payer_id
        WHERE r.resolution_hash = ?
          AND r.collector_id = ?
          AND r.used_at IS NULL
          AND r.expires_at > ?
      `, [hash, collectorId, now()]);
    },

    expirePaymentRequests() {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
          UPDATE payment_requests
          SET state = 'expirada', updated_at = ?
          WHERE state = 'pendiente_de_pagador' AND expires_at <= ?
            AND transaction_id IN (
              SELECT id FROM transactions
              WHERE state NOT IN ('aprobada', 'pagada', 'fallida', 'rechazada', 'expirada')
            )
        `, [timestamp, timestamp]);
        database.run(`
          UPDATE transactions
          SET state = 'expirada', updated_at = ?
          WHERE state = 'pendiente_de_pagador'
            AND id IN (
              SELECT transaction_id FROM payment_requests
              WHERE state = 'expirada' AND updated_at = ?
            )
        `, [timestamp, timestamp]);
      });
    },

    countPendingRequests(payerId) {
      return get(`
        SELECT COUNT(*) AS count
        FROM payment_requests
        WHERE payer_id = ? AND state = 'pendiente_de_pagador' AND expires_at > ?
      `, [payerId, now()]).count;
    },

    createPaymentRequest(request) {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
          INSERT INTO payment_requests
            (id, payer_id, collector_id, resolution_hash, transaction_id, status_token_hash,
             status_token_expires_at, state, expires_at, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, 'pendiente_de_pagador', ?, ?, ?)
        `, [
          request.id,
          request.payerId,
          request.collectorId || null,
          request.resolutionHash,
          request.transactionId,
          request.statusTokenHash,
          request.statusTokenExpiresAt,
          request.expiresAt,
          timestamp,
          timestamp,
        ]);
      });
    },

    listPayerRequests(payerId) {
      return all(`
        SELECT r.id, r.state, r.expires_at, r.created_at, r.updated_at,
          t.amount, t.currency, t.concept, t.approval_url
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        WHERE r.payer_id = ?
          AND r.state IN ('pendiente_de_pagador', 'aprobada', 'pagada', 'rechazada', 'expirada', 'fallida')
        ORDER BY r.created_at DESC
      `, [payerId]).map((request) => ({
        id: request.id,
        state: request.state,
        expiresAt: request.expires_at,
        createdAt: request.created_at,
        updatedAt: request.updated_at,
        amount: request.amount,
        currency: request.currency,
        concept: request.concept,
        approvalUrl: request.approval_url,
      }));
    },

    getPaymentRequestStatus(requestId, resolutionHash) {
      return get(`
        SELECT r.id, r.state AS request_state, r.expires_at,
          t.state AS transaction_state, t.amount, t.currency, t.concept
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        WHERE r.id = ?
          AND r.status_token_hash = ?
          AND r.status_token_expires_at > ?
      `, [requestId, resolutionHash, now()]);
    },

    rejectPaymentRequest(requestId, payerId) {
      return enqueueWrite(() => {
        const request = get(`
          SELECT transaction_id
          FROM payment_requests
          WHERE id = ? AND payer_id = ? AND state = 'pendiente_de_pagador'
        `, [requestId, payerId]);
        if (!request) {
          return false;
        }
        const timestamp = now();
        database.run(`
          UPDATE payment_requests
          SET state = 'rechazada', updated_at = ?
          WHERE id = ? AND payer_id = ? AND state = 'pendiente_de_pagador'
        `, [timestamp, requestId, payerId]);
        database.run(`
          UPDATE transactions
          SET state = 'rechazada', updated_at = ?
          WHERE id = ? AND state = 'pendiente_de_pagador'
        `, [timestamp, request.transaction_id]);
        return database.getRowsModified() > 0;
      });
    },

    createChargeProposal(proposal) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO charge_proposals
            (id, resolution_hash, amount, currency, concept, method, status, expires_at, created_at)
          VALUES (?, ?, ?, ?, ?, ?, 'ready', ?, ?)
        `, [
          proposal.id,
          proposal.resolutionHash,
          proposal.amount,
          proposal.currency,
          proposal.concept,
          proposal.method,
          proposal.expiresAt,
          now(),
        ]);
      });
    },

    consumeChargeProposal(proposalId, collectorId) {
      return enqueueWrite(() => {
        const timestamp = now();
        // Solo el cobrador dueño de la resolución puede consumir la propuesta.
        database.run(`
          UPDATE charge_proposals
          SET status = 'used', used_at = ?
          WHERE id = ? AND status = 'ready' AND used_at IS NULL AND expires_at > ?
            AND resolution_hash IN (
              SELECT resolution_hash FROM payer_resolutions WHERE collector_id = ?
            )
        `, [timestamp, proposalId, timestamp, collectorId]);
        if (database.getRowsModified() !== 1) {
          return null;
        }
        return get(`
          SELECT id, resolution_hash, amount, currency, concept, method
          FROM charge_proposals
          WHERE id = ?
        `, [proposalId]);
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
