const dotenv = require("dotenv");
const { createApp } = require("./app");
const { createConfig } = require("./config");
const { createDatabase } = require("./db/database");
const { createAgentService } = require("./services/agent");
const { createAssistantService } = require("./services/assistant");
const { createDailyBudget } = require("./services/gemini");
const { PayPalService } = require("./services/paypal");

dotenv.config();

async function start() {
  const config = createConfig();
  const database = await createDatabase(config.databasePath);
  const paypal = new PayPalService(config.paypal);
  // El cobro por voz y el asistente comparten el tope diario de llamadas a la IA.
  const budget = createDailyBudget(config.agent.dailyCallLimit);
  const agent = createAgentService({ ...config.agent, budget });
  const assistant = createAssistantService({ ...config.agent, budget });
  const app = createApp({ config, database, paypal, agent, assistant });

  app.listen(config.port, () => {
    console.log(`GlassPay server listening on port ${config.port}`);
  });
}

start().catch(() => {
  console.error("GlassPay server failed to start");
  process.exitCode = 1;
});
