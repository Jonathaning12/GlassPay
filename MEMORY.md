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
- Despliegue previsto: Render; el servidor puede servir la build de `payer-web/`.
- Estado: repositorio Git sin commits; solo existen `AGENTS.md` y `MEMORY.md`.

## 3. Estado por fases

- Fase 1 - Setup: pendiente (2026-10-20).
- Fase 2 - PayPal: pendiente.
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

## 5. Preguntas abiertas

- ¿Qué proveedor/modelo de IA se conectará a la interfaz intercambiable?
- ¿Está disponible el acceso operativo al Meta Wearables Developer Center?
- ¿Cuál será la URL de Render para la demo?

## 6. Endpoints y contratos de datos vigentes

- Aún no definidos. La Fase 1 añadirá `GET /health`.

## 7. Variables de entorno necesarias

- Aún no definidas. Se documentarán en `.env.example` sin valores secretos.

## 8. Cómo correr y probar cada parte

- Aún no definido. Se documentará al completar la Fase 1.

## 9. Problemas conocidos y riesgos

- El Meta Wearables Device Access Toolkit está en vista previa y puede cambiar.
- No se cuenta con lentes físicos; la integración real deberá validarse si cambia el acceso.
- PayPal, webhooks y Render son riesgos principales de integración y demo.

## 10. Registro de cambios

- 2026-10-20: Se registraron decisiones iniciales y respuestas de configuración para comenzar la Fase 1.
