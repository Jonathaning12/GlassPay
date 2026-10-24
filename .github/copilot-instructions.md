# GlassPay Copilot instructions

## Repository context

GlassPay is a Spanish-language, hands-free payment demo for PayPal Sandbox. It
is a monorepo with an Express API in `server/`, a React/Vite payer UI in
`payer-web/`, an Android placeholder in `android/`, and project documentation
in `docs/`. Read `MEMORY.md` and the relevant parts of `AGENTS.md` before
changing behavior; `MEMORY.md` is the current project state and must be updated
for important decisions, endpoints, dependencies, variables, bugs, or phase
changes.

## Commands

Run commands from the component directory shown:

```powershell
# API
cd server
npm install
npm start                 # production-style local server
npm run dev               # Node watch mode
npm test                  # all Node test files
npm test -- --test-name-pattern="monotonic"  # one matching test
npm run evaluate:agent    # local 25-case deterministic agent evaluation

# Payer web
cd payer-web
npm install
npm run dev
npm run build
npm run preview
```

The server uses Node's built-in test runner (`server/test/*.test.js`); a single
file can be selected with `npm test -- test/agent.test.js`. There is currently
no lint script in either package manifest and no Android Gradle project yet.
For local API development, copy `server/.env.example` to `server/.env`; never
commit or print `server/.env`.

## Architecture and data flow

- `server/src/index.js` loads dotenv, builds validated configuration, opens the
  `sql.js` database, constructs PayPal and agent services, and starts Express.
  `server/src/app.js` composes the routers and applies CORS and JSON parsing.
- The PayPal webhook router is mounted before `express.json()` so
  `POST /webhooks/paypal` retains the raw body for signature verification.
  PayPal calls and AI-provider calls are server-only; clients never receive
  credentials.
- Payment routes and `services/paypal.js` implement PayPal Orders API v2
  Sandbox creation/capture/status behavior. Webhook processing in
  `routes/paypal-webhooks.js` verifies signatures, correlates events to orders,
  captures approved orders, and applies idempotent state transitions.
- `db/database.js` is the persistence boundary. It stores transactions,
  webhook events, payer profiles, QR resolutions, payment requests, and charge
  proposals in SQLite via `sql.js`, writes the exported database file through a
  serialized queue, and exposes monotonic state operations to the routes.
- The payer flow is `payer-web` -> payer/profile and QR endpoints -> a short-lived
  resolution -> an agent proposal -> explicit confirmation -> a payment request.
  The payer UI keeps its session secret in browser `localStorage`, polls pending
  requests every two seconds, and is the only client surface that receives an
  `approveUrl`.
- The cobrador-facing status endpoint uses a separate one-time opaque
  `statusToken` in `X-Status-Token`; it returns only payment state and display
  fields. Android is not implemented yet and should consume this API rather than
  embedding PayPal or Gemini credentials.
- Render deploys the server as a Node web service rooted at `server` and the
  payer UI as a separate static site rooted at `payer-web`; `render.yaml` is the
  deployment reference. The local SQLite file is ephemeral on free Render
  infrastructure.

## Codebase-specific conventions

- Keep the server CommonJS and the payer UI ESM/JSX as currently configured.
  Prefer the existing route/service/database boundaries instead of putting
  PayPal, AI, or SQL logic directly in UI handlers.
- PayPal must remain Sandbox-only: configuration rejects any base URL other
  than HTTPS `api-m.sandbox.paypal.com`. Use the existing configured limits,
  currencies, return/cancel URLs, and idempotency behavior rather than
  duplicating validation.
- Treat AI output as an untrusted proposal. `routes/agent.js` validates the
  normalized result against the original text and server payment rules;
  proposals are short-lived and atomically single-use. Confirmation accepts
  only the proposal ID and loads amount/concept from the database.
- QR values and session/status secrets are opaque random tokens. Store only
  hashes server-side, use constant-time secret checks, and keep tokens out of
  URLs and logs. New QR values use the `glasspay:<token>` prefix; temporary
  unprefixed compatibility is intentional until Android delivery 5A.
- Use generic client errors for invalid, missing, expired, or unauthorized
  tokens. Do not expose approval URLs, PayPal IDs, secrets, hashes, raw
  transcribed text, or model responses to the cobrador or in logs.
- Preserve monotonic payment semantics: only verified PayPal webhooks can make
  a payment `pagada`; duplicate webhook event IDs are ignored; webhook work
  that cannot yet correlate to a transaction returns a retryable response.
- Spanish is the product language, with MXN as the default currency and a
  default payment ceiling of 1,000 MXN. Keep confirmation explicit on both
  sides: the cobrador confirms before request creation and the payer approves
  in PayPal.
- Tests construct the app with injected database/config/fake PayPal or agent
  services. Follow that pattern for deterministic tests; do not spend real AI
  credits or use PayPal production endpoints.
- Before Android work, verify current official Android, Meta Wearables DAT,
  PayPal, Gemini, and Render documentation rather than guessing SDK APIs or
  versions. Follow the phase/approval gates in `AGENTS.md`.
