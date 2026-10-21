const dotenv = require("dotenv");
const { createApp } = require("./app");
const { createConfig } = require("./config");
const { createDatabase } = require("./db/database");
const { createAgentService } = require("./services/agent");
const { PayPalService } = require("./services/paypal");

dotenv.config();

async function start() {
  const config = createConfig();
  const database = await createDatabase(config.databasePath);
  const paypal = new PayPalService(config.paypal);
  const agent = createAgentService(config.agent);
  const app = createApp({ config, database, paypal, agent });

  app.listen(config.port, () => {
    console.log(`GlassPay server listening on port ${config.port}`);
  });
}

start().catch(() => {
  console.error("GlassPay server failed to start");
  process.exitCode = 1;
});
