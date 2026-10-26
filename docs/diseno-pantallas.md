# Inventario de pantallas para diseño (app Android del cobrador)

Figma: https://www.figma.com/design/a1TicyApwWJkq9qKIN1GEB
Fuente: código en `android/app/src/main/java/com/glasspay/android/` al 2026-10-26.
Objetivo: bocetos de cada pantalla y estado antes de rediseñar la UI. Español, tema oscuro,
alto contraste, texto grande, sin marca PayPal; pensada para evolucionar a lentes (HUD).

## Restricciones que el diseño debe respetar
- "Pago recibido" / "¡Enviado!" solo cuando el servidor lo confirma: el diseño no puede sugerir éxito antes.
- Confirmar un cobro es explícito (botón principal; voz solo "confirmar"/"sí"). Enviar dinero exige vista previa + huella/PIN.
- Montos de saldo ocultos por defecto (ojo). "Por pagar" y vistas previas muestran el monto.
- Ningún texto dice "cancelado" si el pagador aún puede aprobar.
- Todo es PayPal Sandbox: rotular "Actividad en GlassPay (Sandbox)".

## Navegación
Barra inferior: **Inicio · Movimientos · Cobrar · Contactos**. Se oculta mientras hay un cobro en curso.
Capas globales: pantalla de bloqueo, banner "Sin conexión. Revisa tu red.".

## Pantallas y estados

| # | Pantalla | Archivo | Estados a bocetar |
|---|---|---|---|
| 1 | Bienvenida / crear cuenta | `WelcomeScreen.kt` | vacío, creando, "Despertando el servidor…", error de red, sesión inválida |
| 2 | Bloqueo | `MainActivity.kt` (LockScreen) | bloqueada con "Desbloquear"; sin bloqueo configurado (aviso en Inicio) |
| 3 | Inicio | `HomeScreen.kt` | saludo; tarjeta saldo (oculto/visible con ojo animado); Cobrado/Enviado; accesos Cobrar, Enviar, Mi QR; "Recibir envíos" (sin correo / con correo); "Por pagar" (lista, aprobar, rechazar con diálogo); cargando (esqueleto); error |
| 4 | Mi QR | `MyQrScreen.kt` | QR blanco grande, nombre, "Se renueva en Ns", error |
| 5 | Correo para recibir | `PayoutEmailScreen.kt` | vacío, inválido, guardando, guardado |
| 6 | Movimientos (lista) | `MovementsScreen.kt` | cobros y envíos con estado en color, montos ocultos, vacío, cargando, error |
| 7 | Movimiento (detalle) | `MovementsScreen.kt` | cobro / envío, cada estado |
| 8 | Cobrar: escaneo | `ChargeScreen.kt` + `CameraScanner.kt` | cámara con marco, "Apunta al QR de tu amigo", resolviendo, QR propio, QR vencido, reintento |
| 9 | Cobrar: pagador identificado | `ChargeScreen.kt` | tarjeta del pagador, Guardar contacto / En tus contactos, "Dictar cobro", campo de texto |
| 10 | Cobrar: escuchando / interpretando | `ChargeScreen.kt` | micrófono animado, "Entendí: …", "Interpretando…" |
| 11 | Cobrar: aclaración | `ChargeScreen.kt` | pregunta, "Frase acumulada", Responder, Empezar de nuevo |
| 12 | Cobrar: vista previa (recibo) | `ChargeScreen.kt` | monto, concepto, pagador, "Vence en Ns", Confirmar (principal), Cancelar, Volver, Empezar de nuevo; vencida |
| 13 | Cobrar: espera del pago | `PaymentProgress.kt` | línea de tiempo (enviada → aprobando → confirmando con PayPal), Dejar de esperar |
| 14 | Cobrar: resultado | `PaymentProgress.kt` | ¡Pago recibido! (check animado), rechazado, expirado, falló |
| 15 | Contactos | `ContactsScreen.kt` | lista con Cobrar/Enviar/Eliminar (diálogo), vacío, Agregar con QR |
| 16 | Agregar con QR | `AddContactScreen.kt` | cámara, agregado, ya era contacto, QR propio, error |
| 17 | Enviar dinero | `SendMoneyScreen.kt` | elegir contacto, monto + nota, vista previa con cuenta regresiva, autenticando, enviando, ¡Enviado!, falló, sin bloqueo de pantalla, saldo insuficiente, destinatario sin correo |

## Página del pagador (web, opcional)
`payer-web/src/main.jsx`: crear perfil, Mi QR, Por aprobar (Aprobar con PayPal / Rechazar, "Te cobra …"), Pagos realizados, Historial.

## Skills de diseño instaladas (`.claude/skills/`)
`emil-design-eng`, `review-animations` (Emil Kowalski); `compose-animations`, `compose-component-design`,
`compose-state-and-effects`, `compose-performance` (Chris Banes). Se cargan al iniciar una sesión nueva.

## Tokens de color (Figma: colecciones "Color" y, pendiente, "Color · Claro")
Mismos nombres en ambos temas; en Compose serían dos `ColorScheme` (oscuro/claro) con estos valores.

| Token | Oscuro | Claro |
|---|---|---|
| bg/base | #0A0D14 | #F4F6FA |
| bg/surface | #131826 | #FFFFFF |
| bg/raised | #1B2233 | #EEF1F6 |
| line/default | #283044 | #DCE1EA |
| text/primary | #F5F7FB | #0B1220 |
| text/muted | #A3ADC2 | #5B6578 |
| text/on-accent | #00151C | #FFFFFF |
| accent/default | #7FE7FF | #0B6F89 |
| accent/soft | #12303A | #DDF4FA |
| status/success · soft | #63E6A0 · #10301F | #0F7443 · #DDF5E8 |
| status/warning · soft | #FFC56B · #33260F | #9A5B00 · #FFF0D6 |
| status/danger · soft | #FF8A80 · #3A1614 | #C4312A · #FDE3E1 |
| qr/paper | #FFFFFF | #FFFFFF |
| chart/cobrado | #14A3C4 | #0B8FB0 |
| chart/enviado | #9878F0 | #7A56E0 |

Claro verificado: texto/fondo ≥ 4.5:1 en todos los pares (primary 18.7, muted 5.2–5.9, acento sobre blanco y blanco sobre acento ≈ 5.7); gráfica validada con la skill dataviz sobre #FFFFFF (banda de luminosidad, daltonismo ΔE 12.0, contraste ≥ 3:1).
Regla: la pantalla de escaneo conserva el tema oscuro en ambos temas (va sobre la cámara).
