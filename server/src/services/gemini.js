// Cliente mínimo de Gemini (generateContent con salida JSON y esquema) compartido por los servicios de IA.
// La clave va en el header x-goog-api-key, nunca en la URL; los logs no incluyen texto ni respuestas.

class GeminiApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

/** Tope diario de llamadas compartido entre el cobro por voz y el asistente. */
function createDailyBudget(limit) {
  let calls = 0;
  let dayKey = new Date().toISOString().slice(0, 10);
  return {
    tryConsume() {
      const currentKey = new Date().toISOString().slice(0, 10);
      if (currentKey !== dayKey) {
        dayKey = currentKey;
        calls = 0;
      }
      if (calls >= limit) {
        return false;
      }
      calls += 1;
      return true;
    },
  };
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/** Devuelve el objeto JSON generado; lanza GeminiApiError, AbortError o SyntaxError. */
async function requestGeminiJson(config, { prompt, schema, maxOutputTokens, label }) {
  const request = config.fetch || fetch;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.timeoutMs);
  const startedAt = Date.now();
  let status = null;
  let reason = "success";
  try {
    const response = await request(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.model)}:generateContent`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": config.apiKey,
        },
        body: JSON.stringify({
          contents: [{ parts: [{ text: prompt }] }],
          generationConfig: {
            temperature: 0,
            maxOutputTokens,
            responseMimeType: "application/json",
            responseSchema: schema,
          },
        }),
        signal: controller.signal,
      },
    );
    status = response.status;
    if (!response.ok) {
      reason = `http_${response.status}`;
      throw new GeminiApiError("Gemini request failed", response.status);
    }
    const body = await response.json();
    return JSON.parse(body.candidates?.[0]?.content?.parts?.[0]?.text);
  } catch (error) {
    if (error.name === "AbortError") {
      reason = "timeout";
    } else if (reason === "success") {
      reason = error instanceof SyntaxError ? "invalid_response" : "provider_error";
    }
    throw error;
  } finally {
    clearTimeout(timeout);
    const telemetry = { model: config.model, label, status, elapsedMs: Date.now() - startedAt, reason };
    console.info("Gemini call", telemetry);
    config.onProviderCall?.(telemetry);
  }
}

/**
 * Llama a Gemini con un reintento corto ante 429/503/timeout y, si falla o no hay cupo,
 * usa el respaldo determinista. `call` debe validar y normalizar la respuesta.
 */
async function withGeminiFallback(config, budget, call, fallback) {
  if (config.provider !== "gemini" || !config.apiKey || !budget.tryConsume()) {
    return fallback();
  }
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      return await call();
    } catch (error) {
      if ((error.status === 429 || error.status === 503 || error.name === "AbortError") && attempt === 0) {
        await wait(300);
        continue;
      }
      const fallbackReason = error.name === "AbortError"
        ? "timeout"
        : error.status
          ? `http_${error.status}`
          : "provider_error";
      console.warn("Gemini unavailable; using deterministic parser", { model: config.model, reason: fallbackReason });
      config.onProviderFallback?.({ model: config.model, status: error.status || null, reason: fallbackReason });
      return fallback();
    }
  }
  return fallback();
}

module.exports = { GeminiApiError, createDailyBudget, requestGeminiJson, withGeminiFallback };
