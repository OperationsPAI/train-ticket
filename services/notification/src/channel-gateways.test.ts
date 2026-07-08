import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DirectSuccessGateway, SmtpChannelGateway, notificationChannelGatewayFromEnv, type SmtpTransporter } from "./adapters/channel-gateways.js";
import type { DeliveryResult, NotificationChannelGateway } from "./application/notification-service.js";
import type { NotificationTaskSnapshot } from "./domain.js";

function notificationTask(overrides: Partial<NotificationTaskSnapshot> = {}): NotificationTaskSnapshot {
  return {
    notificationTaskId: "nt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    status: "Delivering",
    triggerEventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    triggerEventType: "AccountEmailRequested",
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    recipientRef: "alice@example.test",
    templateCode: "account_email_requested",
    channel: "EMAIL",
    intent: "ACCOUNT_EMAIL_REQUESTED",
    transactionRequired: true,
    variables: { orderId: "ord-123", total: "100" },
    scheduledAt: new Date("2026-01-01T00:00:00.000Z"),
    dispatchedAt: new Date("2026-01-01T00:00:01.000Z"),
    receipts: [],
    ...overrides,
  };
}

describe("notification channel gateways", () => {
  it("maps accepted EMAIL SMTP sends to a successful delivery result", async () => {
    let mail: Parameters<SmtpTransporter["sendMail"]>[0] | undefined;
    const transport: SmtpTransporter = {
      async sendMail(message) {
        mail = message;
        return { messageId: "smtp-message-1" };
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.deepEqual(result, { ok: true, providerMessageId: "smtp-message-1" });
    assert.equal(mail?.from, "no-reply@train-ticket.local");
    assert.equal(mail?.to, "alice@example.test");
    assert.equal(mail?.subject, "[train-ticket] account_email_requested");
    assert.equal(mail?.text, "orderId: ord-123\ntotal: 100");
    assert.deepEqual(mail?.headers, {
      "X-Notification-Task-Id": "nt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      "X-Template-Code": "account_email_requested",
      "X-Correlation-Id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    });
  });

  it("maps connection refusal to Timeout without throwing", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        const error = new Error("connect ECONNREFUSED 127.0.0.1:1025") as Error & { code: string };
        error.code = "ECONNREFUSED";
        throw error;
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.equal(result.ok, false);
    assert.equal(result.ok ? undefined : result.outcome, "Timeout");
    assert.equal(result.ok ? undefined : result.providerCode, "ECONNREFUSED");
  });

  it("maps SMTP 5xx refusals to Rejected", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        const error = new Error("550 mailbox rejected") as Error & { responseCode: number; response: string };
        error.responseCode = 550;
        error.response = "550 mailbox rejected";
        throw error;
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.deepEqual(result, {
      ok: false,
      outcome: "Rejected",
      providerCode: "550",
      providerMessage: "550 mailbox rejected",
    });
  });

  it("maps SMTP 4xx temporary refusals to Timeout", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        const error = new Error("451 try again later") as Error & { responseCode: number; response: string };
        error.responseCode = 451;
        error.response = "451 try again later";
        throw error;
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.deepEqual(result, {
      ok: false,
      outcome: "Timeout",
      providerCode: "451",
      providerMessage: "451 try again later",
    });
  });

  it("delegates non EMAIL channels without touching SMTP", async () => {
    let smtpCalls = 0;
    const transport: SmtpTransporter = {
      async sendMail() {
        smtpCalls += 1;
        return { messageId: "unexpected" };
      },
    };
    const fallback: NotificationChannelGateway = {
      send(task): DeliveryResult {
        return { ok: true, providerMessageId: `fallback-${task.channel}` };
      },
    };

    const result = await new SmtpChannelGateway(transport, fallback).send(notificationTask({ channel: "SMS" }));

    assert.deepEqual(result, { ok: true, providerMessageId: "fallback-SMS" });
    assert.equal(smtpCalls, 0);
  });

  it("falls back to direct success when SMTP_URL is unset", async () => {
    const gateway = notificationChannelGatewayFromEnv({ SMTP_URL: undefined });

    assert.ok(gateway instanceof DirectSuccessGateway);
    assert.deepEqual(await gateway.send(notificationTask()), {
      ok: true,
      providerMessageId: "email-nt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    });
  });
});
