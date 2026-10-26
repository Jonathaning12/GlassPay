const { randomBytes, randomUUID } = require("node:crypto");
const { logRef, syncTransfer } = require("../services/transfers");
const express = require("express");
const {
  createOpaqueToken,
  createRateLimiter,
  createSalt,
  createSessionSecret,
  hashOpaqueToken,
  hashSessionSecret,
  verifySessionSecret,
} = require("../services/payers");

const MAX_CONTACTS_PER_COLLECTOR = 200;

async function ensureCollectorPayer(database, collector) {
  if (collector.payer_id) {
    return collector.payer_id;
  }
  const sessionSalt = createSalt();
  // Nadie conoce este secreto: el perfil de pagador del cobrador solo se usa con su sesión de cobrador.
  return database.linkCollectorPayer(collector.id, {
    id: randomUUID(),
    displayName: collector.display_name,
    sessionSalt,
    sessionHash: hashSessionSecret(createSessionSecret(), sessionSalt),
  });
}

function toIncomingRequest(paymentRequest) {
  return {
    requestId: paymentRequest.id,
    state: paymentRequest.state,
    amount: paymentRequest.amount,
    currency: paymentRequest.currency,
    concept: paymentRequest.concept,
    approveUrl: paymentRequest.state === "pendiente_de_pagador" ? paymentRequest.approvalUrl : null,
    expiresAt: paymentRequest.expiresAt,
    createdAt: paymentRequest.createdAt,
    fromName: paymentRequest.collectorName,
  };
}

function isResolutionId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{32,128}$/.test(value);
}

function isRequestId(value) {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function isContactId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{16,64}$/.test(value);
}

// Sal y hash ficticios para que un id inexistente cueste lo mismo que uno real.
const DUMMY_SALT = createSalt();
const DUMMY_HASH = hashSessionSecret(createSessionSecret(), DUMMY_SALT);

// El cobrador se autentica con X-Collector-Id y X-Collector-Session (nunca en la URL).
function authenticateCollector(request, database) {
  const collectorId = request.get("X-Collector-Id");
  const secret = request.get("X-Collector-Session");
  const profile = database.getCollectorProfile(collectorId);
  if (!profile) {
    verifySessionSecret(secret, DUMMY_SALT, DUMMY_HASH);
    return null;
  }
  return verifySessionSecret(secret, profile.session_salt, profile.session_hash) ? profile : null;
}

const PAYOUT_EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function normalizePayoutEmail(value) {
  if (typeof value !== "string") {
    return null;
  }
  const email = value.normalize("NFKC").trim().toLowerCase();
  return email.length <= 127 && PAYOUT_EMAIL_PATTERN.test(email) ? email : null;
}

// El dueño solo ve su correo enmascarado; nadie más lo ve.
function maskEmail(email) {
  if (!email) {
    return null;
  }
  const [user, domain] = email.split("@");
  return `${user.slice(0, 1)}***@${domain}`;
}

function isTransferProposalId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{43,128}$/.test(value);
}

function toMovementFromTransfer(transfer) {
  return {
    movementId: transfer.transferId,
    kind: "envio",
    state: transfer.state,
    amount: transfer.amount,
    currency: transfer.currency,
    concept: transfer.note,
    payerName: transfer.recipientName,
    createdAt: transfer.createdAt,
    updatedAt: transfer.updatedAt,
  };
}

function createCollectorsRouter({ database, config, paypal }) {
  const router = express.Router();
  const profileLimiter = createRateLimiter({ maxRequests: 5, windowMs: 60_000 });
  const readLimiter = createRateLimiter({ maxRequests: 60, windowMs: 60_000 });
  const contactWriteLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const contactResolutionLimiter = createRateLimiter({ maxRequests: 30, windowMs: 60_000 });
  const qrLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const transferLimiter = createRateLimiter({ maxRequests: 10, windowMs: 60_000 });
  const settingsLimiter = createRateLimiter({ maxRequests: 10, windowMs: 60_000 });

  // Refresca hasta 3 envíos abiertos por consulta para no abusar de PayPal.
  async function refreshOpenTransfers(collectorId) {
    const intervalMs = (config.payouts?.refreshIntervalSeconds ?? 30) * 1000;
    for (const transferId of database.listOpenTransfers(collectorId, intervalMs).slice(0, 3)) {
      await syncTransfer({ database, paypal, transferId });
    }
  }
  const incomingWriteLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  // Se importa aquí para evitar una dependencia circular con payers.js.
  const { sanitizeDisplayName } = require("./payers");
  const { parseAmount, sanitizeConcept } = require("./payments");

  router.post("/", profileLimiter, async (request, response) => {
    const displayName = sanitizeDisplayName(request.body?.displayName);
    if (!displayName) {
      return response.status(400).json({ error: "displayName is invalid" });
    }

    const collectorId = randomUUID();
    const sessionSecret = createSessionSecret();
    const sessionSalt = createSalt();
    await database.createCollectorProfile({
      id: collectorId,
      displayName,
      sessionSalt,
      sessionHash: hashSessionSecret(sessionSecret, sessionSalt),
    });
    try {
      await ensureCollectorPayer(database, database.getCollectorProfile(collectorId));
    } catch {
      // No bloquea la cuenta: el perfil de pagador se crea después al pedir el QR.
    }

    return response.status(201).json({ collectorId, sessionSecret, displayName });
  });

  router.get("/me", readLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }

    await database.expirePaymentRequests();
    const activity = database.getCollectorSummary(collector.id).map((item) => {
      const balance = database.getAvailableCents(collector.id, item.currency);
      return {
        ...item,
        sentTotal: (balance.sentCents / 100).toFixed(2),
        availableTotal: (balance.availableCents / 100).toFixed(2),
      };
    });
    return response.status(200).json({
      displayName: collector.display_name,
      activity,
      payoutEmail: maskEmail(collector.payout_email),
    });
  });

  router.get("/me/movements", readLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }

    await database.expirePaymentRequests();
    await refreshOpenTransfers(collector.id);
    const movements = [
      ...database.listCollectorMovements(collector.id).map((movement) => ({ ...movement, kind: "cobro" })),
      ...database.listCollectorTransfers(collector.id).map(toMovementFromTransfer),
    ].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 50);
    return response.status(200).json({ movements });
  });

  router.put("/me/payout-email", settingsLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    if (request.body?.email === null) {
      await database.setCollectorPayoutEmail(collector.id, null);
      return response.status(200).json({ payoutEmail: null });
    }
    const email = normalizePayoutEmail(request.body?.email);
    if (!email) {
      return response.status(400).json({ error: "email is invalid" });
    }
    await database.setCollectorPayoutEmail(collector.id, email);
    console.info("Payout email updated", { collectorId: collector.id });
    return response.status(200).json({ payoutEmail: maskEmail(email) });
  });

  router.post("/me/transfers/preview", transferLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const amount = parseAmount(request.body?.amount, config.payments.maxAmount);
    const currency = config.payments.defaultCurrency;
    const note = request.body?.note === undefined || request.body?.note === ""
      ? "Envío GlassPay"
      : sanitizeConcept(request.body?.note);
    const contact = isContactId(request.body?.contactId)
      ? database.getCollectorContact(request.body.contactId, collector.id)
      : null;
    if (!amount || !note) {
      return response.status(400).json({ error: "transfer is invalid" });
    }
    if (!contact) {
      return response.status(404).json({ error: "contact not found" });
    }
    if (!database.getPayoutEmailForPayer(contact.payer_id)) {
      return response.status(409).json({ error: "recipient cannot receive" });
    }
    const { availableCents } = database.getAvailableCents(collector.id, currency);
    if (Math.round(Number.parseFloat(amount) * 100) > availableCents) {
      return response.status(409).json({ error: "insufficient balance" });
    }

    const transferProposalId = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + config.agent.proposalTtlSeconds * 1000).toISOString();
    await database.createTransferProposal({
      id: transferProposalId,
      senderCollectorId: collector.id,
      recipientPayerId: contact.payer_id,
      amount,
      currency,
      note,
      expiresAt,
    });
    return response.status(201).json({
      transferProposalId,
      amount,
      currency,
      note,
      recipientName: contact.display_name,
      expiresAt,
    });
  });

  router.post("/me/transfers/confirm", transferLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const proposalId = request.body?.transferProposalId;
    const result = isTransferProposalId(proposalId)
      ? await database.confirmTransferProposal(proposalId, collector.id)
      : null;
    if (!result) {
      return response.status(404).json({ error: "transfer not found" });
    }
    if (result.insufficient) {
      return response.status(409).json({ error: "insufficient balance" });
    }
    if (result.cannotReceive) {
      return response.status(409).json({ error: "recipient cannot receive" });
    }

    console.info("Transfer confirmed", {
      collectorId: collector.id,
      transfer: logRef(proposalId),
      amount: result.transfer.amount,
    });
    await syncTransfer({ database, paypal, transferId: proposalId });
    const [transfer] = database.listCollectorTransfers(collector.id)
      .filter((item) => item.transferId === proposalId);
    return response.status(201).json({
      transferId: transfer.transferId,
      state: transfer.state,
      amount: transfer.amount,
      currency: transfer.currency,
      recipientName: transfer.recipientName,
    });
  });

  router.post("/me/qr", qrLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const payerId = await ensureCollectorPayer(database, collector);
    const token = createOpaqueToken(32);
    const expiresAt = new Date(Date.now() + config.payers.qrTokenTtlSeconds * 1000).toISOString();
    await database.createQrToken({ hash: hashOpaqueToken(token), payerId, expiresAt });
    return response.status(201).json({ token: `glasspay:${token}`, expiresAt });
  });

  router.get("/me/incoming-requests", readLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    if (!collector.payer_id) {
      return response.status(200).json({ requests: [] });
    }
    await database.expirePaymentRequests();
    return response.status(200).json({
      requests: database.listPayerRequests(collector.payer_id).map(toIncomingRequest),
    });
  });

  router.post("/me/incoming-requests/:requestId/reject", incomingWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    await database.expirePaymentRequests();
    const rejected = collector.payer_id
      && isRequestId(request.params.requestId)
      && await database.rejectPaymentRequest(request.params.requestId, collector.payer_id);
    if (!rejected) {
      return response.status(404).json({ error: "payment request not found" });
    }
    return response.status(200).json({ requestId: request.params.requestId, state: "rechazada" });
  });

  router.get("/me/contacts", readLimiter, (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    return response.status(200).json({ contacts: database.listCollectorContacts(collector.id) });
  });

  router.post("/me/contacts", contactWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const resolutionId = request.body?.resolutionId;
    const resolution = isResolutionId(resolutionId)
      ? database.findCollectorResolution(hashOpaqueToken(resolutionId), collector.id)
      : null;
    if (!resolution) {
      return response.status(404).json({ error: "contact cannot be saved" });
    }
    if (collector.payer_id && resolution.payer_id === collector.payer_id) {
      return response.status(409).json({ error: "cannot add yourself" });
    }

    const result = await database.saveCollectorContact({
      id: createOpaqueToken(16),
      collectorId: collector.id,
      payerId: resolution.payer_id,
    }, MAX_CONTACTS_PER_COLLECTOR);
    if (!result) {
      return response.status(404).json({ error: "contact cannot be saved" });
    }
    if (result.limitReached) {
      return response.status(409).json({ error: "contact limit reached" });
    }
    return response.status(result.created ? 201 : 200).json(result.contact);
  });

  router.delete("/me/contacts/:contactId", contactWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const deleted = isContactId(request.params.contactId)
      && await database.deleteCollectorContact(request.params.contactId, collector.id);
    if (!deleted) {
      return response.status(404).json({ error: "contact not found" });
    }
    return response.status(204).end();
  });

  // Cobrar a un contacto: crea una resolución nueva de vida corta, ligada al
  // cobrador, equivalente a escanear su QR. El resto del flujo no cambia.
  router.post("/me/contacts/:contactId/resolution", contactResolutionLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const contact = isContactId(request.params.contactId)
      ? database.getCollectorContact(request.params.contactId, collector.id)
      : null;
    if (!contact) {
      return response.status(404).json({ error: "contact not found" });
    }

    const resolutionId = createOpaqueToken(24);
    const expiresAt = new Date(Date.now() + config.payers.resolutionTtlSeconds * 1000).toISOString();
    await database.createPayerResolution({
      hash: hashOpaqueToken(resolutionId),
      payerId: contact.payer_id,
      collectorId: collector.id,
      expiresAt,
    });
    return response.status(201).json({
      displayName: contact.display_name,
      resolutionId,
      expiresAt,
      contactId: contact.id,
    });
  });

  return router;
}

module.exports = { authenticateCollector, createCollectorsRouter };
