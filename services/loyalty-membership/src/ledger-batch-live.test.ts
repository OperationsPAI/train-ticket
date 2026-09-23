import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile,readdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { PostgresMemberRepository } from "./bootstrap.js";
import { Member,type MemberSnapshot } from "./domain.js";

test("recorded member ledger is persisted in one batch and replayed without duplicates",{skip:!process.env.TEST_DATABASE_URL||!process.env.TEST_MEMBER_SNAPSHOT},async()=>{
  const snapshot=JSON.parse(await readFile(process.env.TEST_MEMBER_SNAPSHOT!,"utf8")) as MemberSnapshot;
  assert.ok(snapshot.ledger.length>10);
  const schema=`ledger_batch_${randomUUID().replaceAll("-","")}`;
  const pool=new Pool({connectionString:process.env.TEST_DATABASE_URL});
  const client=await pool.connect();
  try{
    await client.query(`CREATE SCHEMA ${schema}`);await client.query(`SET search_path TO ${schema}`);
    for(const file of (await readdir("migrations")).filter(file=>file.endsWith(".sql")).sort())await client.query(await readFile(`migrations/${file}`,"utf8"));
    const repository=new PostgresMemberRepository(client);const member=Member.fromSnapshot(snapshot);
    await client.query("BEGIN");await repository.save(member);await client.query("COMMIT");
    await client.query("BEGIN");await repository.save(member);await client.query("COMMIT");
    const rows=await client.query("SELECT entry_id,points,source_event_id FROM points_ledger ORDER BY entry_id");
    assert.equal(rows.rowCount,snapshot.ledger.length);
    for(const entry of snapshot.ledger){const row=rows.rows.find(row=>row.entry_id===entry.entryId);assert.equal(row.points,entry.points);assert.equal(row.source_event_id,entry.sourceFactRef.eventId)}
    const stored=await repository.findByMemberId(snapshot.memberId);assert.ok(stored);assert.equal(stored.redeemablePoints,member.redeemablePoints);
  }finally{await client.query("ROLLBACK");await client.query("SET search_path TO public");await client.query(`DROP SCHEMA ${schema} CASCADE`);client.release();await pool.end()}
});
