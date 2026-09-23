Booking、Finance、JourneyOrder 和 Offer 的 outbox 清理使用以下谓词及批量。
Java-kit 当前代码、Booking 线上基线 `c5bb352d` 和 Offer 的 TypeScript 实现均已核对。
Booking 补丁 `1e62d420` 不修改清理 SQL。

```sql
DELETE FROM outbox WHERE ctid IN (
  SELECT ctid FROM outbox
  WHERE published_at IS NOT NULL
    AND published_at < now() - interval '30 seconds'
  LIMIT 1000 FOR UPDATE SKIP LOCKED
);
```

新增索引以 `published_at` 为 Btree 键，partial predicate 为 `published_at IS NOT NULL`。
时间截止值作为查询的索引范围条件，不能将随时间变化的 `now()` 写入 partial predicate。
已有 unpublished 索引继续服务发布查询。
本次只新增 migrations、tests 和文档，保留 30 秒 retention、每批 1000 条、每轮一个 batch 及原有调度频率。

| 数据库 | migration 文件 | 执行器使用的 version |
| --- | --- | --- |
| booking_orchestration | `services/booking-orchestration/migrations/004_outbox_retention_index.sql` | `004` |
| finance_settlement | `services/finance-settlement/migrations/008_outbox_retention_index.sql` | `008` |
| journey_order | `services/journey-order/migrations/005_outbox_retention_index.sql` | `005` |
| offer_management | `services/offer-management/migrations/006_outbox_retention_index.sql` | `006_outbox_retention_index` |

四个 migration 文件均为以下内容。
应用执行器使用事务，因此文件保持普通 `CREATE INDEX IF NOT EXISTS`。

```sql
CREATE INDEX IF NOT EXISTS idx_outbox_published_at
  ON outbox (published_at)
  WHERE published_at IS NOT NULL;
```

生产并发建索引已由主代理完成，本任务代理仅执行本地 DDL 和测试。
四份 `goal-{booking,finance,order,offer}-outbox-index-online.log` 均已读取，全部显示 `indisvalid = true`、`indisready = true`。
这四份日志当前均为 `Bitmap Heap Scan` 与 `Bitmap Index Scan on idx_outbox_published_at`，均包含 `published_at` 时间范围的 `Index Cond`，均没有 Seq Scan。
日志展示的是 `Limit` 下的候选选择计划，没有 `LockRows` 或 `Delete` 节点，不能将它们描述为生产完整删除语句的执行计划或实际 buffers 测量。
主代理确认未改写 `schema_migrations`，未改动 Booking 镜像，后续 I/O 变化采样由主代理负责。

| 数据库 | 已核对的在线日志 |
| --- | --- |
| booking_orchestration | `.scratch/runtime-fixes/goal-booking-outbox-index-online.log` |
| finance_settlement | `.scratch/runtime-fixes/goal-finance-outbox-index-online.log` |
| journey_order | `.scratch/runtime-fixes/goal-order-outbox-index-online.log` |
| offer_management | `.scratch/runtime-fixes/goal-offer-outbox-index-online.log` |

Booking 线上镜像基于 `c5bb352d + 1e62d420`，main 上的 accepted supply 未部署，禁止通过部署 main Booking 应用此索引。
已保留 migration 供未来镜像执行。
无需现在写入 `schema_migrations`，未来执行器会通过 `IF NOT EXISTS` 保留已经核实的同名索引并登记上述 version。

执行前，在当前目标数据库核对以下目录信息。
数据库应为表中明确指定的目标，`outbox` 应解析到 `public.outbox`，目标表应为普通表。
同名索引若已存在，必须核对有效性及完整定义，不能仅凭名称跳过。
存在构建任务或影响该表 DDL 的并发维护任务时，由主代理协调执行时间。

```sql
SELECT current_database(), current_user, current_setting('search_path'),
       'outbox'::regclass = 'public.outbox'::regclass AS expected_table;
SELECT oid::regclass, relkind, relpages, reltuples
FROM pg_class WHERE oid = 'public.outbox'::regclass;
SELECT c.oid::regclass, i.indisvalid, i.indisready,
       pg_get_indexdef(c.oid), pg_get_expr(i.indpred, i.indrelid)
FROM pg_class c
LEFT JOIN pg_index i ON i.indexrelid = c.oid
WHERE c.oid = to_regclass('public.idx_outbox_published_at');
SELECT pid, datname, relid, index_relid, command, phase,
       lockers_total, lockers_done, current_locker_pid,
       blocks_total, blocks_done
FROM pg_stat_progress_create_index;
SELECT pid, backend_type, state, wait_event_type, wait_event,
       xact_start, query_start, pg_blocking_pids(pid), left(query, 300)
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND (xact_start IS NOT NULL OR backend_type = 'autovacuum worker');
```

以下四条命令保留为逐库在线 DDL 操作参考，本任务代理未执行。
当前四个生产索引已经创建，无需重复执行这些创建命令。
每次只运行一个数据库的命令，完成有效性检查后再运行下一条。
`PGOPTIONS` 将超时限制配置在连接启动阶段，`psql -c` 只包含一条 DDL，确保 `CONCURRENTLY` 在事务外运行。
不要使用 `BEGIN`、`psql --single-transaction` 或应用 migration 执行器包装这些命令。
两个 timeout 是执行上限，索引构建仍会消耗 I/O、CPU 和额外存储。
保持现有 retention 与业务 workload 不变，并由主代理在每个数据库完成后检查数据库延迟、I/O 和任务进度。

```bash
kubectl -n train-ticket exec deploy/postgres-core -- env 'PGOPTIONS=-c application_name=outbox_retention_online_ddl -c lock_timeout=2s -c statement_timeout=15min' psql -X -v ON_ERROR_STOP=1 -U trainticket -d booking_orchestration -c 'CREATE INDEX CONCURRENTLY idx_outbox_published_at ON public.outbox (published_at) WHERE published_at IS NOT NULL;'
```

```bash
kubectl -n train-ticket exec deploy/postgres-core -- env 'PGOPTIONS=-c application_name=outbox_retention_online_ddl -c lock_timeout=2s -c statement_timeout=15min' psql -X -v ON_ERROR_STOP=1 -U trainticket -d finance_settlement -c 'CREATE INDEX CONCURRENTLY idx_outbox_published_at ON public.outbox (published_at) WHERE published_at IS NOT NULL;'
```

```bash
kubectl -n train-ticket exec deploy/postgres-core -- env 'PGOPTIONS=-c application_name=outbox_retention_online_ddl -c lock_timeout=2s -c statement_timeout=15min' psql -X -v ON_ERROR_STOP=1 -U trainticket -d journey_order -c 'CREATE INDEX CONCURRENTLY idx_outbox_published_at ON public.outbox (published_at) WHERE published_at IS NOT NULL;'
```

```bash
kubectl -n train-ticket exec deploy/postgres-core -- env 'PGOPTIONS=-c application_name=outbox_retention_online_ddl -c lock_timeout=2s -c statement_timeout=15min' psql -X -v ON_ERROR_STOP=1 -U trainticket -d offer_management -c 'CREATE INDEX CONCURRENTLY idx_outbox_published_at ON public.outbox (published_at) WHERE published_at IS NOT NULL;'
```

每次成功后，在该数据库使用只读会话执行以下查询。
`indisvalid`、`indisready` 必须均为 `true`，`amname` 为 `btree`，键为 `published_at`，predicate 为 `(published_at IS NOT NULL)`，键数及总列数均为 1，`indisunique` 为 `false`。
`EXPLAIN` 不带 `ANALYZE`，不会执行删除。
稀疏过期范围应出现包含 `published_at < now() - interval '30 seconds'` 的 `Index Cond`。
小表或大量过期记录可能使优化器选择 Seq Scan，须结合当时统计与候选范围判断，不能强制关闭 `enable_seqscan` 作为验收证据。

```sql
SET statement_timeout = '5s';
SET default_transaction_read_only = on;
SELECT current_database(), i.indisvalid, i.indisready, am.amname,
       pg_get_indexdef(i.indexrelid, 1, true) AS key,
       pg_get_expr(i.indpred, i.indrelid) AS predicate,
       i.indnkeyatts, i.indnatts, i.indisunique,
       pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
JOIN pg_am am ON am.oid = c.relam
WHERE i.indexrelid = 'public.idx_outbox_published_at'::regclass
  AND i.indrelid = 'public.outbox'::regclass;
EXPLAIN (COSTS, FORMAT TEXT)
DELETE FROM public.outbox WHERE ctid IN (
  SELECT ctid FROM public.outbox
  WHERE published_at IS NOT NULL
    AND published_at < now() - interval '30 seconds'
  LIMIT 1000 FOR UPDATE SKIP LOCKED
);
```

如果构建超时或取消，先检查构建后台是否已经结束以及索引状态。
若索引已有效则继续验收，若无同名对象则可以重新运行该数据库的创建命令。
只有确认同名对象属于本次构建、定义匹配、`indisvalid = false` 且没有构建任务后，才在该目标数据库事务外运行以下恢复命令，然后重新创建。
不要对有效索引或其他定义的同名对象执行此命令。
无效索引未清理前，普通 migration 的 `IF NOT EXISTS` 不会修复它。

```sql
DROP INDEX CONCURRENTLY public.idx_outbox_published_at;
```

真实 PostgreSQL 验证使用本地 `127.0.0.1:15432` 的 PostgreSQL 16.15。
源记录在 2026 年 9 月 23 日 15:14:44 至 15:14:47 UTC 从四个数据库各读取一次，使用 `ORDER BY seq DESC LIMIT 12000`，并强制只读会话和 5 秒 statement timeout。
原始记录、采集时间、目录信息及 SHA256 保存在 `.scratch/runtime-fixes/outbox-retention-recordings-20260923/`。
完整 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 保存在 `.scratch/runtime-fixes/outbox-retention-results-20260923/`，仅在本地独立 schema 执行，所有验证删除均回滚。
没有使用 mock、生成业务记录、改写源时间戳或复制记录扩充规模。

```bash
OUTBOX_RETENTION_TEST_DATABASE_URL=postgresql://postgres@127.0.0.1:15432/postgres OUTBOX_RETENTION_RECORDINGS=.scratch/runtime-fixes/outbox-retention-recordings-20260923 OUTBOX_RETENTION_RESULTS=.scratch/runtime-fixes/outbox-retention-results-20260923 python3 -m pytest -q -s -p no:cacheprovider scripts/tests/test_outbox_retention_indexes_live.py
```

测试结果为 `5 passed in 1.46s`。
测试执行全部既有 migration，再验证新增 migration 的事务兼容性、重复执行、本地并发创建、目录有效性以及随后普通 migration 的兼容性。
清理 SQL 与 Java-kit 及 TypeScript 原文、`CLEANUP_BATCH_SIZE = 1_000` 和 `CLEANUP_MAX_BATCHES = 1` 均有断言。
稀疏范围和空范围通过将 `now()` 绑定为由记录原始 `published_at` 推导的固定回放时间验证；谓词中的 30 秒间隔、严格小于、`ctid`、1000 条批量及 `SKIP LOCKED` 完全保留。
另行执行未经修改的 `now()` 清理 SQL，验证完整已过期集合的删除与批量。
测试连接的 `enable_seqscan` 保持 `on`。

| 服务 | 原始记录数 | 未发布记录数 | 稀疏范围删除数 | 边界记录数 | 候选扫描 buffers：Seq Scan / Index Scan | 空范围 buffers：Seq Scan / Index Scan |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Booking | 5956 | 5 | 82 | 26 | 584 / 11 | 584 / 2 |
| Finance | 2303 | 0 | 86 | 26 | 464 / 20 | 464 / 2 |
| JourneyOrder | 2663 | 3 | 95 | 6 | 464 / 31 | 464 / 2 |
| Offer | 784 | 1 | 39 | 1 | 208 / 10 | 208 / 2 |

表中 buffers 为候选扫描节点的 Shared Hit Blocks 与 Shared Read Blocks 之和，包含索引页面访问，不能等同于物理磁盘读取量，也不是整条 DELETE 的 buffers。
四个稀疏范围均由 Seq Scan 改为 `idx_outbox_published_at` 的 Index Scan，返回的索引记录数等于删除数，后续没有过滤非候选记录。
每个服务均对比相同删除集合和全部保留记录，确认恰好处于 30 秒边界及更新记录继续保留。
另一真实连接锁定 7 条过期记录时，清理跳过这些记录，锁释放后这些记录恢复可清理。
完整积压清理前后的批量分别为 Booking `[1000,1000,1000,1000,1000,951]`、Finance `[1000,1000,303]`、JourneyOrder `[1000,1000,660]`、Offer `[783]`。
重复调用清理语句用于验证积压最终集合，不修改每轮一个 batch 的生产预算。
Finance 本次记录没有未发布记录，Offer 本次记录未覆盖超过 1000 条的积压，不能声称这两个样本分别验证了对应场景。
本地原始 `now()` 场景中几乎全部记录已过期，四个服务的优化器均保留 Seq Scan，完整删除集合及批量仍一致。
记录恢复后的本地 heap 为 584、464、464、208 页，没有复现线上历史页面膨胀或业务负载，不能从上述本地结果推断生产延迟改善比例。
rev275 诊断的四张 outbox 表约 31 秒累计 `heap_blks_read` 为 5800696，对应证据见 `.scratch/runtime-fixes/rev275-readonly-diagnosis.md`。

主代理提供的 `.scratch/runtime-fixes/goal-indexed-outbox-deltas.json` 已核对。
采样覆盖 2026 年 9 月 23 日 15:18:02 至 15:18:32 UTC，约 30.22 秒。

| 数据库 | 新增 Seq Scan | heap_blks_read 增量 | 删除记录增量 |
| --- | ---: | ---: | ---: |
| booking_orchestration | 0 | 65731 | 2821 |
| finance_settlement | 0 | 10387 | 807 |
| journey_order | 0 | 33448 | 1558 |
| offer_management | 0 | 11877 | 546 |
| 合计 | 0 | 121443 | 5732 |

该窗口四张 outbox 表新增 Seq Scan 均为零，清理继续删除记录。
`heap_blks_read` 是表级缓存之外的页面读取次数，可能命中操作系统缓存，不能归为纯 retention I/O 或物理磁盘读取量。
本次窗口与 rev275 的负载和时间不同，121443 与 5800696 不能用于计算相同负载下的性能改善比例。

Post Sales 使用相同的 published time 索引，migration 为 `005_outbox_retention_index.sql`。
该索引已在线创建并确认有效，生产候选选择计划使用 Bitmap Index Scan。
真实记录测试使用 1984 条事件，保留 3 条未发布记录以及时间边界，过期记录分为 `[1000, 981]` 两批。
验证结果为 `2 passed, 4 deselected in 0.38s`。
在本地 256 页表上，过期候选使用索引读取 82 条记录，而 DELETE 的外层目标查找仍选择 Hash Semi Join 与顺序扫描。
测试分别检查候选查询与删除目标计划，不将候选索引等同于整条 DELETE 没有顺序扫描。
该样本不改变生产清理预算，也不证明删除目标扫描已在所有数据规模下消除。
