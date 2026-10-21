const express = require("express");
const dotenv = require("dotenv");

dotenv.config();

const app = express();
const port = Number.parseInt(process.env.PORT || "3000", 10);

app.use(express.json());

app.get("/health", (_request, response) => {
  response.status(200).json({
    status: "ok",
    service: "glasspay-server",
  });
});

app.listen(port, () => {
  console.log(`GlassPay server listening on port ${port}`);
});

