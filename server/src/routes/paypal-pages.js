const express = require("express");

const pageStyles = `
  :root { color-scheme: light; font-family: system-ui, -apple-system, sans-serif; }
  body { margin: 0; min-height: 100vh; background: #f4f7fb; color: #172033; }
  main { box-sizing: border-box; width: min(100% - 32px, 520px); margin: 0 auto; padding: 48px 0; }
  section { background: #fff; border: 1px solid #dce3ee; border-radius: 18px; padding: 28px; box-shadow: 0 8px 24px #17203314; }
  h1 { margin: 0 0 12px; font-size: clamp(1.6rem, 7vw, 2.2rem); }
  p { line-height: 1.5; margin: 10px 0; }
  .loading { display: inline-flex; align-items: center; gap: 10px; color: #52627a; }
  .spinner { width: 18px; height: 18px; border: 3px solid #dce3ee; border-top-color: #1769aa; border-radius: 50%; animation: spin 0.8s linear infinite; }
  .success { color: #126b42; }
  .failure { color: #a32929; }
  .muted { color: #52627a; }
  button { border: 0; border-radius: 10px; background: #1769aa; color: #fff; cursor: pointer; font: inherit; padding: 11px 16px; }
  button:hover { background: #125487; }
  @keyframes spin { to { transform: rotate(360deg); } }
`;

function renderReturnPage() {
  return `<!doctype html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Confirmación de pago | GlassPay</title>
  <style>${pageStyles}</style>
</head>
<body>
  <main>
    <section aria-live="polite">
      <h1 id="title">Confirmando tu pago…</h1>
      <p id="message" class="loading"><span class="spinner" aria-hidden="true"></span><span>Estamos verificando el estado con el servidor.</span></p>
      <div id="details"></div>
      <button id="retry" type="button" hidden>Volver a consultar</button>
    </section>
  </main>
  <script>
    (() => {
      const token = new URLSearchParams(window.location.search).get("token");
      const title = document.getElementById("title");
      const message = document.getElementById("message");
      const details = document.getElementById("details");
      const retry = document.getElementById("retry");
      let startedAt = Date.now();
      let timer;

      function showDetails(payment) {
        details.replaceChildren();
        const values = [
          ["Monto", payment.amount + " " + payment.currency],
          ["Concepto", payment.concept],
        ];
        for (const [label, value] of values) {
          const paragraph = document.createElement("p");
          const strong = document.createElement("strong");
          strong.textContent = label + ": ";
          paragraph.append(strong, document.createTextNode(value));
          details.appendChild(paragraph);
        }
      }

      function waitingText(elapsedMs) {
        if (elapsedMs < 4000) return "Confirmando tu pago con PayPal…";
        if (elapsedMs < 10000) return "Preparando tu comprobante…";
        if (elapsedMs < 20000) return "Terminando detalles…";
        if (elapsedMs < 30000) return "Esto está tardando un poco más de lo normal…";
        return "Tu pago está en proceso…";
      }

      function showWaiting() {
        title.textContent = waitingText(Date.now() - startedAt);
        message.className = "loading";
        message.innerHTML = '<span class="spinner" aria-hidden="true"></span><span>El servidor aún no confirma un pago recibido.</span>';
        retry.hidden = true;
      }

      function showPending() {
        title.textContent = "Tu pago está en proceso";
        message.className = "muted";
        message.textContent = "El servidor aún no confirma un pago recibido. Puedes volver a consultar.";
        retry.hidden = false;
      }

      async function checkStatus() {
        if (!token || Date.now() - startedAt >= 30000) {
          showPending();
          return;
        }

        try {
          const response = await fetch("/api/payments/status?token=" + encodeURIComponent(token), {
            headers: { Accept: "application/json" },
            cache: "no-store",
          });
          if (response.status === 404) {
            title.textContent = "No pudimos encontrar este pago";
            message.className = "muted";
            message.textContent = "Verifica el enlace o vuelve a intentarlo más tarde.";
            return;
          }
          if (!response.ok) {
            throw new Error("status request failed");
          }

          const payment = await response.json();
          if (payment.state === "pagada") {
            title.textContent = "Pago recibido";
            message.className = "success";
            message.textContent = "La confirmación se recibió correctamente.";
            showDetails(payment);
            return;
          }
          if (payment.state === "fallida") {
            title.textContent = "El pago no se completó";
            message.className = "failure";
            message.textContent = "No se pudo completar el cobro. Puedes cerrar esta página.";
            return;
          }
          if (Date.now() - startedAt >= 30000) {
            showPending();
            return;
          }
          showWaiting();
          timer = window.setTimeout(checkStatus, 1500);
        } catch {
          if (Date.now() - startedAt >= 30000) {
            showPending();
            return;
          }
          showWaiting();
          timer = window.setTimeout(checkStatus, 1500);
        }
      }

      retry.addEventListener("click", () => {
        startedAt = Date.now();
        retry.hidden = true;
        showWaiting();
        checkStatus();
      });

      window.addEventListener("pagehide", () => window.clearTimeout(timer));
      checkStatus();
    })();
  </script>
</body>
</html>`;
}

function renderCancelPage() {
  return `<!doctype html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Pago cancelado | GlassPay</title>
  <style>${pageStyles}</style>
</head>
<body>
  <main>
    <section>
      <h1>Pago cancelado</h1>
      <p class="muted">No se hizo ningún cobro.</p>
    </section>
  </main>
</body>
</html>`;
}

function createPayPalPagesRouter() {
  const router = express.Router();

  router.get("/return", (_request, response) => {
    response.type("html").send(renderReturnPage());
  });

  router.get("/cancel", (_request, response) => {
    response.type("html").send(renderCancelPage());
  });

  return router;
}

module.exports = { createPayPalPagesRouter };
