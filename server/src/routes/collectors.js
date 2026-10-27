const { randomBytes, randomUUID } = require("node:crypto");
const { logRef, syncTransfer } = require("../services/transfers");
const { computeStats, isPeriod } = require("../services/stats");
const { SUGGESTIONS, buildAssistantReply } = require("../services/assistant-replies");
const {
  prepareCancel,
  prepareRecurringCharge,
  prepareReminder,
  prepareScheduleCharge,
  prepareSchedulePayment,
  prepareSplitBill,
} = require("../services/assistant-actions");
const { sendReminder, toVisibleReminder } = require("../services/reminders");
const { formatRunAt } = require("../services/scheduling");
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

const MAX_ASSISTANT_MESSAGE_LENGTH = 500;

function sanitizeAssistantText(value) {
  if (typeof value !== "string") {
    return null;
  }
  const text = value.normalize("NFKC").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim();
  return text.length > 0 && text.length <= MAX_ASSISTANT_MESSAGE_LENGTH ? text : null;
}

const ACTIVE_SCHEDULED_STATES = new Set(["programado", "esperando_confirmacion", "ejecutando"]);

function isProposalId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{43,128}$/.test(value);
}

function isScheduledId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{24,64}$/.test(value);
}

function toScheduledItem(item) {
  return {
    scheduledId: item.scheduledId,
    kind: item.kind,
    contactName: item.contactName,
    amount: item.amount,
    currency: item.currency,
    concept: item.concept,
    runAt: item.runAt,
    runAtLabel: formatRunAt(new Date(item.runAt)),
    autoExecute: item.autoExecute,
    state: item.state,
    failure: item.failure,
    recurrence: item.recurrence,
    occurrencesTotal: item.occurrencesTotal,
    occurrencesDone: item.occurrencesDone,
  };
}

const PROPOSAL_INTRO = {
  schedule_charge: "Revisa el cobro programado y confírmalo.",
  recurring_charge: "Revisa el cobro recurrente y confírmalo.",
  split_bill: "Así quedaría la cuenta dividida. Revisa y confirma.",
  reminder: "Este es el recordatorio que le llegaría. ¿Lo envío?",
  schedule_payment: "Revisa el pago programado. Para confirmarlo te pediré tu huella o el bloqueo del teléfono.",
  cancel_scheduled: "¿Cancelo esta programación?",
};
const PREPARERS = {
  schedule_charge: prepareScheduleCharge,
  recurring_charge: prepareRecurringCharge,
  split_bill: prepareSplitBill,
  reminder: prepareReminder,
  schedule_payment: prepareSchedulePayment,
};

const SCHEDULED_KIND = { reminder: "reminder", schedule_payment: "payment" };

function isReminderId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{24,64}$/.test(value);
}

// Sugiere recordar a los deudores que están en contactos (la sugerencia también pasa por confirmación).
function debtorSuggestions(reply, contacts) {
  if (reply.kind !== "debtors") return SUGGESTIONS;
  const names = new Set(contacts.map((contact) => contact.displayName));
  const reminders = reply.debtors
    .filter((debtor) => names.has(debtor.name))
    .slice(0, 3)
    .map((debtor) => `Recuérdale a ${debtor.name} que me pague ${Number(debtor.total).toFixed(2)}`);
  return [...reminders, ...SUGGESTIONS].slice(0, 5);
}

function activeScheduled(items) {
  return items.filter((item) => ACTIVE_SCHEDULED_STATES.has(item.state)).map(toScheduledItem);
}

function createCollectorsRouter({ database, config, paypal, assistant, scheduler }) {
  const router = express.Router();
  const profileLimiter = createRateLimiter({ maxRequests: 5, windowMs: 60_000 });
  const readLimiter = createRateLimiter({ maxRequests: 60, windowMs: 60_000 });
  const contactWriteLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const contactResolutionLimiter = createRateLimiter({ maxRequests: 30, windowMs: 60_000 });
  const qrLimiter = createRateLimiter({ maxRequests: 20, windowMs: 60_000 });
  const transferLimiter = createRateLimiter({ maxRequests: 10, windowMs: 60_000 });
  const settingsLimiter = createRateLimiter({ maxRequests: 10, windowMs: 60_000 });
  const scheduledWriteLimiter = createRateLimiter({
    maxRequests: 20,
    windowMs: 60_000,
    key: (request) => request.get("X-Collector-Id") || request.ip || "unknown",
  });
  // Por cobrador (y por IP si no hay id) para que un usuario no agote el cupo diario de IA de otros.
  const assistantLimiter = createRateLimiter({
    maxRequests: 15,
    windowMs: 60_000,
    key: (request) => request.get("X-Collector-Id") || request.ip || "unknown",
  });

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

  router.get("/me/stats", readLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const period = request.query.period ?? "week";
    if (!isPeriod(period)) {
      return response.status(400).json({ error: "invalid period" });
    }

    await database.expirePaymentRequests();
    await refreshOpenTransfers(collector.id);
    return response.status(200).json(
      computeStats(database, collector.id, period, { currency: config.payments.defaultCurrency }),
    );
  });

  // Chat del asistente. Un mensaje nunca ejecuta nada: a lo sumo deja una propuesta que el
  // cobrador debe confirmar en otro endpoint.
  router.post("/me/assistant/messages", assistantLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const text = sanitizeAssistantText(request.body?.text);
    if (!text) {
      return response.status(400).json({ error: "invalid message" });
    }

    const startedAt = Date.now();
    const now = new Date();
    const interpretation = await assistant.interpret(text, now);
    await database.expirePaymentRequests();
    let reply;
    if (PREPARERS[interpretation.intent]) {
      const prepared = PREPARERS[interpretation.intent]({
        interpretation,
        text,
        contacts: database.listCollectorContacts(collector.id),
        config,
        now,
      });
      if (prepared.type === "proposal" && prepared.kind === "schedule_payment") {
        const contact = database.getCollectorContact(prepared.payload.contactId, collector.id);
        if (!database.getPayoutEmailForPayer(contact.payer_id)) {
          prepared.type = "clarification";
          prepared.text = `${contact.display_name} aún no registra su correo para recibir envíos. Pídele que lo haga en su app.`;
        } else {
          const { availableCents } = database.getAvailableCents(collector.id, prepared.payload.currency);
          // Solo un aviso: el saldo se verifica de nuevo, de forma atómica, a la hora del envío.
          prepared.payload.balanceWarning = Math.round(Number(prepared.payload.amount) * 100) > availableCents;
        }
      }
      reply = prepared.type === "proposal"
        ? await storeProposal(collector.id, prepared, now)
        : { kind: "text", text: prepared.text };
    } else if (interpretation.intent === "cancel_scheduled") {
      const scheduled = database.listScheduledActions(collector.id);
      const prepared = prepareCancel({ interpretation, text, scheduled });
      if (prepared.type === "proposal") {
        reply = await storeProposal(collector.id, prepared, now);
      } else if (prepared.type === "scheduled") {
        reply = { kind: "scheduled", text: prepared.text, scheduled: activeScheduled(scheduled) };
      } else {
        reply = { kind: "text", text: prepared.text };
      }
    } else if (interpretation.intent === "scheduled") {
      const active = activeScheduled(database.listScheduledActions(collector.id));
      reply = active.length === 0
        ? { kind: "text", text: "Aún no tienes nada programado." }
        : {
          kind: "scheduled",
          text: active.length === 1 ? "Tienes 1 programación activa." : `Tienes ${active.length} programaciones activas.`,
          scheduled: active,
        };
    } else {
      reply = buildAssistantReply({
        database,
        collectorId: collector.id,
        interpretation,
        currency: config.payments.defaultCurrency,
      });
    }
    // Sin texto del usuario ni datos personales en el log.
    console.info("assistant message", {
      intent: interpretation.intent,
      method: interpretation.method,
      reply: reply.kind,
      elapsedMs: Date.now() - startedAt,
    });
    const suggestions = debtorSuggestions(reply, database.listCollectorContacts(collector.id));
    return response.status(200).json({ reply, suggestions, method: interpretation.method });
  });

  async function storeProposal(collectorId, prepared, now) {
    const proposalId = createOpaqueToken(32);
    const expiresAt = new Date(now.getTime() + config.scheduling.proposalTtlSeconds * 1000).toISOString();
    await database.createAssistantProposal({
      id: proposalId,
      collectorId,
      kind: prepared.kind,
      payload: prepared.payload,
      expiresAt,
    });
    const { payload } = prepared;
    const isSplit = prepared.kind === "split_bill";
    return {
      kind: "proposal",
      text: PROPOSAL_INTRO[prepared.kind],
      proposal: {
        proposalId,
        action: prepared.kind,
        // En una división, la tarjeta muestra el total y la lista de partes (sin ids internos).
        contactName: isSplit ? "" : payload.contactName,
        amount: isSplit ? payload.total : payload.amount,
        currency: payload.currency,
        concept: payload.concept,
        runAt: payload.runAt,
        runAtLabel: payload.runAt ? formatRunAt(new Date(payload.runAt)) : null,
        recurrence: payload.recurrence || null,
        occurrencesTotal: payload.occurrencesTotal || null,
        participants: isSplit
          ? payload.participants.map((share) => ({ contactName: share.contactName, amount: share.amount }))
          : [],
        myShare: isSplit ? payload.myShare : null,
        balanceWarning: payload.balanceWarning === true,
        expiresAt,
      },
    };
  }

  router.post("/me/assistant/proposals/:proposalId/confirm", scheduledWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const proposal = isProposalId(request.params.proposalId)
      ? await database.consumeAssistantProposal(request.params.proposalId, collector.id)
      : null;
    if (!proposal) {
      return response.status(404).json({ error: "proposal not found" });
    }

    if (proposal.kind === "cancel_scheduled") {
      const cancelled = await database.cancelScheduledAction(proposal.payload.scheduledId, collector.id);
      return cancelled
        ? response.status(200).json({ cancelled: true })
        : response.status(404).json({ error: "scheduled action not found" });
    }

    // Se vuelve a validar todo con lo guardado; nada viene del cliente salvo autoExecute.
    const { payload } = proposal;
    // En un pago, lo automático debe pedirse de forma explícita (apagado por defecto); en cobros
    // y recordatorios, encendido por defecto.
    const autoExecute = proposal.kind === "schedule_payment"
      ? request.body?.autoExecute === true
      : request.body?.autoExecute !== false;

    if (proposal.kind === "reminder" && !payload.runAt) {
      const contact = database.getCollectorContact(payload.contactId, collector.id);
      if (!contact) {
        return response.status(404).json({ error: "proposal not found" });
      }
      const sent = await sendReminder({
        database,
        config,
        collectorId: collector.id,
        payerId: contact.payer_id,
        amount: payload.amount,
        currency: payload.currency,
        concept: payload.concept,
      });
      if (!sent) {
        return response.status(409).json({ error: "reminder limit reached" });
      }
      console.info("reminder sent", { ref: contact.id.slice(0, 8) });
      return response.status(201).json({ reminder: { sent: true, contactName: payload.contactName } });
    }
    const shares = proposal.kind === "split_bill"
      ? payload.participants
      : [{ contactId: payload.contactId, contactName: payload.contactName, amount: payload.amount }];
    const contacts = shares.map((share) => database.getCollectorContact(share.contactId, collector.id));
    if (contacts.some((contact) => !contact)) {
      return response.status(404).json({ error: "proposal not found" });
    }

    // División sin fecha: los cobros se crean ahora, uno por persona.
    if (proposal.kind === "split_bill" && !payload.runAt) {
      const created = [];
      for (const [index, share] of shares.entries()) {
        const result = await scheduler.chargeNow({
          collectorId: collector.id,
          contactId: contacts[index].id,
          payerId: contacts[index].payer_id,
          amount: share.amount,
          currency: payload.currency,
          concept: payload.concept,
        });
        created.push({ contactName: share.contactName, amount: share.amount, ok: result.ok, failure: result.failure || null });
      }
      console.info("split bill charged", { parts: created.length, failed: created.filter((entry) => !entry.ok).length });
      return response.status(201).json({ split: created });
    }

    if (Date.parse(payload.runAt) <= Date.now()) {
      return response.status(409).json({ error: "run time already passed" });
    }
    const actions = shares.map((share, index) => ({
      id: createOpaqueToken(18),
      collectorId: collector.id,
      kind: SCHEDULED_KIND[proposal.kind] || "charge",
      contactId: contacts[index].id,
      payerId: contacts[index].payer_id,
      // La columna exige texto: un recordatorio sin monto se guarda vacío.
      amount: share.amount || "",
      currency: payload.currency,
      concept: payload.concept,
      runAt: payload.runAt,
      autoExecute,
      recurrence: proposal.kind === "recurring_charge" ? payload.recurrence : null,
      occurrencesTotal: proposal.kind === "recurring_charge" ? payload.occurrencesTotal : 1,
      anchorDay: proposal.kind === "recurring_charge" ? payload.anchorDay : null,
    }));
    if (proposal.kind === "schedule_payment") {
      const contact = contacts[0];
      if (!database.getPayoutEmailForPayer(contact.payer_id)) {
        return response.status(409).json({ error: "recipient cannot receive" });
      }
    }
    const created = await database.createScheduledActions(actions, config.scheduling.maxActive, {
      maxAutoPayments: config.scheduling.maxAutoPayments,
    });
    if (!created) {
      return response.status(409).json({ error: "scheduled limit reached" });
    }
    console.info("scheduled action created", { refs: actions.map((action) => action.id.slice(0, 8)) });
    const ids = new Set(actions.map((action) => action.id));
    const items = database.listScheduledActions(collector.id)
      .filter((entry) => ids.has(entry.scheduledId))
      .map(toScheduledItem);
    return response.status(201).json({ scheduled: items[0], scheduledItems: items });
  });

  // Recordatorios que le llegaron al cobrador como pagador (su perfil de pagador ligado).
  router.get("/me/reminders", readLimiter, (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const reminders = collector.payer_id ? database.listPayerReminders(collector.payer_id) : [];
    return response.status(200).json({ reminders: reminders.map(toVisibleReminder) });
  });

  router.post("/me/reminders/:reminderId/dismiss", scheduledWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const dismissed = collector.payer_id && isReminderId(request.params.reminderId)
      && await database.dismissPayerReminder(request.params.reminderId, collector.payer_id);
    return dismissed
      ? response.status(200).json({ dismissed: true })
      : response.status(404).json({ error: "reminder not found" });
  });

  router.get("/me/scheduled", readLimiter, (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    return response.status(200).json({
      scheduled: database.listScheduledActions(collector.id).map(toScheduledItem),
      maxActive: config.scheduling.maxActive,
    });
  });

  router.post("/me/scheduled/:scheduledId/cancel", scheduledWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const cancelled = isScheduledId(request.params.scheduledId)
      && await database.cancelScheduledAction(request.params.scheduledId, collector.id);
    return cancelled
      ? response.status(200).json({ cancelled: true })
      : response.status(404).json({ error: "scheduled action not found" });
  });

  // "Confirmar a la hora": el cobrador ejecuta algo que quedó esperando su confirmación.
  router.post("/me/scheduled/:scheduledId/run", scheduledWriteLimiter, async (request, response) => {
    const collector = authenticateCollector(request, database);
    if (!collector) {
      return response.status(401).json({ error: "collector authentication failed" });
    }
    const action = isScheduledId(request.params.scheduledId)
      ? await database.claimScheduledActionForRun(request.params.scheduledId, collector.id)
      : null;
    if (!action) {
      return response.status(404).json({ error: "scheduled action not found" });
    }
    await scheduler.executeAction(action);
    const item = database.listScheduledActions(collector.id).find((entry) => entry.scheduledId === action.id);
    return response.status(200).json({ scheduled: toScheduledItem(item) });
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
