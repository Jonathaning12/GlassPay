// Idioma de la respuesta que pide el cliente. Solo "es" (por defecto) o "en"; cualquier otro valor
// cae en español para que los clientes existentes (Android, payer-web) no cambien.
const SUPPORTED_LANGUAGES = ["es", "en"];

function normalizeLanguage(value) {
  const language = typeof value === "string" ? value.trim().toLowerCase().slice(0, 2) : "";
  return SUPPORTED_LANGUAGES.includes(language) ? language : "es";
}

/** Lee el header X-GlassPay-Language o, si falta, el campo `language` del cuerpo. */
function requestLanguage(request) {
  const header = typeof request.get === "function" ? request.get("X-GlassPay-Language") : undefined;
  return normalizeLanguage(header || request.body?.language);
}

/** Elige el texto según el idioma: pick("en", { es: "Hola", en: "Hi" }) → "Hi". */
function pick(language, texts) {
  return language === "en" ? texts.en : texts.es;
}

module.exports = { SUPPORTED_LANGUAGES, normalizeLanguage, pick, requestLanguage };
