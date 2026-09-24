import nodemailer, { type Transporter } from "nodemailer";
import { createHash } from "node:crypto";
import { deliveryErrorEvidence } from "./delivery-errors.js";

import type { NotificationTaskSnapshot } from "../domain.js";
import { DirectSuccessGateway, type DeliveryResult, type NotificationChannelGateway } from "../application/notification-service.js";

export { DirectSuccessGateway };

const SMTP_TIMEOUT_MS = 5_000;
const EMAIL_FROM = "no-reply@train-ticket.local";
// The domain stores no contact email (recipientRef is a traveler/account
// ref), so the demo cluster derives a synthetic mailbox from the ref. This
// keeps mailpit messages searchable by recipient ref.
const SYNTHETIC_RECIPIENT_DOMAIN = "passengers.train-ticket.local";

export function recipientEmailAddress(recipientRef: string): string {
  return recipientRef.includes("@") ? recipientRef : `${recipientRef}@${SYNTHETIC_RECIPIENT_DOMAIN}`;
}

export type SmtpTransporter = Pick<Transporter, "sendMail">;

export function smtpMessageId(taskId: string): string {
  return `<${createHash("sha256").update(taskId).digest("hex")}@notification.train-ticket.local>`;
}

export class SmtpChannelGateway implements NotificationChannelGateway {
  constructor(
    private readonly transporter: SmtpTransporter,
    private readonly fallback: NotificationChannelGateway = new DirectSuccessGateway(),
  ) {}

  async send(task: NotificationTaskSnapshot, lateResult?: (result: DeliveryResult) => Promise<void>): Promise<DeliveryResult> {
    if (task.channel !== "EMAIL") {
      return this.fallback.send(task);
    }

    let timedOut = false;
    const sending: Promise<DeliveryResult> = this.transporter.sendMail({
        from: EMAIL_FROM,
        to: recipientEmailAddress(task.recipientRef),
        subject: `[train-ticket] ${task.templateCode}`,
        text: renderTextBody(task.variables),
        messageId: smtpMessageId(task.notificationTaskId),
        headers: {
          "X-Notification-Task-Id": task.notificationTaskId,
          "X-Template-Code": task.templateCode,
          "X-Correlation-Id": task.correlationId,
        },
      }).then(result => {
        if (!result.accepted?.length) return { ok: false, outcome: "Rejected", providerCode: "NO_ACCEPTED_RECIPIENT", retryable: false } as const;
        return { ok: true, providerMessageId: result.messageId, providerResponse: result.response } as const;
      }, smtpErrorToDeliveryResult);
    const observed = sending.then(async result => {
      if (timedOut && lateResult) await lateResult(result);
      return result;
    });
    // Observe persistence errors even after the caller received Unknown.
    void observed.catch(error => console.error({ component: "notification-smtp", code: "LATE_RESULT_PERSIST_FAILED", error: deliveryErrorEvidence(error) }));
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        observed,
        new Promise<DeliveryResult>(resolve => {
          timer = setTimeout(() => {
            timedOut = true;
            resolve({ ok: false, outcome: "Unknown", providerCode: "SMTP_DEADLINE_EXCEEDED" });
          }, SMTP_TIMEOUT_MS);
        }),
      ]);
    } finally { if (timer) clearTimeout(timer); }
  }
}

export function notificationChannelGatewayFromEnv(env: Readonly<{ SMTP_URL?: string; SMTP_MAX_CONNECTIONS?: string }> = process.env): NotificationChannelGateway {
  const smtpUrl = env.SMTP_URL?.trim();
  if (!smtpUrl) {
    return new DirectSuccessGateway();
  }
  return new SmtpChannelGateway(createSmtpTransport(smtpUrl, Number(env.SMTP_MAX_CONNECTIONS ?? "8")), new DirectSuccessGateway());
}

export function createSmtpTransport(smtpUrl: string, maxConnections = 8): Transporter {
  if (!Number.isInteger(maxConnections) || maxConnections < 1 || maxConnections > 128) {
    throw new Error("SMTP_MAX_CONNECTIONS must be an integer between 1 and 128");
  }
  const url = new URL(smtpUrl);
  const username = decodeURIComponent(url.username);
  const password = decodeURIComponent(url.password);
  return nodemailer.createTransport({
    pool: true,
    maxConnections,
    maxMessages: 1000,
    host: url.hostname,
    port: url.port ? Number.parseInt(url.port, 10) : defaultSmtpPort(url.protocol),
    secure: url.protocol === "smtps:",
    ...(username ? { auth: { user: username, pass: password } } : {}),
    connectionTimeout: SMTP_TIMEOUT_MS,
    socketTimeout: SMTP_TIMEOUT_MS,
  });
}

function defaultSmtpPort(protocol: string): number {
  return protocol === "smtps:" ? 465 : 25;
}

function renderTextBody(variables: Readonly<Record<string, string>>): string {
  return Object.entries(variables)
    .map(([key, value]) => `${key}: ${value}`)
    .join("\n");
}

function smtpErrorToDeliveryResult(error: unknown): DeliveryResult {
  const details = smtpErrorDetails(error);
  // Nodemailer also labels a disconnect after DATA as CONN.
  const knownUnsent = details.connectionRefused || details.providerCode === "EDNS";
  if (details.responseCode !== undefined) {
    if (details.responseCode >= 500 && details.responseCode <= 599) {
      return {
        ok: false,
        outcome: "Rejected",
        providerCode: details.providerCode,
        providerMessage: details.providerMessage,
        retryable: false,
      };
    }
    if (details.responseCode >= 400 && details.responseCode <= 499) {
      return {
        ok: false,
        outcome: "Timeout",
        providerCode: details.providerCode,
        providerMessage: details.providerMessage,
        retryable: true,
      };
    }
  }

  return {
    ok: false,
    outcome: knownUnsent ? "Timeout" : "Unknown",
    providerCode: details.providerCode,
    providerMessage: details.providerMessage,
    retryable: knownUnsent,
  };
}

function smtpErrorDetails(error: unknown): Readonly<{
  responseCode?: number;
  providerCode?: string;
  providerMessage?: string;
  command?: string;
  connectionRefused?: boolean;
}> {
  if (!isRecord(error)) {
    return { providerMessage: "SMTP delivery failed" };
  }

  const code = stringValue(error.code);
  const command = stringValue(error.command);
  const response = stringValue(error.response) ?? stringValue(error.message);
  const responseCode = numberValue(error.responseCode) ?? responseCodeFromResponse(response);

  return {
    responseCode,
    command,
    connectionRefused: error.syscall === "connect" && (error.errno === "ECONNREFUSED" || error.code === "ECONNREFUSED" || /\bECONNREFUSED\b/.test(stringValue(error.message) ?? "")),
    providerCode: code ?? (responseCode !== undefined ? String(responseCode) : command),
    providerMessage: JSON.stringify({ ...deliveryErrorEvidence(error), ...(responseCode ? { reason: `SMTP ${responseCode} rejected the operation` } : {}), responseCode, command: command && /^(CONN|EHLO|HELO|AUTH|MAIL FROM|RCPT TO|DATA|QUIT)$/.test(command) ? command : undefined }),
  };
}

function responseCodeFromResponse(response: string | undefined): number | undefined {
  if (response === undefined) {
    return undefined;
  }
  const match = /^(\d{3})\b/.exec(response.trim());
  if (!match) {
    return undefined;
  }
  const code = Number.parseInt(match[1], 10);
  return Number.isFinite(code) ? code : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}
