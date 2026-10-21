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
- Estado: Fase 2 implementada localmente; falta ejecutar el pago real Sandbox.

## 3. Estado por fases

- Fase 1 - Setup: terminada (2026-10-20); `/health` responde localmente y en Render.
- Fase 2 - PayPal: en curso (2026-10-21); el pago Sandbox y la idempotencia del webhook fueron validados; la corrección de la carrera queda lista para desplegar y falta confirmar saldo Business y revisar logs manualmente.
- Fase 3 - QR y página del pagador: pendiente.
- Fase 4 - Agente de IA: pendiente.
- Fase 5 - App Android: pendiente.
- Fase 6 - Tiempo real y pulido: pendiente.
- Fase 7 - Extras: pendiente.

## 4. Decisiones tomadas y su motivo

- Android confirmado; teléfono de pruebas: Samsung SM-A146U con Android 14.
- No hay lentes físicos; se usará Mock Device Kit y la cámara del celular como respaldo.
- La capa de IA será intercambiable; el proveedor/modelo queda pendiente.
- País y moneda por defecto: México y MXN; interfaz y voz en español.
- Tope inicial de cobro: $1,000 MXN.
- Seguridad de demo: confirmación explícita del cobrador y aprobación del pagador.
- Demo en vivo con dos celulares; se espera pulido visual alto y se dedicarán 3+ horas diarias.
- Todo PayPal será Sandbox y los secretos vivirán únicamente en variables de entorno del servidor.
- Se eligió Orders API v2 sobre Invoicing API porque el flujo requiere aprobación y captura inmediata desde el teléfono del pagador.
- La persistencia usa `sql.js` sobre SQLite para evitar dependencias nativas; el archivo local está en `.data/glasspay.db`.
- Las transiciones de estado usan una sentencia SQL atómica monotónica y las escrituras de `sql.js` se serializan mediante una cola.

## 5. Preguntas abiertas

- ¿Qué proveedor/modelo de IA se conectará a la interfaz intercambiable?
- ¿Está disponible el acceso operativo al Meta Wearables Developer Center?

## 6. Endpoints y contratos de datos vigentes

- `GET /health` en `server/src/index.js`: responde HTTP 200 con `{ status: "ok", service: "glasspay-server" }`.
- `POST /api/payments`: valida monto, moneda y concepto; crea una orden y devuelve `paymentId` y `approveUrl`.
- `GET /api/payments/:id`: devuelve estado, monto, moneda, concepto e IDs de PayPal.
- `GET /paypal/return`: informa que se espera la confirmación; no marca el pago como pagado.
- `GET /paypal/cancel`: informa cancelación; no marca el pago como pagado.
- `POST /webhooks/paypal`: conserva el cuerpo crudo, verifica firma con PayPal, correlaciona capturas por orden y procesa eventos idempotentemente; responde `503` si aún no puede aplicar un evento para provocar reintento.
- Producción: `https://glasspay-1qdu.onrender.com/health`.
- PayPal Sandbox confirmado: OAuth `POST /v1/oauth2/token`, Orders API v2 en `/v2/checkout/orders` y verificación en `POST /v1/notifications/verify-webhook-signature`.
- Eventos manejados: `CHECKOUT.ORDER.APPROVED`, `PAYMENT.CAPTURE.COMPLETED` y `PAYMENT.CAPTURE.DENIED`.

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

## 8. Cómo correr y probar cada parte

- Servidor: `cd server`, `npm install`, copiar `.env.example` a `.env` y ejecutar `npm start`.
- Prueba: abrir `http://localhost:3000/health`; debe responder `status: "ok"`.
- Render: usar `render.yaml`; el servicio tiene raíz `server`, build `npm install` y start `npm start`.
- Producción: abrir `https://glasspay-1qdu.onrender.com/health`; debe responder HTTP 200 con `status: "ok"`.
- Crear una orden: `curl.exe -X POST http://localhost:3000/api/payments -H "Content-Type: application/json" -d "{\"amount\":350,\"currency\":\"MXN\",\"concept\":\"Cena\"}"`; debe responder HTTP 201 con `paymentId` y `approveUrl`.
- Consultar una transacción: `curl.exe http://localhost:3000/api/payments/<paymentId>`; debe devolver su estado.
- Completar la prueba: abrir `approveUrl` con la cuenta Personal Sandbox, permitir la captura y esperar `PAYMENT.CAPTURE.COMPLETED`; el estado final debe ser `pagada`.
- Tests: ejecutar `cd server` y `npm test`; deben pasar la prueba de carrera de captura y la prueba de transición monotónica.

## 9. Problemas conocidos y riesgos

- El Meta Wearables Device Access Toolkit está en vista previa y puede cambiar.
- No se cuenta con lentes físicos; la integración real deberá validarse si cambia el acceso.
- PayPal, webhooks y Render son riesgos principales de integración y demo.
- El disco gratuito de Render es efímero; SQLite conserva datos durante el proceso, pero no es una persistencia durable para producción.
- La prueba punta a punta requiere desplegar esta corrección antes de repetir el pago en Render.

## 10. Registro de cambios

- 2026-10-20: Se registraron decisiones iniciales y respuestas de configuración para comenzar la Fase 1.
- 2026-10-20: Se creó el monorepo base, el servidor Express y `GET /health`; queda pendiente validar el despliegue en Render.
- 2026-10-21: Se implementó el flujo PayPal Sandbox con Orders API v2, SQLite, rutas de pagos y webhooks verificados.
- 2026-10-21: Se validó una orden Sandbox de punta a punta en Render; terminó en `pagada` y el reenvío del webhook no duplicó el estado.
- 2026-10-21: Se corrigió la carrera de webhooks con correlación por orden, transiciones monotónicas, cola de escrituras y pruebas automatizadas.
