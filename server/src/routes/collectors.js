const { randomUUID } = require("node:crypto");
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

function createCollectorsRouter({ database, config }) {
  const router = express.Router();
  const profileLimiter = createRateLimiter({ maxRequests: 5, windowMs: 60_000 });
  const readLimiter = createRateLimiter({ maxRequests: 60, windowMs: 60_000 });
  const contactWriteLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const contactResolutionLimiter = createRateLimiter({ maxRequests: 30, windowMs: 60_000 });
  const qrLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const incomingWriteLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  // Se importa aquí para evitar una dependencia circular con payers.js.
  const { sanitizeDisplayName } = require("./payers");

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
    return response.status(200).json({
      displayName: collector.display_name,
      activity: database.getCollectorSummary(collector.id),
    });
  });

  router.get("/me/movements", readLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }

    await database.expirePaymentRequests();
    return response.status(200).json({
      movements: database.listCollectorMovements(collector.id),
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
