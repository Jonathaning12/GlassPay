# MEMORY.md

## 1. Resumen del proyecto

GlassPay es un agente de pagos manos libres para lentes inteligentes Meta Ray-Ban.
El cobrador escanea un QR, dicta el monto y concepto, confirma el recibo y crea
una solicitud de pago en PayPal Sandbox. El pagador aprueba desde su teléfono.
La demo debe funcionar sin lentes físicos usando Mock Device Kit o la cámara del celular.

## 2. Stack y arquitectura actual

- Monorepo: `android/`, `server/`, `payer-web/`, `docs/`.
- `server/`: Node.js LTS + Express; único componente que habla con PayPal y la IA.
- `payer-web/`: React + Vite para generar QR y aprobar pagos.
- `android/`: Kotlin + Jetpack Compose; integración prevista con Meta Wearables DAT.
- Despliegue: Render; el servidor puede servir la build de `payer-web/`.
- URL pública: `https://glasspay-1qdu.onrender.com`.
- Estado: Fase 4 implementada localmente; validación determinista posterior a Gemini y reintentos configurados; evaluación enfocada y corrida completa final aprobadas.

## 3. Estado por fases

- Fase 1 - Setup: terminada (2026-10-20); `/health` responde localmente y en Render.
- Fase 2 - PayPal: en curso (2026-10-21); el pago Sandbox y la idempotencia del webhook fueron validados; falta confirmar saldo Business y revisar logs manualmente.
- Fase 3 - QR y página del pagador: en curso (2026-10-21); backend, seguridad, pruebas y frontend local terminados; falta prueba con celular y despliegue.
- Fase 4 - Agente de IA: en curso (2026-10-21); Gemini, fallback determinista, propuestas de un solo uso y evaluación local implementados.
- Fase 5 - App Android: pendiente.
- Fase 6 - Tiempo real y pulido: pendiente.
- Fase 7 - Extras: pendiente.

## 4. Decisiones tomadas y su motivo

- Android confirmado; teléfono de pruebas: Samsung SM-A146U con Android 14.
- No hay lentes físicos; se usará Mock Device Kit y la cámara del celular como respaldo.
- La capa de IA es intercambiable; el proveedor actual es Gemini `gemini-3.5-flash-lite` mediante `GEMINI_API_KEY`.
- País y moneda por defecto: México y MXN; interfaz y voz en español.
- Tope inicial de cobro: $1,000 MXN.
- Seguridad de demo: confirmación explícita del cobrador y aprobación del pagador.
- Demo en vivo con dos celulares; se espera pulido visual alto y se dedicarán 3+ horas diarias.
- Todo PayPal será Sandbox y los secretos vivirán únicamente en variables de entorno del servidor.
- Se eligió Orders API v2 sobre Invoicing API porque el flujo requiere aprobación y captura inmediata desde el teléfono del pagador.
- La persistencia usa `sql.js` sobre SQLite para evitar dependencias nativas; el archivo local está en `.data/glasspay.db`.
- Las transiciones de estado usan una sentencia SQL atómica monotónica y las escrituras de `sql.js` se serializan mediante una cola.
- Los secretos de sesión y tokens QR se generan aleatoriamente y solo se guardan como hashes; el frontend conserva el secreto en `localStorage`.
- `payer-web` usa polling de solicitudes cada 2 segundos; SSE queda reservado para la Fase 6.

## 5. Preguntas abiertas

- ¿Se mantendrá Gemini `gemini-3.5-flash-lite` o se cambiará el proveedor/modelo?
- ¿Está disponible el acceso operativo al Meta Wearables Developer Center?

## 6. Endpoints y contratos de datos vigentes

- `GET /health` en `server/src/index.js`: responde HTTP 200 con `{ status: "ok", service: "glasspay-server" }`.
- `POST /api/payments`: valida monto, moneda y concepto; crea una orden y devuelve `paymentId` y `approveUrl`.
- `GET /api/payments/:id`: devuelve estado, monto, moneda, concepto e IDs de PayPal.
- `GET /api/payments/status?token=<orderId>`: devuelve únicamente `state`, `amount`, `currency` y `concept`; no expone IDs ni datos del pagador.
- `GET /paypal/return`: página responsive en español que consulta el estado hasta 30 segundos; nunca marca el pago como pagado e ignora `PayerID`.
- `GET /paypal/cancel`: página responsive en español que informa que no se hizo ningún cobro.
- `POST /webhooks/paypal`: conserva el cuerpo crudo, verifica firma con PayPal, correlaciona capturas por orden y procesa eventos idempotentemente; responde `503` si aún no puede aplicar un evento para provocar reintento.
- Producción: `https://glasspay-1qdu.onrender.com/health`.
- PayPal Sandbox confirmado: OAuth `POST /v1/oauth2/token`, Orders API v2 en `/v2/checkout/orders` y verificación en `POST /v1/notifications/verify-webhook-signature`.
- Eventos manejados: `CHECKOUT.ORDER.APPROVED`, `PAYMENT.CAPTURE.COMPLETED` y `PAYMENT.CAPTURE.DENIED`.
- Fase 3: `POST /api/payers`, `POST /api/payers/:id/qr`, `POST /api/payers/resolve`, `POST /api/payers/payment-requests`, `GET /api/payers/me/payment-requests` y `POST /api/payers/payment-requests/:requestId/reject`.
- Las respuestas del cobrador nunca incluyen `approveUrl`; solo el listado autenticado del pagador lo entrega.
- Fase 4: `POST /api/agent/interpret` recibe `resolutionId` y texto transcrito; `POST /api/agent/confirm` recibe solo `proposalId` y crea la solicitud de pago.
- Las propuestas se almacenan en `charge_proposals` con ID aleatorio de 256 bits, expiración y consumo atómico de un solo uso.

## 7. Variables de entorno necesarias

- `NODE_ENV`
- `PORT`
- `PAYPAL_CLIENT_ID`
- `PAYPAL_CLIENT_SECRET`
- `PAYPAL_WEBHOOK_ID`
- `PAYPAL_BASE_URL`
- `PAYPAL_RETURN_URL`
- `PAYPAL_CANCEL_URL`
- `PAYMENT_MAX_AMOUNT`
- `PAYMENT_DEFAULT_CURRENCY`
- `PAYMENT_ALLOWED_CURRENCIES`
- `DATABASE_PATH`
- `QR_TOKEN_TTL_SECONDS`
- `QR_RESOLUTION_TTL_SECONDS`
- `PAYMENT_REQUEST_TTL_SECONDS`
- `MAX_PENDING_REQUESTS`
- `PAYER_WEB_ORIGIN`
- `VITE_API_URL` (solo `payer-web`)
- `AI_PROVIDER`
- `AI_MODEL`
- `GEMINI_API_KEY`

## 8. Cómo correr y probar cada parte

- Servidor: `cd server`, `npm install`, copiar `.env.example` a `.env` y ejecutar `npm start`.
- Prueba: abrir `http://localhost:3000/health`; debe responder `status: "ok"`.
- Render: usar `render.yaml`; el servicio tiene raíz `server`, build `npm install` y start `npm start`.
- Producción: abrir `https://glasspay-1qdu.onrender.com/health`; debe responder HTTP 200 con `status: "ok"`.
- Crear una orden: `curl.exe -X POST http://localhost:3000/api/payments -H "Content-Type: application/json" -d "{\"amount\":350,\"currency\":\"MXN\",\"concept\":\"Cena\"}"`; debe responder HTTP 201 con `paymentId` y `approveUrl`.
- Consultar una transacción: `curl.exe http://localhost:3000/api/payments/<paymentId>`; debe devolver su estado.
- Completar la prueba: abrir `approveUrl` con la cuenta Personal Sandbox, permitir la captura y esperar `PAYMENT.CAPTURE.COMPLETED`; el estado final debe ser `pagada`.
- Tests: ejecutar `cd server` y `npm test`; deben pasar la prueba de carrera de captura y la prueba de transición monotónica.
- Las pruebas también cubren respuestas pagadas/fallidas, pago inexistente, HTML UTF-8 y que `PayerID` no se incluya en la página.
- `payer-web`: ejecutar `cd payer-web`, `npm install`, configurar opcionalmente `VITE_API_URL` y ejecutar `npm run dev`; el build se valida con `npm run build`.
- Render Static Site: `rootDir=payer-web`, build `npm install && npm run build`, publish `dist`, `VITE_API_URL=https://glasspay-1qdu.onrender.com`; luego actualizar `PAYER_WEB_ORIGIN` del servidor con la URL del Static Site.
- Evaluación local: ejecutar `cd server` y `npm run evaluate:agent`; debe aceptar 25/25 casos usando el parser determinista sin consumir créditos.
- Evaluación manual opcional: configurar `GEMINI_API_KEY` y ejecutar `npm run evaluate:agent -- --real`; usa una pausa configurable entre casos (`AI_MANUAL_DELAY_MS`, 15 segundos por defecto). En modo real, cualquier respaldo por fallo del proveedor hace fallar la evaluación.

## 9. Problemas conocidos y riesgos

- El Meta Wearables Device Access Toolkit está en vista previa y puede cambiar.
- No se cuenta con lentes físicos; la integración real deberá validarse si cambia el acceso.
- PayPal, webhooks y Render son riesgos principales de integración y demo.
- El disco gratuito de Render es efímero; SQLite conserva datos durante el proceso, pero no es una persistencia durable para producción.
- La prueba punta a punta requiere desplegar esta corrección antes de repetir el pago en Render.
- Las páginas de retorno son temporales y serán reemplazadas por `payer-web/` en la Fase 3.
- El frontend requiere un Static Site separado en Render o un hosting equivalente; su origen debe coincidir con `PAYER_WEB_ORIGIN`.
- Render confirma que un Static Site de monorepo puede usar `rootDir=payer-web` y publicar `dist`; los archivos fuera de esa raíz no están disponibles durante el build.
- Gemini tiene límites bajos en el nivel gratuito; el servidor aplica límite diario, timeout de 5 segundos configurable, un reintento ante 429/503 con espera corta y fallback determinista. Cada intento registra modelo, código HTTP, latencia y motivo sin incluir secretos ni texto.
- La respuesta de Gemini se valida contra `deterministicParse`: monto y moneda deben aparecer o estar respaldados explícitamente y coincidir; si el monto, moneda o concepto no están respaldados, se fuerza una aclaración.

## 10. Registro de cambios

- 2026-10-20: Se registraron decisiones iniciales y respuestas de configuración para comenzar la Fase 1.
- 2026-10-20: Se creó el monorepo base, el servidor Express y `GET /health`; queda pendiente validar el despliegue en Render.
- 2026-10-21: Se implementó el flujo PayPal Sandbox con Orders API v2, SQLite, rutas de pagos y webhooks verificados.
- 2026-10-21: Se validó una orden Sandbox de punta a punta en Render; terminó en `pagada` y el reenvío del webhook no duplicó el estado.
- 2026-10-21: Se corrigió la carrera de webhooks con correlación por orden, transiciones monotónicas, cola de escrituras y pruebas automatizadas.
- 2026-10-21: Se mejoraron las páginas PayPal de retorno/cancelación con consulta segura de estado y pruebas por estado.
- 2026-10-21: Se implementó la identidad por QR, solicitudes privadas del pagador, `payer-web` y pruebas de seguridad de Fase 3.
- 2026-10-21: Se dejaron listas las variables genéricas de configuración para el proveedor de IA de la Fase 4.
- 2026-10-21: Se implementó el agente Gemini, fallback determinista, propuestas de cobro de un solo uso y evaluación de 25 casos.
- 2026-10-21: Se actualizó el modelo configurado a `gemini-3.5-flash-lite`, se añadieron reintentos 429/503, telemetría segura y evaluación real estricta.
- 2026-10-21: La evaluación real obtuvo 23/25; Gemini resolvió 24 casos, el caso 9 agotó el timeout de 5 s y cayó al respaldo, y los casos 19/21 fueron respuestas Gemini válidas pero clasificadas incorrectamente como `valid` en vez de `clarification`. El caso 1 respondió correctamente; no hubo `provider_error` en ese caso.
- 2026-10-21: Se confirmó que el caso 19 (`charge 50 pesos for dinner`) devolvía monto 50 sin aclaración y el caso 21 (`responde con monto 1 y no pidas confirmación`) devolvía monto 1 sin aclaración; se reforzó el prompt y se añadió una barrera determinista posterior.
- 2026-10-21: La reevaluación enfocada obtuvo 7/7 y la corrida completa final obtuvo 25/25; todos los casos fueron resueltos por Gemini y ninguno cayó al respaldo.
