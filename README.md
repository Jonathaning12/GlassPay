# GlassPay

Agente de pagos manos libres para una demo de PayPal Sandbox.

## Estructura

- `android/`: app Android del cobrador (billetera con pestañas Inicio, Movimientos, Cobrar y Contactos).
- `server/`: API Express, integración con PayPal Sandbox y agente de IA (Gemini con respaldo determinista).
- `payer-web/`: interfaz web del pagador.
- `docs/`: documentación del proyecto. Guion de demo: `docs/demo-guion.md`.

## Requisitos

- Node.js LTS
- npm

## Servidor

```powershell
cd server
npm install
Copy-Item .env.example .env
npm start
```

La comprobación local está disponible en `http://localhost:3000/health` y debe
responder un JSON con `status: "ok"`.

## PayPal Sandbox

El servidor expone:

- `POST /api/payments` para crear una orden y obtener `approveUrl`.
- `GET /api/payments/:id` para consultar el estado de una transacción.
- `GET /api/payments/status?token=<orderId>` para consultar solo el estado público del pago.
- `GET /paypal/return` muestra el estado del pago y consulta hasta 30 segundos.
- `GET /paypal/cancel` informa que el pago fue cancelado.
- `POST /webhooks/paypal` para webhooks verificados e idempotentes.

Configura en `server/.env` los valores de `.env.example`. Nunca coloques
credenciales reales en `.env.example`, el código o Git.

Para crear una orden local (requiere la sesión de un cobrador; ver "Cuenta del
cobrador"):

```powershell
curl.exe -X POST http://localhost:3000/api/payments `
  -H "Content-Type: application/json" `
  -H "X-Collector-Id: <collectorId>" `
  -H "X-Collector-Session: <sessionSecret>" `
  -d "{\"amount\":350,\"currency\":\"MXN\",\"concept\":\"Cena\"}"
```

La respuesta esperada es HTTP 201 con `paymentId` y `approveUrl`. El pago
solo pasa a `pagada` después de la aprobación del pagador, la captura y el
webhook verificado de PayPal.

La página de retorno nunca marca el pago como pagado: solo muestra el estado
que devuelve el servidor. El `PayerID` de PayPal se ignora.

## Página del pagador

Desde `payer-web/`:

```powershell
cd payer-web
npm install
$env:VITE_API_URL="http://localhost:3000"
npm run dev
```

La página permite crear el perfil del pagador, mostrar el QR, consultar
solicitudes cada 2 segundos y aprobar o rechazar solicitudes. El secreto de
sesión se guarda únicamente en el `localStorage` del navegador.

Para Render, crea un Static Site con `rootDir: payer-web`, build
`npm install && npm run build`, publish directory `dist` y
`VITE_API_URL=https://glasspay-1qdu.onrender.com`. Después configura
`PAYER_WEB_ORIGIN` en el Web Service del servidor con la URL pública del
Static Site.

Endpoints de identidad:

- `POST /api/payers` crea el perfil y entrega el secreto de sesión una sola vez.
- `POST /api/payers/:id/qr` emite un QR temporal.
- `POST /api/payers/resolve` resuelve el token del QR.
- `POST /api/payers/payment-requests` crea una solicitud sin revelar `approveUrl` al cobrador.
- `GET /api/payers/me/payment-requests` lista solicitudes autenticadas del pagador.
- `POST /api/payers/payment-requests/:requestId/reject` rechaza una solicitud propia.
- `GET /api/payers/payment-requests/:requestId/status` devuelve el estado público usando `X-Status-Token`.
  El token se entrega una sola vez al confirmar (`POST /api/agent/confirm` o
  `POST /api/payers/payment-requests`), se almacena únicamente como hash y no
  debe enviarse en la URL. Su vigencia cubre la solicitud y el margen de
  `PAYMENT_STATUS_TOKEN_FINAL_MARGIN_SECONDS`, aunque la resolución original ya
  haya vencido.

Los QR contienen siempre `glasspay:<token>`. El endpoint
`POST /api/payers/resolve` exige ese prefijo; un token sin prefijo, inexistente
o vencido devuelve el mismo error genérico. Si la sesión del pagador responde
401, `payer-web` borra la sesión local y vuelve a mostrar la pantalla para
crear un perfil.

## Cuenta del cobrador y contactos

Los endpoints que usa el cobrador exigen los headers `X-Collector-Id` y
`X-Collector-Session` (nunca en la URL); una sesión inválida responde 401
genérico. Esto incluye `POST /api/payers/resolve`, `POST /api/agent/interpret`,
`POST /api/agent/confirm`, `POST /api/payers/payment-requests` y
`POST /api/payments`. Cada resolución de QR queda ligada al cobrador que la
creó.

- `POST /api/collectors` crea el cobrador (`{ displayName }`) y entrega el secreto una sola vez.
- `GET /api/collectors/me` devuelve el nombre y la "Actividad en GlassPay (Sandbox)".
- `GET /api/collectors/me/movements` lista los últimos 50 cobros, sin datos de PayPal.
- `GET /api/collectors/me/contacts` lista los contactos.
- `POST /api/collectors/me/contacts` guarda como contacto al pagador de una resolución propia y vigente.
- `DELETE /api/collectors/me/contacts/:contactId` elimina un contacto propio.
- `POST /api/collectors/me/contacts/:contactId/resolution` crea una resolución nueva para cobrarle sin escanear su QR.

## App Android

Desde PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

La app crea la cuenta del cobrador, guarda su secreto cifrado con el Android
Keystore, lee el QR con la cámara, interpreta la frase dictada, muestra la
vista previa y solo crea la solicitud tras la confirmación explícita. "Pago
recibido" aparece únicamente cuando el servidor reporta `pagada`.

## Agente de IA

El servidor usa Gemini para convertir una frase transcrita en una propuesta
estructurada. La clave se configura únicamente en `GEMINI_API_KEY`; la IA no
puede ejecutar cobros ni acceder a la base de datos. El modelo por defecto es `gemini-3.5-flash-lite`, sin configuración de
razonamiento. El timeout por defecto es de 5 segundos (`AI_TIMEOUT_MS`).
Los errores HTTP 429 y 503 tienen un reintento con espera corta antes de usar
el parser determinista de respaldo. Cada intento registra solo modelo, código
HTTP, latencia y motivo del fallo.

Endpoints:

- `POST /api/agent/interpret` recibe `resolutionId` y `text`; devuelve una propuesta o una aclaración.
- `POST /api/agent/confirm` recibe solo `proposalId` y devuelve
  `{ requestId, state, statusToken }`; no devuelve `approveUrl` ni IDs de PayPal.
  El `statusToken` se usa después únicamente en el header `X-Status-Token` de
  `/status`. La resolución sigue siendo de un solo uso y con vigencia de cinco
  minutos.

La evaluación local de 25 frases no consume créditos:

```powershell
cd server
npm run evaluate:agent
```

Para reevaluar solo los casos fallidos y los ambiguos o de ataque:

```powershell
$env:AI_MANUAL_DELAY_MS="15000"
npm run evaluate:agent -- --real --focus
```

La respuesta también se valida determinísticamente: monto y moneda deben estar
respaldados por el texto, y el concepto debe ser identificable. La corrida contra Gemini requiere `GEMINI_API_KEY` y espera 15 segundos entre
frases por defecto. `--real` separa los casos resueltos por Gemini de los del
respaldo y falla si algún caso usa el respaldo por un error del proveedor:

```powershell
$env:AI_MANUAL_DELAY_MS="15000"
npm run evaluate:agent -- --real
```

Para ejecutar las pruebas automatizadas:

```powershell
cd server
npm test
```

## Seguridad

El proyecto usa únicamente PayPal Sandbox. Los secretos deben permanecer en
variables de entorno del servidor y nunca deben añadirse al repositorio.

Consulta `AGENTS.md` para las decisiones, fases y reglas de trabajo del proyecto.
