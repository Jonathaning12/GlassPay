import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { createRoot } from "react-dom/client";
import "./styles.css";

const API_URL = import.meta.env.VITE_API_URL || "http://localhost:3000";
const STORAGE_KEY = "glasspay-payer-session";

const CLOSED_STATE_LABELS = {
  rechazada: "Rechazada",
  expirada: "Expirada",
  fallida: "Falló",
};

function waitingMessage(elapsedMs) {
  if (elapsedMs < 4000) return "Confirmando tu pago con PayPal…";
  if (elapsedMs < 10000) return "Preparando tu comprobante…";
  if (elapsedMs < 20000) return "Terminando detalles…";
  if (elapsedMs < 30000) return "Esto está tardando un poco más de lo normal…";
  return "Tu pago está en proceso…";
}

function loadSession() {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

function saveSession(session) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    return true;
  } catch {
    return false;
  }
}

function clearSession() {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore storage cleanup failures; the in-memory session is still cleared.
  }
}

async function apiRequest(path, options = {}) {
  const response = await fetch(`${API_URL}${path}`, {
    ...options,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.error || "No pudimos completar la solicitud.");
    error.status = response.status;
    throw error;
  }
  return body;
}

function App() {
  const [session, setSession] = useState(loadSession);
  const [displayName, setDisplayName] = useState("");
  const [qrToken, setQrToken] = useState("");
  const [qrExpiresAt, setQrExpiresAt] = useState(null);
  const [requests, setRequests] = useState([]);
  const [reminders, setReminders] = useState([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [approvalStartedAt, setApprovalStartedAt] = useState(null);
  const [clock, setClock] = useState(Date.now());
  const canvasRef = useRef(null);

  function handleSessionError(requestError) {
    if (requestError.status !== 401) {
      return false;
    }
    clearSession();
    setSession(null);
    setError("Tu sesión expiró. Crea un nuevo QR para continuar.");
    return true;
  }

  async function issueQr(currentSession = session) {
    if (!currentSession) {
      return;
    }
    const result = await apiRequest(`/api/payers/${currentSession.payerId}/qr`, {
      method: "POST",
      headers: {
        "X-Payer-Id": currentSession.payerId,
        "X-Payer-Session": currentSession.sessionSecret,
      },
    });
    setQrToken(result.token);
    setQrExpiresAt(new Date(result.expiresAt));
  }

  async function createProfile(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const result = await apiRequest("/api/payers", {
        method: "POST",
        body: JSON.stringify({ displayName }),
      });
      const nextSession = {
        payerId: result.payerId,
        sessionSecret: result.sessionSecret,
      };
      if (!saveSession(nextSession)) {
        throw new Error("No pudimos guardar la sesión en este dispositivo.");
      }
      setSession(nextSession);
      await issueQr(nextSession);
    } catch (requestError) {
      if (handleSessionError(requestError)) {
        return;
      }
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadRequests() {
    if (!session) {
      return;
    }
    try {
      const result = await apiRequest("/api/payers/me/payment-requests", {
        headers: {
          "X-Payer-Id": session.payerId,
          "X-Payer-Session": session.sessionSecret,
        },
      });
      setRequests(result.requests);
      setReminders(result.reminders || []);
      setError("");
    } catch (requestError) {
      if (handleSessionError(requestError)) {
        return;
      }
      setError(requestError.message);
    }
  }

  async function dismissReminder(reminderId) {
    setError("");
    try {
      await apiRequest(`/api/payers/me/reminders/${reminderId}/dismiss`, {
        method: "POST",
        headers: {
          "X-Payer-Id": session.payerId,
          "X-Payer-Session": session.sessionSecret,
        },
      });
      await loadRequests();
    } catch (requestError) {
      if (handleSessionError(requestError)) {
        return;
      }
      setError(requestError.message);
    }
  }

  async function rejectRequest(requestId) {
    setError("");
    try {
      await apiRequest(`/api/payers/payment-requests/${requestId}/reject`, {
        method: "POST",
        headers: {
          "X-Payer-Id": session.payerId,
          "X-Payer-Session": session.sessionSecret,
        },
      });
      await loadRequests();
    } catch (requestError) {
      if (handleSessionError(requestError)) {
        return;
      }
      setError(requestError.message);
    }
  }

  useEffect(() => {
    if (!session) {
      return undefined;
    }
    issueQr().catch((requestError) => setError(requestError.message));
    loadRequests();
    const interval = window.setInterval(loadRequests, 2000);
    return () => window.clearInterval(interval);
  }, [session]);

  useEffect(() => {
    if (!qrToken || !canvasRef.current) {
      return;
    }
    QRCode.toCanvas(canvasRef.current, qrToken, {
      width: 260,
      margin: 2,
      color: { dark: "#172033", light: "#ffffff" },
    }).catch(() => setError("No pudimos generar el código QR."));
  }, [qrToken]);

  useEffect(() => {
    if (!qrExpiresAt) {
      return undefined;
    }
    const interval = window.setInterval(() => {
      if (qrExpiresAt.getTime() - Date.now() < 30_000) {
        issueQr().catch((requestError) => setError(requestError.message));
      }
    }, 10_000);
    return () => window.clearInterval(interval);
  }, [qrExpiresAt]);

  useEffect(() => {
    if (!approvalStartedAt) {
      return undefined;
    }
    const interval = window.setInterval(() => setClock(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, [approvalStartedAt]);

  // "aprobada" significa que PayPal ya recibió la aprobación y falta la captura:
  // ya no se puede volver a aprobar, así que pasa a "Pagos realizados".
  const pendingRequests = requests.filter((request) => request.state === "pendiente_de_pagador");
  const completedRequests = requests.filter((request) => ["aprobada", "pagada"].includes(request.state));
  const closedRequests = requests.filter((request) => request.state in CLOSED_STATE_LABELS);

  if (!session) {
    return (
      <main className="shell">
        <section className="card">
          <p className="eyebrow">GLASSPAY</p>
          <h1>Tu identidad de pagador</h1>
          <p className="muted">Crea un nombre visible para generar tu QR personal.</p>
          <form onSubmit={createProfile} className="stack">
            <label htmlFor="display-name">Nombre visible</label>
            <input
              id="display-name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              maxLength={60}
              placeholder="Alex"
              required
            />
            <button type="submit" disabled={loading}>
              {loading ? "Creando…" : "Crear mi QR"}
            </button>
          </form>
          {error && <p className="error" role="alert">{error}</p>}
        </section>
      </main>
    );
  }

  return (
    <main className="shell">
      <section className="card">
        <p className="eyebrow">GLASSPAY</p>
        <h1>Mi QR</h1>
        <p className="muted">Muéstralo al cobrador para recibir una solicitud.</p>
        <div className="qr-frame">
          <canvas ref={canvasRef} aria-label="Código QR de pagador" />
        </div>
        {qrExpiresAt && (
          <p className="muted small">
            Se renueva automáticamente · vence {qrExpiresAt.toLocaleTimeString()}
          </p>
        )}
      </section>
      {reminders.length > 0 && (
        <section className="card">
          <h2>Recordatorios</h2>
          <div className="stack">
            {reminders.map((reminder) => (
              <article className="request" key={reminder.reminderId}>
                <div>
                  {/* El texto viene de una plantilla fija del servidor; React lo escapa. */}
                  <p>{reminder.text}</p>
                  <p className="muted small">{new Date(reminder.createdAt).toLocaleString()}</p>
                </div>
                <div className="actions">
                  <button type="button" className="secondary" onClick={() => dismissReminder(reminder.reminderId)}>
                    Descartar
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}
      <section className="card">
        <h2>Por aprobar</h2>
        {pendingRequests.length === 0 && <p className="muted">No tienes solicitudes pendientes.</p>}
        <div className="stack">
          {pendingRequests.map((request) => (
            <article className="request" key={request.requestId}>
              <div>
                <strong>{request.amount} {request.currency}</strong>
                <p>{request.concept}</p>
                {request.fromName && <p className="muted">Te cobra {request.fromName}</p>}
                {approvalStartedAt && approvalStartedAt.requestId === request.requestId && (
                  <p className="muted" aria-live="polite">
                    {waitingMessage(clock - approvalStartedAt.startedAt)}
                    {" El servidor aún no confirma un pago recibido."}
                  </p>
                )}
              </div>
              <div className="actions">
                <button
                  type="button"
                  onClick={() => {
                    setApprovalStartedAt({ requestId: request.requestId, startedAt: Date.now() });
                    window.open(request.approveUrl, "_blank", "noopener,noreferrer");
                  }}
                >
                  Aprobar con PayPal
                </button>
                <button type="button" className="secondary" onClick={() => rejectRequest(request.requestId)}>
                  Rechazar
                </button>
              </div>
            </article>
          ))}
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </section>
      {completedRequests.length > 0 && (
        <section className="card">
          <h2>Pagos realizados</h2>
          <div className="stack">
            {completedRequests.map((request) => (
              <article className="request" key={request.requestId}>
                <div>
                  <strong>{request.amount} {request.currency}</strong>
                  <p>{request.concept}</p>
                  {request.fromName && <p className="muted">Te cobra {request.fromName}</p>}
                  {request.state === "pagada"
                    ? <p className="success">Pago recibido</p>
                    : <span className="badge">Procesando pago</span>}
                </div>
              </article>
            ))}
          </div>
        </section>
      )}
      {closedRequests.length > 0 && (
        <section className="card">
          <h2>Historial</h2>
          <div className="stack">
            {closedRequests.map((request) => (
              <article className="request" key={request.requestId}>
                <div>
                  <strong>{request.amount} {request.currency}</strong>
                  <p>{request.concept}</p>
                  <span className="badge">{CLOSED_STATE_LABELS[request.state]}</span>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}
    </main>
  );
}

createRoot(document.getElementById("root")).render(<App />);
