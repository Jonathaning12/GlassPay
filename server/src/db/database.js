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

    CREATE TABLE IF NOT EXISTS collector_contacts (
      id TEXT PRIMARY KEY,
      collector_id TEXT NOT NULL,
      payer_id TEXT NOT NULL,
      created_at TEXT NOT NULL,
      UNIQUE (collector_id, payer_id)
    );

    CREATE TABLE IF NOT EXISTS assistant_proposals (
      id TEXT PRIMARY KEY,
      collector_id TEXT NOT NULL,
      kind TEXT NOT NULL,
      payload TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      used_at TEXT,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS scheduled_actions (
      id TEXT PRIMARY KEY,
      collector_id TEXT NOT NULL,
      kind TEXT NOT NULL,
      contact_id TEXT NOT NULL,
      payer_id TEXT NOT NULL,
      amount TEXT NOT NULL,
      currency TEXT NOT NULL,
      concept TEXT NOT NULL,
      run_at TEXT NOT NULL,
      auto_execute INTEGER NOT NULL,
      state TEXT NOT NULL,
      payment_request_id TEXT,
      failure TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS transfers (
      id TEXT PRIMARY KEY,
      sender_collector_id TEXT NOT NULL,
      recipient_payer_id TEXT NOT NULL,
      amount TEXT NOT NULL,
      currency TEXT NOT NULL,
      note TEXT NOT NULL,
      state TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      paypal_batch_id TEXT,
      paypal_status TEXT,
      receiver_email TEXT,
      payout_attempts INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
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
  const collectorColumns = database.exec("PRAGMA table_info(collector_profiles)")[0]?.values || [];
  if (!collectorColumns.some(([, column]) => column === "payer_id")) {
    database.run("ALTER TABLE collector_profiles ADD COLUMN payer_id TEXT");
  }
  if (!collectorColumns.some(([, column]) => column === "payout_email")) {
    database.run("ALTER TABLE collector_profiles ADD COLUMN payout_email TEXT");
  }
  const transferColumns = database.exec("PRAGMA table_info(transfers)")[0]?.values || [];
  if (!transferColumns.some(([, column]) => column === "receiver_email")) {
    database.run("ALTER TABLE transfers ADD COLUMN receiver_email TEXT");
  }
  if (!transferColumns.some(([, column]) => column === "payout_attempts")) {
    database.run("ALTER TABLE transfers ADD COLUMN payout_attempts INTEGER NOT NULL DEFAULT 0");
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

    // Perfil de pagador propio del cobrador: le permite mostrar su QR y recibir cobros.
    linkCollectorPayer(collectorId, payer) {
      return enqueueWrite(() => {
        const current = get("SELECT payer_id FROM collector_profiles WHERE id = ?", [collectorId]);
        if (current?.payer_id) {
          return current.payer_id;
        }
        database.run(`
          INSERT INTO payer_profiles (id, display_name, session_salt, session_hash, created_at)
          VALUES (?, ?, ?, ?, ?)
        `, [payer.id, payer.displayName, payer.sessionSalt, payer.sessionHash, now()]);
        database.run("UPDATE collector_profiles SET payer_id = ? WHERE id = ? AND payer_id IS NULL", [
          payer.id,
          collectorId,
        ]);
        return payer.id;
      });
    },

    setCollectorPayoutEmail(collectorId, email) {
      return enqueueWrite(() => {
        database.run("UPDATE collector_profiles SET payout_email = ? WHERE id = ?", [email, collectorId]);
      });
    },

    getPayoutEmailForPayer(payerId) {
      return get("SELECT payout_email FROM collector_profiles WHERE payer_id = ?", [payerId])?.payout_email
        || null;
    },

    // Saldo en centavos = cobrado (pagada) − envíos que reservan dinero.
    getAvailableCents(collectorId, currency) {
      const paid = get(`
        SELECT COALESCE(SUM(CAST(ROUND(CAST(t.amount AS REAL) * 100) AS INTEGER)), 0) AS cents
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        WHERE r.collector_id = ? AND t.currency = ? AND t.state = 'pagada'
      `, [collectorId, currency]).cents;
      const sent = get(`
        SELECT COALESCE(SUM(CAST(ROUND(CAST(amount AS REAL) * 100) AS INTEGER)), 0) AS cents
        FROM transfers
        WHERE sender_collector_id = ? AND currency = ?
          AND state IN ('procesando', 'completada', 'no_reclamada')
      `, [collectorId, currency]).cents;
      return { paidCents: paid, sentCents: sent, availableCents: paid - sent };
    },

    createTransferProposal(transfer) {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
          INSERT INTO transfers
            (id, sender_collector_id, recipient_payer_id, amount, currency, note, state,
             expires_at, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, 'propuesta', ?, ?, ?)
        `, [
          transfer.id,
          transfer.senderCollectorId,
          transfer.recipientPayerId,
          transfer.amount,
          transfer.currency,
          transfer.note,
          transfer.expiresAt,
          timestamp,
          timestamp,
        ]);
      });
    },

    // Consume la propuesta y reserva el saldo en una sola escritura serializada.
    confirmTransferProposal(transferId, collectorId) {
      return enqueueWrite(() => {
        const transfer = get(`
          SELECT * FROM transfers
          WHERE id = ? AND sender_collector_id = ? AND state = 'propuesta' AND expires_at > ?
        `, [transferId, collectorId, now()]);
        if (!transfer) {
          return null;
        }
        const amountCents = Math.round(Number.parseFloat(transfer.amount) * 100);
        const { availableCents } = this.getAvailableCents(collectorId, transfer.currency);
        if (amountCents > availableCents) {
          return { insufficient: true };
        }
        // El receptor se fija aquí: los reintentos nunca cambian de destinatario.
        const receiverEmail = this.getPayoutEmailForPayer(transfer.recipient_payer_id);
        if (!receiverEmail) {
          return { cannotReceive: true };
        }
        database.run(`
          UPDATE transfers SET state = 'procesando', receiver_email = ?, updated_at = ?
          WHERE id = ? AND state = 'propuesta'
        `, [receiverEmail, now(), transferId]);
        return { transfer: { ...transfer, state: "procesando" } };
      });
    },

    // Solo avanza envíos que siguen abiertos; un estado final no se reescribe.
    updateTransferPayout(transferId, { batchId, paypalStatus, state }) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transfers
          SET paypal_batch_id = COALESCE(?, paypal_batch_id),
              paypal_status = COALESCE(?, paypal_status),
              state = ?,
              updated_at = ?
          WHERE id = ? AND state IN ('procesando', 'no_reclamada')
            AND (? <> 'fallida' OR paypal_batch_id IS NOT NULL OR ? IS NOT NULL)
        `, [batchId || null, paypalStatus || null, state, now(), transferId, state, batchId || null]);
      });
    },

    getTransferForPayout(transferId) {
      return get("SELECT * FROM transfers WHERE id = ?", [transferId]);
    },

    // Registra un intento de creación del payout; devuelve su número o null si ya no aplica.
    beginPayoutAttempt(transferId) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transfers SET payout_attempts = payout_attempts + 1, updated_at = ?
          WHERE id = ? AND state = 'procesando' AND paypal_batch_id IS NULL
        `, [now(), transferId]);
        if (database.getRowsModified() !== 1) {
          return null;
        }
        return get("SELECT payout_attempts FROM transfers WHERE id = ?", [transferId]).payout_attempts;
      });
    },

    // Solo libera el saldo si PayPal rechazó el PRIMER intento y nunca hubo lote.
    markTransferRejected(transferId, paypalStatus) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transfers SET state = 'fallida', paypal_status = ?, updated_at = ?
          WHERE id = ? AND state = 'procesando' AND paypal_batch_id IS NULL AND payout_attempts = 1
        `, [paypalStatus, now(), transferId]);
      });
    },

    markTransferForReview(transferId, paypalStatus) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE transfers SET paypal_status = ?, updated_at = ?
          WHERE id = ? AND state = 'procesando' AND paypal_batch_id IS NULL
        `, [paypalStatus, now(), transferId]);
      });
    },

    listOpenTransfers(collectorId, notUpdatedSinceMs = 30_000) {
      const cutoff = new Date(Date.now() - notUpdatedSinceMs).toISOString();
      return all(`
        SELECT id FROM transfers
        WHERE sender_collector_id = ? AND state IN ('procesando', 'no_reclamada')
          AND (paypal_status IS NULL OR paypal_status NOT LIKE 'REVIEW_%')
          AND updated_at <= ?
        ORDER BY created_at
      `, [collectorId, cutoff]).map((row) => row.id);
    },

    listCollectorTransfers(collectorId, limit = 50) {
      return all(`
        SELECT t.id, t.amount, t.currency, t.note, t.state, t.created_at, t.updated_at,
          p.display_name AS recipient_name
        FROM transfers t
        JOIN payer_profiles p ON p.id = t.recipient_payer_id
        WHERE t.sender_collector_id = ? AND t.state NOT IN ('propuesta')
        ORDER BY t.created_at DESC
        LIMIT ?
      `, [collectorId, limit]).map((row) => ({
        transferId: row.id,
        state: row.state,
        amount: row.amount,
        currency: row.currency,
        note: row.note,
        recipientName: row.recipient_name,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
      }));
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

    // Cobros y envíos de un rango [desde, hasta) para estadísticas; montos en centavos.
    listCollectorActivityBetween(collectorId, fromIso, toIso) {
      const charges = all(`
        SELECT t.state, t.currency, p.display_name,
          CAST(ROUND(CAST(t.amount AS REAL) * 100) AS INTEGER) AS cents, r.created_at
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        JOIN payer_profiles p ON p.id = r.payer_id
        WHERE r.collector_id = ? AND r.created_at >= ? AND r.created_at < ?
      `, [collectorId, fromIso, toIso]).map((row) => ({
        state: row.state,
        currency: row.currency,
        payerName: row.display_name,
        cents: row.cents,
        createdAt: row.created_at,
      }));
      const transfers = all(`
        SELECT state, currency, CAST(ROUND(CAST(amount AS REAL) * 100) AS INTEGER) AS cents, created_at
        FROM transfers
        WHERE sender_collector_id = ? AND created_at >= ? AND created_at < ?
          AND state NOT IN ('propuesta')
      `, [collectorId, fromIso, toIso]).map((row) => ({
        state: row.state,
        currency: row.currency,
        cents: row.cents,
        createdAt: row.created_at,
      }));
      return { charges, transfers };
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

    // Resolución vigente del cobrador, aunque ya se haya usado para cobrar:
    // permite guardar como contacto al pagador recién cobrado.
    findCollectorResolution(hash, collectorId) {
      return get(`
        SELECT r.payer_id, p.display_name
        FROM payer_resolutions r
        JOIN payer_profiles p ON p.id = r.payer_id
        WHERE r.resolution_hash = ?
          AND r.collector_id = ?
          AND r.expires_at > ?
      `, [hash, collectorId, now()]);
    },

    saveCollectorContact(contact, maxContacts) {
      return enqueueWrite(() => {
        const findContact = () => get(`
          SELECT c.id, c.created_at, p.display_name
          FROM collector_contacts c
          JOIN payer_profiles p ON p.id = c.payer_id
          WHERE c.collector_id = ? AND c.payer_id = ?
        `, [contact.collectorId, contact.payerId]);
        const existing = findContact();
        if (!existing) {
          const { count } = get(
            "SELECT COUNT(*) AS count FROM collector_contacts WHERE collector_id = ?",
            [contact.collectorId],
          );
          if (count >= maxContacts) {
            return { limitReached: true };
          }
        }
        database.run(`
          INSERT OR IGNORE INTO collector_contacts (id, collector_id, payer_id, created_at)
          VALUES (?, ?, ?, ?)
        `, [contact.id, contact.collectorId, contact.payerId, now()]);
        const created = database.getRowsModified() === 1;
        const row = findContact();
        if (!row) {
          return null;
        }
        return {
          created,
          contact: { contactId: row.id, displayName: row.display_name, createdAt: row.created_at },
        };
      });
    },

    listCollectorContacts(collectorId) {
      return all(`
        SELECT c.id, c.created_at, p.display_name
        FROM collector_contacts c
        JOIN payer_profiles p ON p.id = c.payer_id
        WHERE c.collector_id = ?
        ORDER BY p.display_name COLLATE NOCASE, c.created_at
        LIMIT 200
      `, [collectorId]).map((row) => ({
        contactId: row.id,
        displayName: row.display_name,
        createdAt: row.created_at,
      }));
    },

    getCollectorContact(contactId, collectorId) {
      return get(`
        SELECT c.id, c.payer_id, p.display_name
        FROM collector_contacts c
        JOIN payer_profiles p ON p.id = c.payer_id
        WHERE c.id = ? AND c.collector_id = ?
      `, [contactId, collectorId]);
    },

    findContactIdForPayer(collectorId, payerId) {
      return get(`
        SELECT id FROM collector_contacts WHERE collector_id = ? AND payer_id = ?
      `, [collectorId, payerId])?.id || null;
    },

    deleteCollectorContact(contactId, collectorId) {
      return enqueueWrite(() => {
        database.run(`
          DELETE FROM collector_contacts WHERE id = ? AND collector_id = ?
        `, [contactId, collectorId]);
        return database.getRowsModified() === 1;
      });
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
          t.amount, t.currency, t.concept, t.approval_url, c.display_name AS collector_name
        FROM payment_requests r
        JOIN transactions t ON t.id = r.transaction_id
        LEFT JOIN collector_profiles c ON c.id = r.collector_id
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
        collectorName: request.collector_name || null,
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

    createAssistantProposal({ id, collectorId, kind, payload, expiresAt }) {
      return enqueueWrite(() => {
        database.run(`
          INSERT INTO assistant_proposals (id, collector_id, kind, payload, expires_at, created_at)
          VALUES (?, ?, ?, ?, ?, ?)
        `, [id, collectorId, kind, JSON.stringify(payload), expiresAt, now()]);
      });
    },

    // Un solo uso, solo el dueño y solo vigente; cualquier otro caso devuelve null.
    consumeAssistantProposal(proposalId, collectorId) {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
          UPDATE assistant_proposals
          SET used_at = ?
          WHERE id = ? AND collector_id = ? AND used_at IS NULL AND expires_at > ?
        `, [timestamp, proposalId, collectorId, timestamp]);
        if (database.getRowsModified() !== 1) {
          return null;
        }
        const row = get("SELECT kind, payload FROM assistant_proposals WHERE id = ?", [proposalId]);
        return { kind: row.kind, payload: JSON.parse(row.payload) };
      });
    },

    // Crea la programación solo si no supera el tope de activas (verificado dentro de la escritura).
    createScheduledAction(action, maxActive) {
      return enqueueWrite(() => {
        const active = get(`
          SELECT COUNT(*) AS total FROM scheduled_actions
          WHERE collector_id = ? AND state IN ('programado', 'esperando_confirmacion', 'ejecutando')
        `, [action.collectorId]).total;
        if (active >= maxActive) {
          return false;
        }
        const timestamp = now();
        database.run(`
          INSERT INTO scheduled_actions
            (id, collector_id, kind, contact_id, payer_id, amount, currency, concept, run_at,
             auto_execute, state, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'programado', ?, ?)
        `, [
          action.id, action.collectorId, action.kind, action.contactId, action.payerId, action.amount,
          action.currency, action.concept, action.runAt, action.autoExecute ? 1 : 0, timestamp, timestamp,
        ]);
        return true;
      });
    },

    listScheduledActions(collectorId, limit = 30) {
      return all(`
        SELECT s.id, s.kind, s.amount, s.currency, s.concept, s.run_at, s.auto_execute, s.state,
          s.failure, s.created_at, s.updated_at, p.display_name
        FROM scheduled_actions s
        JOIN payer_profiles p ON p.id = s.payer_id
        WHERE s.collector_id = ?
        ORDER BY CASE WHEN s.state IN ('programado', 'esperando_confirmacion', 'ejecutando') THEN 0 ELSE 1 END,
          s.run_at
        LIMIT ?
      `, [collectorId, limit]).map((row) => ({
        scheduledId: row.id,
        kind: row.kind,
        contactName: row.display_name,
        amount: row.amount,
        currency: row.currency,
        concept: row.concept,
        runAt: row.run_at,
        autoExecute: row.auto_execute === 1,
        state: row.state,
        failure: row.failure,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
      }));
    },

    cancelScheduledAction(scheduledId, collectorId) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE scheduled_actions SET state = 'cancelado', updated_at = ?
          WHERE id = ? AND collector_id = ? AND state IN ('programado', 'esperando_confirmacion')
        `, [now(), scheduledId, collectorId]);
        return database.getRowsModified() === 1;
      });
    },

    /**
     * Reclama lo vencido de forma atómica: lo automático pasa a "ejecutando" (se devuelve para
     * ejecutarlo), lo manual a "esperando_confirmacion" y lo muy atrasado a "omitido".
     */
    claimDueScheduledActions(nowIso, lateLimitIso) {
      return enqueueWrite(() => {
        const timestamp = now();
        database.run(`
          UPDATE scheduled_actions SET state = 'omitido', failure = 'atrasado', updated_at = ?
          WHERE state = 'programado' AND run_at < ?
        `, [timestamp, lateLimitIso]);
        // Lo que esperaba confirmación tampoco se puede crear días después por descuido.
        database.run(`
          UPDATE scheduled_actions SET state = 'omitido', failure = 'sin_confirmar', updated_at = ?
          WHERE state = 'esperando_confirmacion' AND run_at < ?
        `, [timestamp, lateLimitIso]);
        database.run(`
          UPDATE scheduled_actions SET state = 'esperando_confirmacion', updated_at = ?
          WHERE state = 'programado' AND auto_execute = 0 AND run_at <= ?
        `, [timestamp, nowIso]);
        const due = all(`
          SELECT id, collector_id, kind, contact_id, payer_id, amount, currency, concept
          FROM scheduled_actions
          WHERE state = 'programado' AND auto_execute = 1 AND run_at <= ?
          ORDER BY run_at
        `, [nowIso]);
        for (const action of due) {
          database.run("UPDATE scheduled_actions SET state = 'ejecutando', updated_at = ? WHERE id = ?", [
            timestamp,
            action.id,
          ]);
        }
        return due;
      });
    },

    // Ejecución manual de algo que espera la confirmación del cobrador.
    claimScheduledActionForRun(scheduledId, collectorId) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE scheduled_actions SET state = 'ejecutando', updated_at = ?
          WHERE id = ? AND collector_id = ? AND state = 'esperando_confirmacion'
        `, [now(), scheduledId, collectorId]);
        if (database.getRowsModified() !== 1) {
          return null;
        }
        return get(`
          SELECT id, collector_id, kind, contact_id, payer_id, amount, currency, concept
          FROM scheduled_actions WHERE id = ?
        `, [scheduledId]);
      });
    },

    finishScheduledAction(scheduledId, { state, paymentRequestId = null, failure = null }) {
      return enqueueWrite(() => {
        database.run(`
          UPDATE scheduled_actions
          SET state = ?, payment_request_id = ?, failure = ?, updated_at = ?
          WHERE id = ? AND state = 'ejecutando'
        `, [state, paymentRequestId, failure, now(), scheduledId]);
      });
    },

    // Tras un reinicio, lo que quedó a medias no se reintenta: el cobro pudo haberse creado.
    failInterruptedScheduledActions() {
      return enqueueWrite(() => {
        database.run(`
          UPDATE scheduled_actions SET state = 'fallido', failure = 'interrumpido', updated_at = ?
          WHERE state = 'ejecutando'
        `, [now()]);
        return database.getRowsModified();
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
