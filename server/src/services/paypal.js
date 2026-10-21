const { randomUUID } = require("node:crypto");

class PayPalService {
  constructor(config) {
    this.config = config;
    this.accessToken = null;
    this.accessTokenExpiresAt = 0;
  }

  async getAccessToken() {
    if (this.accessToken && Date.now() < this.accessTokenExpiresAt) {
      return this.accessToken;
    }

    if (!this.config.clientId || !this.config.clientSecret) {
      throw new Error("PayPal credentials are not configured");
    }

    const credentials = Buffer
      .from(`${this.config.clientId}:${this.config.clientSecret}`)
      .toString("base64");
    const response = await fetch(`${this.config.baseUrl}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: `Basic ${credentials}`,
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json",
      },
      body: "grant_type=client_credentials",
    });

    const body = await this.parseResponse(response);
    const expiresIn = Number.parseInt(body.expires_in, 10);
    this.accessToken = body.access_token;
    this.accessTokenExpiresAt = Date.now() + Math.max((expiresIn - 60) * 1000, 5000);
    return this.accessToken;
  }

  async request(pathname, options = {}) {
    const token = await this.getAccessToken();
    const response = await fetch(`${this.config.baseUrl}${pathname}`, {
      ...options,
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
        ...(options.headers || {}),
      },
    });
    return this.parseResponse(response);
  }

  async parseResponse(response) {
    const text = await response.text();
    let body = {};
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = {};
      }
    }

    if (!response.ok) {
      throw new Error(`PayPal request failed with status ${response.status}`);
    }
    return body;
  }

  async createOrder({ amount, currency, description, referenceId, returnUrl, cancelUrl }) {
    const body = await this.request("/v2/checkout/orders", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "PayPal-Request-Id": randomUUID(),
        Prefer: "return=representation",
      },
      body: JSON.stringify({
        intent: "CAPTURE",
        purchase_units: [{
          reference_id: referenceId,
          description,
          amount: {
            currency_code: currency,
            value: amount,
          },
        }],
        payment_source: {
          paypal: {
            experience_context: {
              return_url: returnUrl,
              cancel_url: cancelUrl,
              user_action: "PAY_NOW",
            },
          },
        },
      }),
    });

    const approvalLink = (body.links || []).find((link) =>
      link.rel === "payer-action" || link.rel === "approve");
    if (!body.id || !approvalLink?.href) {
      throw new Error("PayPal order did not include an approval link");
    }

    return { orderId: body.id, approvalUrl: approvalLink.href };
  }

  async captureOrder(orderId) {
    return this.request(`/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "PayPal-Request-Id": randomUUID(),
      },
    });
  }

  async showOrder(orderId) {
    return this.request(`/v2/checkout/orders/${encodeURIComponent(orderId)}`);
  }

  async verifyWebhookSignature(headers, event) {
    if (!this.config.webhookId) {
      throw new Error("PAYPAL_WEBHOOK_ID is not configured");
    }

    const requiredHeaders = [
      "paypal-auth-algo",
      "paypal-cert-url",
      "paypal-transmission-id",
      "paypal-transmission-sig",
      "paypal-transmission-time",
    ];
    if (requiredHeaders.some((name) => !headers[name])) {
      return false;
    }

    const body = await this.request("/v1/notifications/verify-webhook-signature", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        auth_algo: headers["paypal-auth-algo"],
        cert_url: headers["paypal-cert-url"],
        transmission_id: headers["paypal-transmission-id"],
        transmission_sig: headers["paypal-transmission-sig"],
        transmission_time: headers["paypal-transmission-time"],
        webhook_id: this.config.webhookId,
        webhook_event: event,
      }),
    });

    return body.verification_status === "SUCCESS";
  }
}

module.exports = { PayPalService };
