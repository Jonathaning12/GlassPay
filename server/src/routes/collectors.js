const { randomUUID } = require("node:crypto");
const express = require("express");
const {
  createRateLimiter,
  createSalt,
  createSessionSecret,
  hashSessionSecret,
  verifySessionSecret,
} = require("../services/payers");

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

function createCollectorsRouter({ database }) {
  const router = express.Router();
  const profileLimiter = createRateLimiter({ maxRequests: 5, windowMs: 60_000 });
  const readLimiter = createRateLimiter({ maxRequests: 60, windowMs: 60_000 });
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

  return router;
}

module.exports = { authenticateCollector, createCollectorsRouter };
