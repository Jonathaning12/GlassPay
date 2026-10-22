const { createHash, randomBytes, scryptSync, timingSafeEqual } = require("node:crypto");

function createSessionSecret() {
  return randomBytes(32).toString("base64url");
}

function createSalt() {
  return randomBytes(16).toString("base64");
}

function hashSessionSecret(secret, salt) {
  return scryptSync(secret, Buffer.from(salt, "base64"), 32).toString("base64");
}

function verifySessionSecret(secret, salt, expectedHash) {
  if (typeof secret !== "string" || !salt || !expectedHash) {
    return false;
  }
  const actualHash = Buffer.from(hashSessionSecret(secret, salt), "base64");
  const expected = Buffer.from(expectedHash, "base64");
  return actualHash.length === expected.length && timingSafeEqual(actualHash, expected);
}

function createOpaqueToken(bytes = 32) {
  return randomBytes(bytes).toString("base64url");
}

function hashOpaqueToken(token) {
  return createHash("sha256").update(token).digest("hex");
}

function createRateLimiter({ maxRequests, windowMs, key = (request) => request.ip || "unknown" }) {
  const buckets = new Map();

  return (request, response, next) => {
    const now = Date.now();
    const bucketKey = key(request);
    const bucket = buckets.get(bucketKey);
    if (!bucket || now - bucket.startedAt >= windowMs) {
      buckets.set(bucketKey, { startedAt: now, count: 1 });
      return next();
    }
    if (bucket.count >= maxRequests) {
      return response.status(429).json({ error: "too many requests" });
    }
    bucket.count += 1;
    return next();
  };
}

module.exports = {
  createOpaqueToken,
  createRateLimiter,
  createSalt,
  createSessionSecret,
  hashOpaqueToken,
  hashSessionSecret,
  verifySessionSecret,
};
