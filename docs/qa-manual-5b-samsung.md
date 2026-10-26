# Guion de prueba manual 5B: cobrador Samsung SM-A146U (Android 14)

Cobrador: Samsung con la app por USB. Pagador: segundo teléfono con payer-web y
cuenta PayPal Sandbox Personal. Solo Sandbox; el guion no contiene secretos.

## Notas previas (comportamiento real del código)
- La lectura de la vista previa es "Cobro de {monto} {moneda} por {concepto}. ¿Confirmas?", con la moneda como código ("MXN").
- Silencio: según el reconocedor llega el código 6 ("No detecté voz. Intenta de nuevo.") o el 7 ("No entendí la frase. Intenta de nuevo."). Cualquiera de los dos es válido.
- Tras confirmar, el primer estado es "Esperando al pagador." y al llegar la primera consulta cambia a los mensajes de espera: "Confirmando tu pago con PayPal…" (0–4 s), "Preparando tu comprobante…", "Terminando detalles…", "Esto está tardando un poco más de lo normal…" y "Tu pago está en proceso…".
- El campo "Frase del cobro (opcional)" solo se puede editar en Ready, escuchando y en aclaración. En la vista previa está deshabilitado.
- Un error de voz en la vista previa (silencio después de la lectura) descarta la propuesta y muestra el error. Es conocido; ver riesgos en MEMORY.md.

## 0. Preparación
1. En el navegador abre `https://glasspay-1qdu.onrender.com/health`. **Esperado:** `status: "ok"` (la primera vez puede tardar si Render estaba dormido).
2. En el segundo teléfono abre payer-web, crea el perfil y muestra el QR. **Esperado:** QR con cuenta regresiva.
3. Compila e instala desde PowerShell:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
   cd C:\Users\rodri\Documents\GlassPayV2\android
   .\gradlew.bat assembleDebug
   adb devices
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am force-stop com.glasspay.android
   ```
   **Esperado:** `BUILD SUCCESSFUL`, el dispositivo aparece como `device` e `install` responde `Success`.
4. En otra ventana deja corriendo Logcat:
   ```powershell
   adb logcat -c; adb logcat AndroidRuntime:E GlassPay:D *:S
   ```
5. Abre la app a mano y acepta los permisos de cámara y micrófono. **Esperado:** "Busca un QR de GlassPay".
6. Apunta al QR del pagador. **Esperado:** vibración breve sin cierre de la app, "Pagador identificado" y el nombre. Espera unos 5 s a que el TTS esté listo.

## A. Aclaración por texto
1. Escribe "cóbrale por la cena" y pulsa "Interpretar texto". **Esperado:** se muestra y se lee una pregunta de aclaración; aparecen "Frase acumulada: cóbrale por la cena", "Responder", "Empezar de nuevo" y "Escanear otro QR".
2. Reemplaza el texto por "350" y pulsa "Interpretar texto". **Esperado:** "Vista previa" con monto 350, MXN, concepto (cena) y pagador; se lee "Cobro de 350 MXN por cena. ¿Confirmas?".
3. Pulsa "Cancelar". **Esperado:** "Cobro cancelado." en pantalla y en voz.

## B. Aclaración por voz
1. Pulsa "Dictar cobro". **Esperado:** se lee "Dicta el monto y el concepto del cobro." y el micrófono se abre al terminar ("Preparando micrófono…" y luego "Escuchando…").
2. Di "cóbrale por la cena". **Esperado:** "Entendí: cóbrale por la cena" y la pregunta de aclaración.
3. Pulsa "Responder" y di "trescientos cincuenta". **Esperado:** vista previa con monto 350.

## C. "Empezar de nuevo"
1. Llega a la aclaración y pulsa "Empezar de nuevo". **Esperado:** "Dictar cobro" y "Escanear otro QR"; el campo de texto queda vacío.
2. Llega a la aclaración, pulsa "Responder" y di "empezar de nuevo". **Esperado:** igual que el paso 1.
3. Llega a la vista previa y pulsa "Empezar de nuevo". **Esperado:** "Dictar cobro" y ninguna propuesta en pantalla.
4. **Regresión del bug corregido:** justo después del paso 3, pulsa "Dictar cobro". **Esperado:** se pide dictar de nuevo; NO reaparece "Vista previa" ni el monto anterior.
5. Llega a la vista previa, espera a que termine la lectura y di "Empezar de nuevo." **Esperado:** igual que el paso 3.

## D. "Sí." / "No." con puntuación (en la vista previa, después de la lectura)
1. Di "sí, confirmar". **Esperado:** no confirma (la frase no es exacta).
2. En una vista previa nueva di "confirmar por favor". **Esperado:** no confirma.
3. En una vista previa nueva di "No." **Esperado:** "Cobro cancelado."
4. En una vista previa nueva di "Sí." **Esperado:** "Confirmando…", después "Solicitud enviada al pagador." (voz), "Esperando al pagador." y el botón "Dejar de esperar". En el segundo teléfono aparece la solicitud. Continúa con H.

Nota: si un intento no confirma, el estado puede pasar a error de voz (ver la nota previa); en ese caso repite el cobro.

## E. Error de voz por silencio
1. Pulsa "Dictar cobro" y no hables (unos 10 s). **Esperado:** "No detecté voz. Intenta de nuevo." (o "No entendí la frase…") con "Intentar de nuevo" y "Escanear otro QR".
2. Espera 10 s más sin tocar nada. **Esperado:** el micrófono NO se reabre solo y no se vuelve a leer nada (sin reintento automático).
3. Pulsa "Intentar de nuevo". **Esperado:** se lee la instrucción y el micrófono se abre al terminar.

## F. Anti-eco
1. Dicta "cóbrale 350 por la cena". **Esperado:** el micrófono no se abre hasta que termina "Cobro de 350 MXN por cena. ¿Confirmas?". La propia voz del teléfono ("¿Confirmas?") no confirma ni cancela.

## G. Vibración sin crash
1. Revisa Logcat después de leer el QR (paso 0.6) y de recibir el pago (H). **Esperado:** hay vibración y no aparecen `FATAL EXCEPTION` ni `AndroidRuntime`.

## H. Polling hasta "Pago recibido."
1. Con la solicitud del paso D.4, en el segundo teléfono pulsa "Aprobar con PayPal" e inicia sesión con la cuenta Sandbox Personal. **Esperado:** la app pasa por los mensajes de espera.
2. Al completarse el webhook. **Esperado:** "Pago recibido." en verde, vibración y voz "Pago recibido."; queda solo "Escanear otro QR".

## I. "cóbrale 350 por la cena, no, mejor 400" (observar y anotar)
1. Dicta la frase completa de una vez. **Esperado:** la vista previa muestra 400 o el servidor pide aclaración. Lee siempre la vista previa antes de confirmar y nunca confirmes 350 sin revisarlo. Anota qué propuso el servidor (depende del agente de IA).

## J. Entrega B: Volver, Escanear otro QR, Dejar de esperar
1. Escribe "cóbrale 200 por el taxi" y pulsa "Interpretar texto". En la vista previa pulsa "Volver". **Esperado:** "Dictar cobro" y "Escanear otro QR"; el campo conserva "cóbrale 200 por el taxi"; no se lee nada; el micrófono no se abre.
2. Pulsa "Escanear otro QR" (prueba también desde la aclaración y desde un error). **Esperado:** vuelve la cámara ("Busca un QR de GlassPay"). Al leer de nuevo el QR, el campo está vacío y no aparece "Entendí:".
3. Crea una solicitud (confirma) y pulsa "Dejar de esperar". **Esperado:** "Dejaste de seguir esta solicitud. El pagador todavía puede aprobarla desde su teléfono.". Ningún texto dice "cancelado" y la app no habla.
4. En el segundo teléfono aprueba esa solicitud. **Esperado:** payer-web muestra el resultado del pago y la app NO anuncia "Pago recibido." (ya no la sigue).

## K. Propuesta vencida
1. Llega a la vista previa y no toques nada durante 2 min (no hables después de la lectura; si aparece un error de voz, repite y quédate en silencio, o usa el modo texto). **Esperado:** "Vence en …s" baja hasta 0 y luego aparece "La propuesta venció. Dicta el cobro otra vez." con "Dictar cobro" y "Escanear otro QR"; no se crea ninguna solicitud.

## Logcat: qué revisar
- Solo líneas `GlassPay` con tipo de fallo y código HTTP (por ejemplo `interpret failure type=TIMEOUT httpCode=…`).
- Nunca texto dictado, tokens QR, `resolutionId`, `statusToken`, `proposalId` ni cuerpos de respuesta.
- No debe haber líneas `GlassPayVoice` ni `FATAL EXCEPTION`.

## Cierre de 5B
5B se cierra si: dictas la frase, ves y escuchas el recibo, confirmas por voz y la solicitud aparece en el teléfono del pagador; además todos los casos siguientes en PASS (el caso I es de observación).

| Caso | PASS/FAIL | Observaciones |
|---|---|---|
| 0 Preparación | | |
| A Aclaración por texto | | |
| B Aclaración por voz | | |
| C Empezar de nuevo (botón, voz, regresión) | | |
| D "Sí." / "No." con puntuación | | |
| E Silencio sin reintento automático | | |
| F Anti-eco | | |
| G Vibración sin crash | | |
| H Pago recibido | | |
| I 350 → 400 (observar) | | |
| J Volver / Escanear otro QR / Dejar de esperar | | |
| K Propuesta vencida | | |
