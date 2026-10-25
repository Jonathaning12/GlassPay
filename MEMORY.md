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
- `android/`: Kotlin + Jetpack Compose; app de teléfono para 5A compilable, con integración Meta Wearables DAT pospuesta.
- Despliegue: Render; el servidor puede servir la build de `payer-web/`.
- URL pública: `https://glasspay-1qdu.onrender.com`.
- Estado: Entrega 5.0 implementada localmente; el estado privado usa `statusToken` independiente de la resolución; falta validar el despliegue y la prueba manual antes de pasar a 5A.

## 3. Estado por fases

- Fase 1 - Setup: terminada (2026-10-20); `/health` responde localmente y en Render.
- Fase 2 - PayPal: en curso (2026-10-21); el pago Sandbox y la idempotencia del webhook fueron validados; falta confirmar saldo Business y revisar logs manualmente.
- Fase 3 - QR y página del pagador: en curso (2026-10-21); backend, seguridad, pruebas y frontend local terminados; falta prueba con celular y despliegue.
- Fase 4 - Agente de IA: en curso (2026-10-21); Gemini, fallback determinista, propuestas de un solo uso y evaluación local implementados.
- Fase 5 - App Android: en curso; 5.0 terminada (2026-10-21), 5A compila y está pendiente de prueba en el Samsung SM-A146U.
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
- Android 5A usa AGP 9.4.0 con Kotlin integrado, por lo que no aplica `org.jetbrains.kotlin.android`; el plugin Compose declarado es 2.2.20.
- Android 5A usa Gradle 9.6.0, JDK 25.0.3 mediante JBR de Android Studio, compile/target SDK 36 y min SDK 31. El JDK 17 es el mínimo/default documentado por AGP 9.4, pero el daemon local usa JDK 25 por `gradle/gradle-daemon-jvm.properties`.
- La corrección actual de 5A usa una máquina de estados pura Kotlin en `android/app/src/main/java/com/glasspay/android/ResolveState.kt`; los intentos tienen contador/ID, el `resolutionId` permanece solo en memoria y los callbacks atrasados no cambian el estado actual.

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
- Entrega 5.0: `POST /api/agent/confirm` devuelve `requestId`, `state` y un `statusToken` aleatorio de 256 bits; solo se persiste su hash.
- Entrega 5.0: `GET /api/payers/payment-requests/:requestId/status` exige `X-Status-Token` y devuelve únicamente `state`, `amount`, `currency` y `concept`. El token dura hasta la expiración de la solicitud más `PAYMENT_STATUS_TOKEN_FINAL_MARGIN_SECONDS` (300 s por defecto), aunque la resolución de 5 minutos haya vencido.
- Los nuevos QR codifican `glasspay:<token>`; el servidor acepta temporalmente tokens sin prefijo para compatibilidad. Esta aceptación se eliminará al terminar 5A.
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
- `PAYMENT_STATUS_TOKEN_FINAL_MARGIN_SECONDS`
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
- Entrega 5.0: confirmar una propuesta y conservar el `statusToken` solo en memoria; consultar estado con `curl.exe http://localhost:3000/api/payers/payment-requests/<requestId>/status -H "X-Status-Token: <statusToken>"`; debe devolver solo estado, monto, moneda y concepto. No se debe poner el token en la URL.
- Evaluación local: ejecutar `cd server` y `npm run evaluate:agent`; debe aceptar 25/25 casos usando el parser determinista sin consumir créditos.
- Evaluación manual opcional: configurar `GEMINI_API_KEY` y ejecutar `npm run evaluate:agent -- --real`; usa una pausa configurable entre casos (`AI_MANUAL_DELAY_MS`, 15 segundos por defecto). En modo real, cualquier respaldo por fallo del proveedor hace fallar la evaluación.
- Android: desde PowerShell, configurar `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, ejecutar `cd android; .\gradlew.bat testDebugUnitTest` y `cd android; .\gradlew.bat assembleDebug`. Para ADB, añadir `$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"`. `android/local.properties` apunta al SDK local y no se versiona.

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
- Entrega 5.0: el endpoint de estado usa un `statusToken` independiente en `X-Status-Token`; devuelve el mismo error genérico ante token ausente, inválido, ajeno o vencido y no expone `approveUrl`, IDs de PayPal ni datos del pagador. El límite es por token.
- Entrega 5A: `PaymentApi` hace un `GET /health` silencioso al iniciar con timeout de hasta 60 s; la primera resolución también tolera hasta 60 s y muestra `Despertando el servidor…`. Las siguientes usan timeout normal.
- Entrega 5A: `POST /api/payers/resolve` se ejecuta una vez por intento activo; repetir el mismo token en el servidor no lo consume ni reutiliza la resolución, sino que crea otra resolución independiente (sujeta a rate limit).
- Entrega 5.0: Render/SQLite puede perder datos al reiniciar o dormir; para la demo se debe despertar el servidor y regenerar sesión/QR si la sesión devuelve 401.
- 5C incluirá guion de demo en `docs/`, plan B por QR, voz, red, Render y Gemini, y modo de ensayo por texto sin micrófono. La secuencia honesta de espera es 0–4 s “Confirmando tu pago con PayPal…”, 4–10 s “Preparando tu comprobante…”, 10–20 s “Terminando detalles…”, 20–30 s “Esto está tardando un poco más de lo normal…” y después “Tu pago está en proceso…” con reconsulta. “Pago recibido” solo aparece cuando el estado confirmado es `pagada`. Su cierre exigirá tres ejecuciones consecutivas sin fallos.
- Incidente Android 5A: `gradle-9.6-bin.zip` devolvía 404; AGP exigía `gradle-9.6.0`, que es la URL vigente.
- Incidente Android 5A: AGP 9.x rechazaba `org.jetbrains.kotlin.android`; se eliminó del proyecto porque Kotlin está integrado en AGP.
- Incidente Android 5A: se eliminó `kotlinOptions`; con AGP 9.x la configuración JVM anterior ya no se usa y el proyecto compila con Java 17 en `compileOptions`, aunque el daemon local usa JDK 25.
- Incidente Android 5A: el wrapper estaba incompleto; se generaron `gradlew`, `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar`, que deben permanecer versionados.
- Incidente Android 5A: `gradle-daemon-jvm.properties` contenía URLs de toolchain y `updateDaemonJvm` fallaba porque no hay repositorios configurados. Se dejó `toolchainVersion=25` sin URLs; no ejecutar ni regenerar esa tarea. Si Android Studio regenera el archivo, revisar `git diff`.
- Riesgo Android conocido: el selector “Gradle JVM criteria” del IDE muestra 17, pero el daemon usa JBR/OpenJDK 25.0.3 por el archivo de criterios. Una clonación necesita JBR 25 de Android Studio o ajustar conscientemente esa configuración.
- 5A corregida: el éxito limpia el error anterior; un fallo atrasado no pisa un pagador identificado; HTTP 404 vuelve a `Scanning`; red/timeout conserva el QR y ofrece `Reintentar`; HTTP 429 muestra `Demasiados intentos. Espera unos segundos.`; otros errores muestran mensaje genérico no reintentable.
- 5A corregida: cada cancelación de flujo cancela el `Job` y el `OkHttp Call`; los logs solo incluyen contador de intento, tipo de fallo y código HTTP, nunca token, `resolutionId` ni cuerpo.
- 5A: después de un 404 el token rechazado se ignora mientras siga frente a la cámara; al detectar un QR distinto vuelve a intentarse. El botón `Reintentar` es la vía explícita para red/timeout.

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
- 2026-10-21: Se implementó la Entrega 5.0: estado privado de solicitudes, prefijo `glasspay:`, recuperación de sesión 401 en payer-web y tareas de 5C registradas.
- 2026-10-21: Se separó el test de pagadores y se añadieron casos independientes para header ausente, resolución vencida, resolución ajena, pagador sin resolución y ausencia de datos PayPal; la suite pasó de 16 a 25 tests.
- 2026-10-21: Los tests revelaron que `/status` aceptaba resoluciones vencidas; se corrigió la consulta para exigir una resolución vigente.
- 2026-10-21: Se añadieron tests independientes para `interpret`, creación de solicitudes y `confirm` con resoluciones inexistentes o vencidas; la suite pasó de 25 a 30 tests.
- 2026-10-22: Se sustituyó la autorización de `/status` por `statusToken` de 256 bits almacenado como hash, con margen posterior al vencimiento de la solicitud; se añadió rate limit por token, tests de expiración, tokens ajenos, uso indebido en agente y consultas periódicas.
- 2026-10-22: La suite final quedó en 34/34 tests exitosos, incluyendo la consulta periódica de `/status` durante 30 segundos.
- 2026-10-22: Se añadieron mensajes de espera honestos por tiempo en `/paypal/return` y `payer-web`, más tests independientes para no degradar estados aprobada/pagada y para migrar una base existente; la suite específica quedó en 8/8.
- 2026-10-23: Se creó `.github/copilot-instructions.md` con comandos verificados, arquitectura y convenciones específicas para futuras sesiones de Copilot.
- 2026-10-23: Se aprobó el plan de la Entrega 5A. Android Studio ya está instalado en su última versión; hay laptop, dos pantallas, un móvil Samsung SM-A146U con Android 14 y una segunda pantalla/dispositivo para mostrar el QR. La integración de Meta Glasses queda para el final; primero se prioriza una app funcional con la cámara del teléfono.
- 2026-10-23: Se inició la Entrega 5A con un proyecto Android Compose en `android/`: AGP 9.4.0, Gradle 9.6, Kotlin 2.2.20, JDK 17, compile/target SDK 36 y min SDK 31. Se añadieron CameraX 1.4.2, ML Kit Barcode Scanning bundled 17.3.0 y OkHttp 4.12.0. La app solo acepta QR `glasspay:<token>`, resuelve el pagador mediante `POST /api/payers/resolve`, conserva la resolución en memoria y no incluye todavía Meta DAT. La terminal tiene Java pero no `adb` ni `sdkmanager` en PATH; falta compilar en Android Studio o configurar esas herramientas.
- 2026-10-24: Se registraron las correcciones del primer Sync de Android, el wrapper completo, JDK/daemon real y el estado 5A: compila; pendiente de prueba en dispositivo. No se actualizaron versiones de Gradle, plugins ni dependencias.
- 2026-10-24: Con `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `android\gradlew.bat testDebugUnitTest` terminó `BUILD SUCCESSFUL` y `android\gradlew.bat assembleDebug` terminó `BUILD SUCCESSFUL` (38 tareas up-to-date). 5A sigue pendiente de instalación y prueba en el Samsung.
- 2026-10-24: Se corrigió el estado QR de 5A con máquina pura, callback Compose actualizado, cancelación de OkHttp, health check de arranque en frío, clasificación segura de red/timeout/404/429/servidor y expiración de resolución en memoria. Se añadieron pruebas de reintento, 404, resultado atrasado y deduplicación; `testDebugUnitTest` y `assembleDebug` terminaron `BUILD SUCCESSFUL`. Sin commit ni push.
