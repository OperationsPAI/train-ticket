import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createSmtpTransport, SmtpChannelGateway } from "./adapters/channel-gateways.js";
import type { NotificationTaskSnapshot } from "./domain.js";

test("configured SMTP pool delivers a concurrent recorded notification burst", {
  skip: !process.env.TEST_SMTP_URL || !process.env.TEST_NOTIFICATION_TASKS,
},async()=>{
  const tasks=JSON.parse(await readFile(process.env.TEST_NOTIFICATION_TASKS!,"utf8")) as NotificationTaskSnapshot[];
  assert.equal(tasks.length,32);
  const transport=createSmtpTransport(process.env.TEST_SMTP_URL!,32);
  try{
    const gateway=new SmtpChannelGateway(transport);
    const results=await Promise.all(tasks.map(task=>gateway.send(task)));
    assert.ok(results.every(result=>result.ok),JSON.stringify(results));
    assert.equal(new Set(results.map(result=>result.ok?result.providerMessageId:undefined)).size,32);
  }finally{transport.close()}
  assert.throws(()=>createSmtpTransport(process.env.TEST_SMTP_URL!,0),/SMTP_MAX_CONNECTIONS/);
});
