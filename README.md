# GlassPay

Agente de pagos manos libres para una demo de PayPal Sandbox.

## Estructura

- `android/`: app Android del cobrador.
- `server/`: API Express y futura integración con PayPal e IA.
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

## Seguridad

El proyecto usa únicamente PayPal Sandbox. Los secretos deben permanecer en
variables de entorno del servidor y nunca deben añadirse al repositorio.

Consulta `AGENTS.md` para las decisiones, fases y reglas de trabajo del proyecto.
