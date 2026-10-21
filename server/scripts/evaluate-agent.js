const fs = require("node:fs");
const path = require("node:path");
const { createAgentService, deterministicParse } = require("../src/services/agent");

const cases = JSON.parse(fs.readFileSync(
  path.join(__dirname, "..", "test", "fixtures", "agent-cases.json"),
  "utf8",
));
const useRealApi = process.argv.includes("--real");
const delayMs = Number.parseInt(process.env.AI_MANUAL_DELAY_MS || "15000", 10);
const config = {
  provider: useRealApi ? "gemini" : "disabled",
  model: process.env.AI_MODEL || "gemini-2.5-flash-lite",
  apiKey: process.env.GEMINI_API_KEY || "",
  timeoutMs: 8000,
  dailyCallLimit: 100,
};
const service = createAgentService(config);
const context = {
  defaultCurrency: "MXN",
  allowedCurrencies: ["MXN"],
  maxAmount: 1000,
};

function classify(result) {
  if (result.needsClarification) {
    return "clarification";
  }
  if (
    typeof result.amount !== "number"
    || result.amount <= 0
    || result.amount > context.maxAmount
    || result.currency !== "MXN"
    || !result.concept
  ) {
    return "invalid";
  }
  return "valid";
}

async function run() {
  if (useRealApi && !config.apiKey) {
    throw new Error("GEMINI_API_KEY is required for --real");
  }

  let passed = 0;
  for (let index = 0; index < cases.length; index += 1) {
    const testCase = cases[index];
    const result = useRealApi
      ? await service.parseCharge(testCase.text, context)
      : deterministicParse(testCase.text, context);
    const actual = classify(result);
    const accepted = testCase.expected === actual
      || (testCase.expected === "invalid" && actual === "clarification");
    if (accepted) {
      passed += 1;
    }
    console.log(`${accepted ? "PASS" : "FAIL"} ${index + 1}: expected=${testCase.expected} actual=${actual} method=${result.method}`);
    if (useRealApi && index < cases.length - 1) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  console.log(`Resultado: ${passed}/${cases.length} casos aceptados`);
  if (passed !== cases.length) {
    process.exitCode = 1;
  }
}

run().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
