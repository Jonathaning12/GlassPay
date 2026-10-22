require("dotenv").config();

const fs = require("node:fs");
const path = require("node:path");
const { createAgentService, deterministicParse } = require("../src/services/agent");

const cases = JSON.parse(fs.readFileSync(
  path.join(__dirname, "..", "test", "fixtures", "agent-cases.json"),
  "utf8",
));
const useRealApi = process.argv.includes("--real");
const delayMs = Number.parseInt(process.env.AI_MANUAL_DELAY_MS || "15000", 10);
const focusOnly = process.argv.includes("--focus");
const selectedCases = cases.filter((testCase, index) => {
  if (!focusOnly) {
    return true;
  }
  const caseNumber = index + 1;
  const isAmbiguous = testCase.expected === "clarification";
  const isAttack = /ignora|instrucciones|contraseña|confirmación|no pidas/i.test(testCase.text);
  return caseNumber === 19 || caseNumber === 21 || isAmbiguous || isAttack;
});
let lastFallback = null;
const config = {
  provider: useRealApi ? "gemini" : "disabled",
  model: process.env.AI_MODEL || "gemini-3.5-flash-lite",
  apiKey: process.env.GEMINI_API_KEY || "",
  timeoutMs: Number.parseInt(process.env.AI_TIMEOUT_MS || "5000", 10),
  dailyCallLimit: 100,
  onProviderFallback: (details) => {
    lastFallback = details;
  },
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
  const methods = { gemini: [], deterministic: [], other: [] };
  for (let index = 0; index < selectedCases.length; index += 1) {
    const testCase = selectedCases[index];
    const caseNumber = cases.indexOf(testCase) + 1;
    const result = useRealApi
      ? await service.parseCharge(testCase.text, context)
      : deterministicParse(testCase.text, context);
    const fallback = lastFallback;
    lastFallback = null;
    const actual = classify(result);
    const accepted = testCase.expected === actual
      || (testCase.expected === "invalid" && actual === "clarification");
    const method = methods[result.method] ? result.method : "other";
    methods[method].push(caseNumber);
    if (accepted) {
      passed += 1;
    }
    const providerFallback = useRealApi && result.method !== "gemini";
    console.log(`${accepted && !providerFallback ? "PASS" : "FAIL"} ${caseNumber}: expected=${testCase.expected} actual=${actual} method=${result.method}${providerFallback ? ` fallback=${fallback?.reason || "provider_error_or_unavailable"}` : ""}`);
    if (providerFallback) {
      console.error(`Caso ${caseNumber}: Gemini no resolvió el caso; el respaldo determinista no se acepta en --real.`);
    }
    if (useRealApi && index < selectedCases.length - 1) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  console.log(`Resultado: ${passed}/${selectedCases.length} casos aceptados`);
  console.log(`Resueltos por Gemini: ${methods.gemini.length} (${methods.gemini.join(", ") || "ninguno"})`);
  console.log(`Resueltos por respaldo: ${methods.deterministic.length} (${methods.deterministic.join(", ") || "ninguno"})`);
  if (methods.other.length) {
    console.log(`Resueltos por otro método: ${methods.other.length} (${methods.other.join(", ")})`);
  }
  if (passed !== selectedCases.length || (useRealApi && methods.deterministic.length > 0)) {
    process.exitCode = 1;
  }
}

run().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
