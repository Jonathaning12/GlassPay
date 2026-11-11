// Textos que el servidor redacta para el cobrador (asistente y etiquetas). Se escriben en español y,
// si el cliente pide inglés (header X-GlassPay-Language: en), se traducen con esta tabla. Las
// variables van entre llaves: t("en", "Hola, {name}", { name: "Ana" }) → "Hi, Ana".
const ENGLISH = {
  // Asistente: respuestas generales.
  "Nadie te debe en este momento.": "Nobody owes you right now.",
  "{name} te debe {total}.": "{name} owes you {total}.",
  "Te deben {total} entre {count} personas.": "{count} people owe you {total} in total.",
  "No tienes cobros pagados ni envíos completados {when}.": "You have no paid charges or completed transfers {when}.",
  "{When} cobraste {paid} y enviaste {sent}. Tu balance es {net}.": "{When} you collected {paid} and sent {sent}. Your balance is {net}.",
  "esta semana": "this week",
  "este mes": "this month",
  "este año": "this year",
  "Aún no tienes nada programado.": "You don't have anything scheduled yet.",
  "Tienes 1 programación activa.": "You have 1 active scheduled item.",
  "Tienes {count} programaciones activas.": "You have {count} active scheduled items.",
  "No entendí tu mensaje. Puedo decirte cuánto cobraste o enviaste y quién te debe.":
    "I didn't understand your message. I can tell you how much you collected or sent and who owes you.",
  "Muy pronto podré {what}. Por ahora puedo decirte cuánto cobraste y quién te debe.":
    "Soon I'll be able to {what}. For now I can tell you how much you collected and who owes you.",
  "¿Cuánto cobré esta semana?": "How much did I collect this week?",
  "¿Quién me debe?": "Who owes me?",
  "¿Qué tengo programado?": "What do I have scheduled?",
  "¿Qué puedes hacer?": "What can you do?",
  "Recuérdale a {name} que me pague {total}": "Remind {name} to pay me {total}",
  // Propuestas.
  "Revisa el cobro programado y confírmalo.": "Review the scheduled charge and confirm it.",
  "Revisa el cobro recurrente y confírmalo.": "Review the recurring charge and confirm it.",
  "Así quedaría la cuenta dividida. Revisa y confirma.": "Here's how the bill would be split. Review and confirm.",
  "Este es el recordatorio que le llegaría. ¿Lo envío?": "This is the reminder they'd get. Should I send it?",
  "Revisa el pago programado. Para confirmarlo te pediré tu huella o el bloqueo del teléfono.":
    "Review the scheduled payment. To confirm it I'll ask for Face ID or your phone lock.",
  "¿Cancelo esta programación?": "Should I cancel this scheduled item?",
  "{name} aún no registra su correo para recibir envíos. Pídele que lo haga en su app.":
    "{name} hasn't registered an email to receive transfers yet. Ask them to do it in their app.",
  // Aclaraciones al preparar propuestas.
  "Esa hora ya pasó o es muy pronto. Dime una hora al menos un minuto en el futuro.":
    "That time already passed or is too soon. Tell me a time at least one minute in the future.",
  "Solo puedo programar hasta {days} días en el futuro.": "I can only schedule up to {days} days ahead.",
  "¿Cuánto le cobro?": "How much should I charge?",
  "¿Cuánto le pago?": "How much should I pay?",
  "¿Cuánto fue el total?": "What was the total?",
  "Ese monto no es válido.": "That amount isn't valid.",
  "Con ese total alguna parte queda fuera del rango permitido.": "With that total, some share is outside the allowed range.",
  "{what} Escribe el monto en el mensaje (máximo ${max} por persona).":
    "{what} Include the amount in your message (maximum ${max} per person).",
  "Tienes varios contactos con ese nombre ({names}). Escribe su nombre completo.":
    "You have several contacts with that name ({names}). Write their full name.",
  "No encontré a esa persona en tus contactos. Agrégala primero escaneando su QR en Contactos.":
    "I couldn't find that person in your contacts. Add them first by scanning their QR in Contacts.",
  "¿Para cuándo lo programo? Por ejemplo: «mañana a las 6» o «en 2 horas».":
    "When should I schedule it? For example: “tomorrow at 6 pm” or “in 2 hours”.",
  "¿Para cuándo programo el pago? Por ejemplo: «mañana a las 6».":
    "When should I schedule the payment? For example: “tomorrow at 6 pm”.",
  "¿Cada cuánto? Dime «cada semana» o «cada mes».": "How often? Say “every week” or “every month”.",
  "Un cobro recurrente puede repetirse de 2 a {max} veces.": "A recurring charge can repeat 2 to {max} times.",
  "¿Cuándo es el primer cobro? Por ejemplo: «cada mes, empezando el 1 de noviembre a las 10».":
    "When is the first charge? For example: “every month starting on the 1st at 10 am”.",
  "No encontré a «{name}» en tus contactos.": "I couldn't find “{name}” in your contacts.",
  "¿Entre quiénes la divido? Nombra a tus contactos, por ejemplo: «entre Ana, Luis y yo».":
    "Who should I split it between? Name your contacts, for example: “between Ana, Luis and me”.",
  "Puedo dividir entre {max} contactos como máximo.": "I can split between {max} contacts at most.",
  "Para dividir necesito al menos dos personas. Si es solo una, pídeme un cobro normal.":
    "To split I need at least two people. If it's just one, ask me for a regular charge.",
  "No entendí para cuándo. Por ejemplo: «mañana a las 6».": "I didn't understand when. For example: “tomorrow at 6 pm”.",
  "No entendí para cuándo. Por ejemplo: «mañana a las 10».": "I didn't understand when. For example: “tomorrow at 10 am”.",
  "No tienes nada programado para cancelar.": "You don't have anything scheduled to cancel.",
  "Tienes varias programaciones que podrían coincidir. Elige cuál cancelar en la lista.":
    "Several scheduled items could match. Pick the one to cancel from the list.",
  // Conceptos por defecto.
  "Cobro programado": "Scheduled charge",
  "Envío GlassPay": "GlassPay transfer",
  "Cobro recurrente": "Recurring charge",
  "Cuenta dividida": "Split bill",
};

function t(language, spanish, vars = {}) {
  const template = language === "en" ? (ENGLISH[spanish] ?? spanish) : spanish;
  return template.replace(/\{(\w+)\}/g, (match, key) => (key in vars ? String(vars[key]) : match));
}

module.exports = { ENGLISH, t };
