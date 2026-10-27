// Interpretación y validación de cobros programados desde el chat. Todo cálculo de fecha se hace
// en hora de México; la IA solo propone campos y aquí se comprueban contra el texto del usuario.
const { NUMBER_WORDS, parseSpanishNumber } = require("./agent");

// Ciudad de México sin horario de verano desde 2022: UTC−6 fijo (mismo criterio que stats.js).
const MEXICO_OFFSET_MS = -6 * 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const DAY_MS = 24 * 60 * MINUTE_MS;
const DEFAULT_HOUR = 10; // Si solo dicen el día ("mañana"), se programa a las 10:00.
const WEEKDAYS = ["domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado"];
const MONTHS = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];

// Para comparar nombres: sin acentos ni signos ("Ana," debe coincidir con "Ana").
function foldWords(text) {
  return fold(text).replace(/[^\p{L}\p{N}]+/gu, " ").trim();
}

const OCCURRENCES_PATTERN = /\b(?:por|durante)\s+\d{1,2}\s+(?:veces|semanas|meses)\b|\b\d{1,2}\s+veces\b/;

function fold(text) {
  return text.normalize("NFKC").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/[¿?¡!]/g, " ").replace(/\s+/g, " ").trim();
}

function localParts(date) {
  const local = new Date(date.getTime() + MEXICO_OFFSET_MS);
  return {
    year: local.getUTCFullYear(),
    month: local.getUTCMonth(),
    day: local.getUTCDate(),
    weekday: local.getUTCDay(),
    hour: local.getUTCHours(),
    minute: local.getUTCMinutes(),
  };
}

function fromLocal(year, month, day, hour, minute) {
  return new Date(Date.UTC(year, month, day, hour, minute) - MEXICO_OFFSET_MS);
}

/** "Hoy es jueves 29 de octubre de 2026, 10:05" para que la IA resuelva fechas relativas. */
function describeNow(now) {
  const parts = localParts(now);
  const pad = (value) => String(value).padStart(2, "0");
  return `${WEEKDAYS[parts.weekday]} ${parts.year}-${pad(parts.month + 1)}-${pad(parts.day)} ${pad(parts.hour)}:${pad(parts.minute)}`;
}

/** Etiqueta en español para la tarjeta: "vie 30 oct, 18:00". */
function formatRunAt(date) {
  const parts = localParts(date);
  const day = WEEKDAYS[parts.weekday].slice(0, 3).replace("mie", "mié").replace("sab", "sáb");
  return `${day} ${parts.day} ${MONTHS[parts.month]}, ${String(parts.hour).padStart(2, "0")}:${String(parts.minute).padStart(2, "0")}`;
}

/**
 * Calcula el instante a partir de { whenType, minutesFromNow, date, time }.
 * Devuelve null si los datos no forman una fecha válida.
 */
function computeRunAt(when, now) {
  if (!when || typeof when !== "object") return null;
  if (when.whenType === "relative") {
    const minutes = Number(when.minutesFromNow);
    if (!Number.isFinite(minutes) || minutes <= 0) return null;
    return new Date(now.getTime() + Math.round(minutes) * MINUTE_MS);
  }
  if (when.whenType !== "absolute") return null;
  const timeMatch = typeof when.time === "string" ? when.time.match(/^(\d{1,2}):(\d{2})$/) : null;
  const dateMatch = typeof when.date === "string" ? when.date.match(/^(\d{4})-(\d{2})-(\d{2})$/) : null;
  if (when.time && !timeMatch) return null;
  if (when.date && !dateMatch) return null;
  if (!timeMatch && !dateMatch) return null;
  const hour = timeMatch ? Number(timeMatch[1]) : DEFAULT_HOUR;
  const minute = timeMatch ? Number(timeMatch[2]) : 0;
  if (hour > 23 || minute > 59) return null;
  if (dateMatch) {
    const [year, month, day] = [Number(dateMatch[1]), Number(dateMatch[2]) - 1, Number(dateMatch[3])];
    const candidate = fromLocal(year, month, day, hour, minute);
    // Rechaza fechas imposibles como 2026-02-31 (Date las desbordaría al mes siguiente).
    const check = localParts(candidate);
    if (check.year !== year || check.month !== month || check.day !== day) return null;
    return candidate;
  }
  // Solo hora: hoy si aún no pasa; si ya pasó, mañana.
  const today = localParts(now);
  const candidate = fromLocal(today.year, today.month, today.day, hour, minute);
  return candidate.getTime() > now.getTime() ? candidate : new Date(candidate.getTime() + DAY_MS);
}

const RELATIVE_PATTERN = /\b(?:en|dentro de)\s+(\d+|un|una|media|dos|tres|cuatro|cinco|diez|quince|veinte|treinta)\s+(minutos?|horas?|dias?)\b/;
const TIME_PATTERN = /\ba\s+la(?:s)?\s+(\d{1,2}|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)(?::(\d{2})|\s+y\s+media)?(?:\s*(am|pm|de la manana|de la tarde|de la noche))?/;
const MONTH_NAMES = [
  "enero", "febrero", "marzo", "abril", "mayo", "junio",
  "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
];
const DAY_OF_MONTH_PATTERN = new RegExp(`\\b(?:el|del|a partir del)\\s+(\\d{1,2})(?:\\s+de\\s+(${MONTH_NAMES.join("|")}))?\\b`);
const SMALL_NUMBERS = {
  un: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10,
  once: 11, doce: 12, quince: 15, veinte: 20, treinta: 30, media: 0.5,
};

function smallNumber(token) {
  return /^\d+$/.test(token) ? Number(token) : SMALL_NUMBERS[token];
}

/** Respaldo sin IA: entiende "en 2 horas", "mañana a las 6", "el viernes a las 18:30", "hoy a las 5 pm". */
function parseWhenFallback(text, now) {
  const folded = fold(text);
  const relative = folded.match(RELATIVE_PATTERN);
  if (relative) {
    const quantity = smallNumber(relative[1]);
    const unit = relative[2].startsWith("min") ? 1 : relative[2].startsWith("hora") ? 60 : 24 * 60;
    return { whenType: "relative", minutesFromNow: quantity * unit, date: "", time: "", matched: relative[0] };
  }
  const today = localParts(now);
  let dayOffset = null;
  let dayText = "";
  if (/\bpasado manana\b/.test(folded)) {
    dayOffset = 2;
    dayText = "pasado manana";
  } else if (/\bmanana\b/.test(folded.replace(/de la manana/g, ""))) {
    dayOffset = 1;
    dayText = "manana";
  } else if (/\bhoy\b/.test(folded)) {
    dayOffset = 0;
    dayText = "hoy";
  } else {
    const weekday = WEEKDAYS.findIndex((name) => new RegExp(`\\b(?:el|este|el proximo|cada)\\s+${name}\\b`).test(folded));
    const dayOfMonth = folded.match(DAY_OF_MONTH_PATTERN);
    if (weekday >= 0) {
      dayOffset = (weekday - today.weekday + 7) % 7 || 7;
      dayText = WEEKDAYS[weekday];
    } else if (dayOfMonth) {
      const day = Number(dayOfMonth[1]);
      const namedMonth = dayOfMonth[2] ? MONTH_NAMES.indexOf(dayOfMonth[2]) : -1;
      // Sin mes: este mes si el día no ha pasado; si ya pasó, el próximo.
      let month = namedMonth >= 0 ? namedMonth : today.month;
      let year = today.year;
      if (namedMonth < 0 && day < today.day) month += 1;
      if (namedMonth >= 0 && (namedMonth < today.month || (namedMonth === today.month && day < today.day))) year += 1;
      const target = new Date(Date.UTC(year, month, day));
      if (target.getUTCDate() !== day) return null;
      dayOffset = Math.round((target.getTime() - Date.UTC(today.year, today.month, today.day)) / DAY_MS);
      dayText = dayOfMonth[0];
    }
  }
  const timeMatch = folded.match(TIME_PATTERN);
  let time = "";
  if (timeMatch) {
    let hour = smallNumber(timeMatch[1]);
    const minute = timeMatch[2] ? Number(timeMatch[2]) : /y media/.test(timeMatch[0]) ? 30 : 0;
    const marker = timeMatch[3] || "";
    if ((marker === "pm" || marker.includes("tarde") || marker.includes("noche")) && hour < 12) hour += 12;
    else if ((marker === "am" || marker.includes("manana")) && hour === 12) hour = 0;
    // Sin "am/pm": de 1 a 7 se entiende de la tarde (nadie cobra a las 3 de la madrugada).
    else if (!marker && hour >= 1 && hour <= 7) hour += 12;
    time = `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
  }
  if (dayOffset === null && !time) return null;
  let date = "";
  if (dayOffset !== null) {
    const target = new Date(Date.UTC(today.year, today.month, today.day + dayOffset));
    date = `${target.getUTCFullYear()}-${String(target.getUTCMonth() + 1).padStart(2, "0")}-${String(target.getUTCDate()).padStart(2, "0")}`;
  }
  return { whenType: "absolute", minutesFromNow: 0, date, time, matched: [dayText, timeMatch?.[0] || ""].join(" ") };
}

const RECURRENCE_PATTERN = /\bcada\s+(semana|mes|lunes|martes|miercoles|jueves|viernes|sabado|domingo)\b|\b(semanal|mensual)(?:mente)?\b/;

/** "cada mes" → monthly; "cada semana" o "cada viernes" → weekly; si no, null. */
function detectRecurrence(text) {
  const match = fold(text).match(RECURRENCE_PATTERN);
  if (!match) return null;
  const word = match[1] || match[2];
  return word === "mes" || word === "mensual" ? "monthly" : "weekly";
}

/** Siguiente fecha de una serie; el día 31 inexistente pasa al último día del mes. */
function nextOccurrence(row) {
  const current = new Date(row.run_at);
  if (row.recurrence === "weekly") {
    return new Date(current.getTime() + 7 * DAY_MS).toISOString();
  }
  if (row.recurrence !== "monthly") return null;
  const parts = localParts(current);
  const anchor = row.anchor_day || parts.day;
  const daysInNext = new Date(Date.UTC(parts.year, parts.month + 2, 0)).getUTCDate();
  return fromLocal(parts.year, parts.month + 1, Math.min(anchor, daysInNext), parts.hour, parts.minute).toISOString();
}

function localDay(date) {
  return localParts(date).day;
}

/** Todos los números que aparecen en el texto (cifras y palabras), para validar el monto de la IA. */
function numbersInText(text) {
  const folded = fold(text);
  const numbers = new Set();
  for (const match of folded.matchAll(/(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)/g)) {
    const raw = /,\d{3}/.test(match[1]) ? match[1].replace(/,/g, "") : match[1].replace(",", ".");
    numbers.add(Number.parseFloat(raw));
  }
  const tokens = folded.split(" ");
  let run = [];
  const flush = () => {
    const words = run.filter((token) => token !== "y" && token !== "con");
    if (words.length) numbers.add(parseSpanishNumber(run.filter((token) => token !== "con")));
    run = [];
  };
  for (const token of tokens) {
    if (NUMBER_WORDS.has(token) && token !== "con") run.push(token);
    else flush();
  }
  flush();
  return numbers;
}

/** Respaldo sin IA para el monto: el primer número que no forme parte de la fecha u hora. */
function amountFallback(text) {
  // Se quitan las expresiones de fecha completas (no sus palabras sueltas: "6" no debe borrar el 6 de "600").
  const folded = fold(text)
    .replace(new RegExp(RELATIVE_PATTERN.source, "g"), " ")
    .replace(new RegExp(TIME_PATTERN.source, "g"), " ")
    .replace(new RegExp(DAY_OF_MONTH_PATTERN.source, "g"), " ")
    .replace(new RegExp(OCCURRENCES_PATTERN.source, "g"), " ");
  const numeric = folded.match(/\$?\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)/);
  if (numeric) {
    const raw = /,\d{3}/.test(numeric[1]) ? numeric[1].replace(/,/g, "") : numeric[1].replace(",", ".");
    return Number.parseFloat(raw);
  }
  const values = [...numbersInText(folded)];
  return values.length ? values[0] : null;
}

/** Concepto: lo que sigue a "por"/"para" hasta la fecha u hora. */
function conceptFallback(text) {
  const match = text.replace(new RegExp(OCCURRENCES_PATTERN.source, "gi"), " ").match(/\b(?:por|para)\s+(.+?)(?=\s+(?:hoy|ma[ñn]ana|pasado|el\s+\w+|este\s+\w+|a\s+las?\s|en\s+\d|dentro\s+de|cada\s|a\s+partir|empezando|entre\s)|$)/i);
  return match ? match[1].trim() : "";
}

/**
 * Busca el contacto mencionado. El nombre debe aparecer en el texto del usuario (la IA no puede
 * inventarlo) y coincidir con un solo contacto guardado.
 */
function matchContact(contactName, text, contacts) {
  const foldedText = ` ${foldWords(text)} `;
  const mentioned = (name) => foldedText.includes(` ${foldWords(name)} `);
  let candidates = [];
  const wanted = foldWords(contactName || "");
  if (wanted && mentioned(wanted.split(" ")[0])) {
    candidates = contacts.filter((contact) => foldWords(contact.displayName) === wanted);
    if (candidates.length === 0) {
      candidates = contacts.filter((contact) => foldWords(contact.displayName).split(" ")[0] === wanted.split(" ")[0]);
    }
  }
  if (candidates.length === 0 && !wanted) {
    candidates = contacts.filter((contact) => mentioned(contact.displayName) || mentioned(foldWords(contact.displayName).split(" ")[0]));
  }
  if (candidates.length === 1) return { status: "match", contact: candidates[0] };
  if (candidates.length > 1) return { status: "ambiguous", candidates };
  return { status: "none" };
}

module.exports = {
  amountFallback,
  computeRunAt,
  conceptFallback,
  detectRecurrence,
  foldWords,
  localDay,
  nextOccurrence,
  describeNow,
  formatRunAt,
  matchContact,
  numbersInText,
  parseWhenFallback,
};
