import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { it } from "node:test";
import { Pool } from "pg";
import { OutboxAppender, streamForProducer, uuidV7 } from "@trainticket/ts-kit";
import { PostgresCustomerServiceRepository } from "./adapters/storage/customer-service-repository.js";
import { CustomerServiceApplication } from "./application/customer-service.js";
import type { EventEnvelope } from "./application/messaging.js";

it("queries related cases and evaluates disjoint bounded batches in PostgreSQL", { skip: !process.env.TEST_DATABASE_URL }, async () => {
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL });
  const client = await pool.connect();
  const second = await pool.connect();
  const schema = `case_test_${randomUUID().replaceAll("-", "")}`;
  try {
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await second.query(`SET search_path TO ${schema}`);
    for (const file of (await readdir("migrations")).filter((name) => name.endsWith(".sql")).sort()) {
      await client.query(await readFile(`migrations/${file}`, "utf8"));
    }
    const repository = new PostgresCustomerServiceRepository(client);
    const publisher = { publish: async (event: EventEnvelope) => {
      await new OutboxAppender(client).append(event, streamForProducer(event.producer));
    } };
    const application = new CustomerServiceApplication(publisher, repository);
    const orderId = `ord-${randomUUID()}`;
    const opened = await application.openSupportCase({
      requesterRef: `tvl-${randomUUID()}`, channel: "APP", description: "Payment status inquiry",
      businessReferences: { journeyOrderId: orderId },
    }, `corr-${uuidV7()}`);
    const matches = await repository.findCasesByReferences([{ businessReferences: { journeyOrderId: orderId } }]);
    assert.deepEqual(matches.map((item) => item.id), [opened.caseId]);
    assert.deepEqual(await repository.findCasesByReferences([]), []);
    assert.deepEqual(await repository.findCasesByReferences([{ businessReferences: { journeyOrderId: `ord-${randomUUID()}` } }]), []);
    await application.assignSupportCase(opened.caseId, { ownerQueue: "support", assignedTo: "op-test" }, "op-test");
    await application.resolveCase(opened.caseId, { summary: "Payment confirmed", resolutionCode: "RESOLVED" }, "op-test");
    const before = await repository.findCase(opened.caseId);
    const evaluated = await application.evaluateEscalationAndSla(opened.caseId, new Date(Date.now() + 86_400_000));
    assert.equal(evaluated.status, "Resolved");
    assert.equal((await repository.findCase(opened.caseId))?.version, before?.version);
    for (let index = 0; index < 105; index++) {
      await application.openSupportCase({
        requesterRef: `tvl-${randomUUID()}`, channel: "APP", description: "Journey inquiry",
      }, `corr-${uuidV7()}`);
    }
    await client.query("BEGIN");
    await second.query("BEGIN");
    const firstBatch = await repository.listOpenCasesForEvaluation();
    const secondBatch = await new PostgresCustomerServiceRepository(second).listOpenCasesForEvaluation();
    assert.equal(firstBatch.length, 100);
    assert.equal(secondBatch.length, 5);
    const firstIds = new Set(firstBatch.map((item) => item.id));
    assert.ok(secondBatch.every((item) => !firstIds.has(item.id)));
  } finally {
    await client.query("ROLLBACK");
    await second.query("ROLLBACK");
    await client.query(`DROP SCHEMA ${schema} CASCADE`);
    client.release();
    second.release();
    await pool.end();
  }
});
