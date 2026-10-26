# Guion de demo GlassPay (2 minutos)

Demo en vivo con dos teléfonos. Todo es PayPal **Sandbox**: ningún dinero real se mueve.

- **Cobrador:** Samsung SM-A146U con la app GlassPay.
- **Pagador:** segundo teléfono con payer-web abierto y la cuenta PayPal Sandbox Personal a la mano.
- **Servidor:** `https://glasspay-1qdu.onrender.com` (Render, plan gratuito).

## 1. Antes de presentar (checklist)

Render gratis **borra la base de datos** cada vez que el servicio se duerme (15 min sin tráfico), se reinicia o se redespliega (documentación oficial de Render, plan Free). Por eso:

| Cuándo | Qué hacer | Comprobación |
|---|---|---|
| Día anterior | **No desplegar nada** a partir de aquí. Confirmar que el ping externo (sección 4) está activo. | El monitor muestra respuestas 200. |
| 30 min antes | Abrir `https://glasspay-1qdu.onrender.com/health`. | `{"status":"ok"}`. Si tarda ~1 min, estaba dormido y la base se vació: sigue igual, el ensayo la vuelve a llenar. |
| 25 min antes | En el teléfono del pagador, abrir payer-web. Si muestra la pantalla de perfil, crear el perfil "Ana". | Se ve "Mi QR" con el código. |
| 25 min antes | En el Samsung, abrir GlassPay. Si muestra la bienvenida, crear la cuenta con tu nombre. | Se ve "Hola, {nombre}". |
| 20 min antes | **Ensayo real:** escanear el QR de Ana, tocar "Guardar contacto", cobrar 2 o 3 montos pequeños y aprobarlos en PayPal Sandbox. | Inicio muestra el total cobrado; Movimientos muestra los cobros como "Pagado"; Contactos muestra a Ana. |
| 10 min antes | Volumen del Samsung alto, modo No molestar, brillo alto, batería > 50 %, Wi‑Fi estable en ambos teléfonos. Iniciar sesión Sandbox en el navegador del pagador para que PayPal no pida contraseña en vivo. | — |
| 2 min antes | Abrir `/health` otra vez y dejar la app en **Inicio**. | — |

No se siembran datos falsos: todo lo que se ve en Inicio y Movimientos es actividad Sandbox real del ensayo.

## 2. Guion (≈ 2 minutos)

| Tiempo | Qué dices | Qué haces | Qué debe verse |
|---|---|---|---|
| 0:00–0:15 | "GlassPay es una billetera para cobrarle a tus amigos con la voz, pensada para lentes inteligentes. Hoy la ven en el teléfono." | Mostrar **Inicio**. | "Hola, {nombre}" y "Actividad en GlassPay (Sandbox)" con lo cobrado en el ensayo. |
| 0:15–0:30 | "Ana me debe la cena. Ella me muestra su QR: solo lleva un token temporal, sin datos personales." | Ir a **Cobrar** y apuntar al QR del teléfono del pagador. | Vibración breve; "Pagador: Ana" y "En tus contactos". |
| 0:30–0:50 | "Le digo lo que quiero cobrar." | Tocar **"Dictar cobro"** y decir: *"Cóbrale 350 pesos por la cena"*. | "Interpretando…" y luego la **vista previa**: 350 MXN, concepto cena, pagador Ana. La app lo lee en voz alta: "Cobro de 350 MXN por cena. ¿Confirmas?". |
| 0:50–1:00 | "La IA solo propone. Nada se cobra sin mi confirmación." | Decir **"Sí"** (o tocar **"Confirmar"**). | La línea de tiempo: "Solicitud enviada" ✓ y "El pagador está aprobando" con el anillo animado. |
| 1:00–1:25 | "Ahora Ana decide en su teléfono, con PayPal." | En el teléfono del pagador: la solicitud aparece en **"Por aprobar"** → **"Aprobar con PayPal"** → aprobar en Sandbox. | En el Samsung: "Confirmando con PayPal" cuando PayPal registra la aprobación. |
| 1:25–1:40 | "El pago solo se marca recibido cuando PayPal lo confirma por webhook verificado." | Esperar. | Check verde animado, **"¡Pago recibido!"**, vibración y voz. En payer-web la solicitud pasa a **"Pagos realizados"**. |
| 1:40–2:00 | "Queda en mis movimientos, y Ana en mis contactos para cobrarle después sin escanear." | Tocar **"Ver en Movimientos"**; luego mostrar **Contactos**. | El cobro como "Pagado"; Ana en Contactos con "Cobrar". |

Cierre (si sobra tiempo): "Doble confirmación, identidad solo por QR opaco, secretos solo en el servidor y todo en PayPal Sandbox."

## 3. Plan B por punto de fallo

| Falla | Síntoma | Plan B inmediato |
|---|---|---|
| **QR** no se lee | La cámara no reacciona. | Acercar o alejar, subir el brillo del pagador. Si sigue: ir a **Contactos → Ana → "Cobrar"** (no necesita cámara). |
| **Voz** no entiende | "No entendí la frase…" / "No detecté voz…" o aclaración. | Escribir la frase en **"Frase del cobro (opcional)"** y tocar **"Interpretar texto"**. Dictar "**cobra** 350 pesos por la cena" si el reconocedor oye "córrale". |
| **Gemini** caído o lento | Tarda; igual llega la vista previa. | Nada: el servidor usa el analizador de respaldo sin IA. Decirlo como punto fuerte. |
| **Render dormido** | "Despertando el servidor…". | Esperar ~1 min hablando del diseño. Si la base se vació: crear cuenta y perfil de nuevo (30 s) y cobrar por QR. |
| **Sesión inválida** | La app vuelve a la bienvenida con "Tu sesión ya no es válida…". | Crear la cuenta de nuevo; en payer-web, crear el perfil de nuevo si lo pide. |
| **Red** | Mensajes de conexión. | Cambiar al hotspot del otro teléfono. Si no hay red: pasar al video de respaldo. |
| **PayPal** lento | La espera pasa a "Esto está tardando un poco más de lo normal…". | Explicar que la app no inventa estados; si pasa de 1 min, **"Dejar de esperar"** y mostrar que en Movimientos se actualiza solo cuando PayPal confirma. |
| **PayPal pide login** | Formulario de Sandbox en el pagador. | Usar la cuenta Sandbox Personal (credenciales fuera del repositorio). |

## 4. Mantener Render despierto (ping externo)

Render apaga el servicio gratuito tras 15 minutos sin tráfico entrante y, al apagarse, pierde la base SQLite. Un servicio externo de monitoreo que llame a `/health` cada 10 minutos lo mantiene despierto. Un servicio siempre despierto consume ~744 de las 750 horas gratuitas mensuales del workspace, así que solo debe haber **un** Web Service gratuito despierto.

Pasos (con cualquier monitor gratuito, por ejemplo cron-job.org o UptimeRobot; usa tu propia cuenta):

1. Crear un monitor/tarea **HTTP GET** a `https://glasspay-1qdu.onrender.com/health`.
2. Intervalo: **cada 10 minutos** (menos de 15).
3. Activarlo y comprobar en su historial que responde **200**.
4. No poner credenciales ni tokens en la URL del monitor.

Limitaciones: un **redeploy** o un reinicio de Render siguen borrando la base. Por eso no se despliega nada el día de la demo y el ensayo se hace al final.

## 5. Video de respaldo

1. Grabar la pantalla del Samsung (grabador de pantalla de Android) y, con otro teléfono o la cámara de la laptop, ambos teléfonos a la vez.
2. Seguir exactamente el guion de la sección 2 después de un ensayo exitoso.
3. Verificar que se oye la voz de la app ("Cobro de… ¿Confirmas?" y "Pago recibido.").
4. Guardar el video en la laptop y en la nube, listo para reproducir sin internet.
