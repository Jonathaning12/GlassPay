# GlassPay

Agente de pagos manos libres para una demo de PayPal Sandbox.

## Estructura

- `android/`: app Android del cobrador.
- `server/`: API Express, integración con PayPal Sandbox y futura IA.
- `payer-web/`: interfaz web del pagador.
- `docs/`: documentación del proyecto.

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
- `GET /paypal/return` y `GET /paypal/cancel` para los retornos del pagador.
- `POST /webhooks/paypal` para webhooks verificados e idempotentes.

Configura en `server/.env` los valores de `.env.example`. Nunca coloques
credenciales reales en `.env.example`, el código o Git.

Para crear una orden local:

```powershell
curl.exe -X POST http://localhost:3000/api/payments `
  -H "Content-Type: application/json" `
  -d "{\"amount\":350,\"currency\":\"MXN\",\"concept\":\"Cena\"}"
```

La respuesta esperada es HTTP 201 con `paymentId` y `approveUrl`. El pago
solo pasa a `pagada` después de la aprobación del pagador, la captura y el
webhook verificado de PayPal.

## Seguridad

El proyecto usa únicamente PayPal Sandbox. Los secretos deben permanecer en
variables de entorno del servidor y nunca deben añadirse al repositorio.

Consulta `AGENTS.md` para las decisiones, fases y reglas de trabajo del proyecto.
