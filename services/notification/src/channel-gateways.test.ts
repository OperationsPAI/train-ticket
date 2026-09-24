import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DirectSuccessGateway, SmtpChannelGateway, notificationChannelGatewayFromEnv, recipientEmailAddress, type SmtpTransporter } from "./adapters/channel-gateways.js";
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

describe("recipientEmailAddress", () => {
  it("keeps literal email addresses and derives a synthetic mailbox from domain refs", () => {
    assert.equal(recipientEmailAddress("alice@example.test"), "alice@example.test");
    assert.equal(
      recipientEmailAddress("tvl-0194f2e0-7b3e-7610-8284-5c26e8b0c222"),
      "tvl-0194f2e0-7b3e-7610-8284-5c26e8b0c222@passengers.train-ticket.local",
    );
  });
});

describe("notification channel gateways", () => {
  it("maps accepted EMAIL SMTP sends to a successful delivery result", async () => {
    let mail: Parameters<SmtpTransporter["sendMail"]>[0] | undefined;
    const transport: SmtpTransporter = {
      async sendMail(message) {
        mail = message;
        return { messageId: "smtp-message-1", accepted: ["alice@example.test"], response: "250 Message accepted" };
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.deepEqual(result, { ok: true, providerMessageId: "smtp-message-1", providerResponse: "250 Message accepted" });
    assert.equal(mail?.from, "no-reply@train-ticket.local");
    assert.equal(mail?.to, "alice@example.test");
    assert.equal(mail?.subject, "[train-ticket] account_email_requested");
    assert.equal(mail?.text, "orderId: ord-123\ntotal: 100");
    assert.match(String(mail?.messageId), /^<[0-9a-f]{64}@notification\.train-ticket\.local>$/);
    assert.deepEqual(mail?.headers, {
      "X-Notification-Task-Id": "nt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      "X-Template-Code": "account_email_requested",
      "X-Correlation-Id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    });
  });

  it("rejects an SMTP result without an accepted recipient", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        return { messageId: "smtp-message-1", accepted: [], rejected: ["alice@example.test"] };
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.deepEqual(result, { ok: false, outcome: "Rejected", providerCode: "NO_ACCEPTED_RECIPIENT", retryable: false });
  });

  it("maps connection refusal to Timeout without throwing", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        const error = new Error("connect ECONNREFUSED 127.0.0.1:1025") as Error & { code: string; errno: string; syscall: string };
        error.code = "ECONNREFUSED";
        error.errno = "ECONNREFUSED";
        error.syscall = "connect";
        throw error;
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.equal(result.ok, false);
    assert.equal(result.ok ? undefined : result.outcome, "Timeout");
    assert.equal(result.ok ? undefined : result.providerCode, "ECONNREFUSED");
    assert.equal(result.ok ? undefined : result.retryable, true);
    assert.match(result.ok ? "" : result.providerMessage!, /Connection refused/);
  });

  it("keeps an interrupted socket without a response code Unknown and not retryable", async () => {
    const transport: SmtpTransporter = {
      async sendMail() {
        throw new Error("socket closed");
      },
    };

    const result = await new SmtpChannelGateway(transport).send(notificationTask());

    assert.equal(result.ok, false);
    assert.equal(result.ok ? undefined : result.outcome, "Unknown");
    assert.equal(result.ok ? undefined : result.providerCode, undefined);
    assert.equal(result.ok ? undefined : result.retryable, false);
    assert.match(result.ok ? "" : result.providerMessage!, /Connection interrupted/);
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
      providerMessage: '{"name":"Error","reason":"SMTP 550 rejected the operation","fingerprint":"8432eaedd95ce4483e282e4f0af2bb9668e023ea8b76fd8e3513d6593a5c657c","responseCode":550}',
      retryable: false,
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
      providerMessage: '{"name":"Error","reason":"SMTP 451 rejected the operation","fingerprint":"d1863106f1d953f1d4862aff5168e3ebd37b120ef77b69bb86599ec625932f49","responseCode":451}',
      retryable: true,
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
