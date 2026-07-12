export const ACCOUNT_STREAM = "events:account";
export const accountStream = ACCOUNT_STREAM;
export const accountConsumerGroup = "account";
export const maxStreamLength = 100_000;
export const maxDeliveryAttempts = 5;
export const recoveryMinIdleMs = 60_000;
export const pollBlockMs = 2_000;
export const pollCount = 10;

export function accountConsumerName(instanceId = process.env.HOSTNAME ?? process.pid.toString()): string {
  return `account-${instanceId}`;
}
