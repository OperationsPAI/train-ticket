import nodemailer, { type Transporter } from "nodemailer";

import type { NotificationTaskSnapshot } from "../domain.js";
import { DirectSuccessGateway, type DeliveryResult, type NotificationChannelGateway } from "../application/notification-service.js";

export { DirectSuccessGateway };

const SMTP_TIMEOUT_MS = 5_000;
const EMAIL_FROM = "no-reply@train-ticket.local";

export type SmtpTransporter = Pick<Transporter, "sendMail">;

export class SmtpChannelGateway implements NotificationChannelGateway {
  constructor(
    private readonly transporter: SmtpTransporter,
    private readonly fallback: NotificationChannelGateway = new DirectSuccessGateway(),
  ) {}

  async send(task: NotificationTaskSnapshot): Promise<DeliveryResult> {
    if (task.channel !== "EMAIL") {
      return this.fallback.send(task);
    }

    try {
      const result = await withSmtpTimeout(this.transporter.sendMail({
        from: EMAIL_FROM,
        to: task.recipientRef,
        subject: `[train-ticket] ${task.templateCode}`,
        text: renderTextBody(task.variables),
        headers: {
          "X-Notification-Task-Id": task.notificationTaskId,
          "X-Template-Code": task.templateCode,
          "X-Correlation-Id": task.correlationId,
        },
      }));
      return { ok: true, providerMessageId: result.messageId };
    } catch (error) {
      return smtpErrorToDeliveryResult(error);
    }
  }
}

export function notificationChannelGatewayFromEnv(env: Readonly<{ SMTP_URL?: string }> = process.env): NotificationChannelGateway {
  const smtpUrl = env.SMTP_URL?.trim();
  if (!smtpUrl) {
    return new DirectSuccessGateway();
  }
  return new SmtpChannelGateway(createSmtpTransport(smtpUrl), new DirectSuccessGateway());
}

function createSmtpTransport(smtpUrl: string): Transporter {
  const url = new URL(smtpUrl);
  const username = decodeURIComponent(url.username);
  const password = decodeURIComponent(url.password);
  return nodemailer.createTransport({
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

async function withSmtpTimeout<T>(send: Promise<T>): Promise<T> {
  let timeout: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      send,
      new Promise<T>((_resolve, reject) => {
        timeout = setTimeout(() => reject(smtpTimeoutError()), SMTP_TIMEOUT_MS);
      }),
    ]);
  } finally {
    if (timeout !== undefined) {
      clearTimeout(timeout);
    }
  }
}

function smtpTimeoutError(): Error & { code: string } {
  const error = new Error("SMTP delivery timed out");
  return Object.assign(error, { code: "ETIMEDOUT" });
}

function renderTextBody(variables: Readonly<Record<string, string>>): string {
  return Object.entries(variables)
    .map(([key, value]) => `${key}: ${value}`)
    .join("\n");
}

function smtpErrorToDeliveryResult(error: unknown): DeliveryResult {
  const details = smtpErrorDetails(error);
  if (details.responseCode !== undefined) {
    if (details.responseCode >= 500 && details.responseCode <= 599) {
      return {
        ok: false,
        outcome: "Rejected",
        providerCode: details.providerCode,
        providerMessage: details.providerMessage,
      };
    }
    if (details.responseCode >= 400 && details.responseCode <= 499) {
      return {
        ok: false,
        outcome: "Timeout",
        providerCode: details.providerCode,
        providerMessage: details.providerMessage,
      };
    }
  }

  return {
    ok: false,
    outcome: "Timeout",
    providerCode: details.providerCode,
    providerMessage: details.providerMessage,
  };
}

function smtpErrorDetails(error: unknown): Readonly<{
  responseCode?: number;
  providerCode?: string;
  providerMessage?: string;
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
    providerCode: code ?? (responseCode !== undefined ? String(responseCode) : command),
    providerMessage: response,
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
