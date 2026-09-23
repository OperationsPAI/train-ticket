# 搜索性能验收

该实现已部署至 Helm revision 277。
部署后的综合验收通过，用时 225.475 秒，覆盖资格占用、恢复选择、多人售后和受保护换乘。
完整高峰周期与跨服务压力验收仍在进行。

`PlanIndex` 使用 `segment_ref` 保存唯一 segment，并通过出发日期和出发节点定位候选。
节点索引同时维护 node 到 place 和 place 到 nodes 的映射，搜索时解析节点别名。
节点映射到达或更新后，已有 segment 立即使用当前映射。
segment 的日期或出发节点更新时，索引移除旧位置，并清理空集合。
排序、snapshot 写入、outbox 事务以及请求期限保持现有行为。

## 记录来源

2026 年 9 月 23 日 15:06:18 UTC，从运行集群的 `trip_planning` 数据库读取全部 `plan_segments` 和 `plan_nodes`。
数据库读取使用 `REPEATABLE READ` 只读事务，随后读取运行中的 loadgen 的 `/data/loadgen-state.json` 全部 routes。
记录包含 394838 个 segment、389797 个节点映射和 45816 条不同查询。
9 月 29 日包含 133788 个 segment，9 月 30 日包含 128512 个 segment。
所有验收使用同一份完整记录，没有过滤日期或 routes。

索引记录路径为 `services/trip-planning-rs/.scratch/search-source.json`。
SHA256 为 `d01422d0dd00e3f1c6260cf09d1084730a260f358fe60802ba63e1a53a32dd25`。
该 JSON 保存 `captured_at`、`segments`、`nodes` 和 `routes`。
`segments` 使用 `SegmentEntry` 字段，`nodes` 使用 `node_id`、`place_id`，`routes` 使用 loadgen 原始字段。

并发请求的旅客引用来自 `.scratch/runtime-fixes/goal-current-registry.json` 中真实 purchase 的 `traveler` 字段。
该文件 SHA256 为 `9aed90c886dc93192c6beac829a59972eb4d6f945f94011cff8baa98da890424`。
选中的旅客为 `tvl-01a0c898-7d94-7f81-bc8e-571f23707962`，来源订单为 `ord-01a0c89a-d9ae-7fc3-b204-30ad8c057e8f`。
同一真实旅客查询所有记录中的 routes，每个请求人数为 1。

## 定位证据

revision275 的 14:56:34 至 15:01:34 UTC 记录位于 `.scratch/runtime-fixes/goal-rev275-five-minute-window.json`。
其中普通 `search` 有 2309 次无 HTTP 响应和 151 次 503。
运行服务在 15:04:34 UTC 仍记录 2.0 至 2.8 秒的连接获取等待。
15:05 UTC 的 PG 样本已经恢复，不能用该样本判定高峰不存在问题。

`api.rs` 在第一个数据库等待之前同步执行候选查找、构造和排序。
基线按日期遍历全部 segment，完整记录中每次查询实际匹配平均 2.816 个候选，最多 8 个。
查找占查找和排序总耗时的约 99.6%。
snapshot 每个结果执行一次 INSERT，之后 outbox 执行 BEGIN、INSERT、COMMIT。
本次单一修复针对已测得最重的候选查找阶段。

## 相同数据的测量结果

CPU 测试使用 release 构建并固定在 CPU 0、1，顺序执行全部 45816 条查询。
全部候选、分数和排序的 SHA256 与修改前保存的结果逐条一致。

| 指标 | 修改前 | 修改后 |
| :--- | ---: | ---: |
| 候选查找平均 | 2218.970 微秒 | 31.370 微秒 |
| 候选查找 p50 | 286.228 微秒 | 28.874 微秒 |
| 候选查找 p95 | 635.173 微秒 | 46.938 微秒 |
| 候选查找 p99 | 44980.653 微秒 | 61.555 微秒 |
| 排序平均 | 9.537 微秒 | 5.322 微秒 |
| 排序 p99 | 44.854 微秒 | 10.720 微秒 |

并发测试经过实际 Axum router 和 PostgreSQL 16，使用 2 个 Tokio worker、128 个并发任务、10 个 PG 连接、5 秒连接获取期限和 10 秒请求期限。
前后分别使用本地空数据库 `trip_search_peak_before` 和 `trip_search_peak_after`，执行服务自身迁移和真实 snapshot、outbox 写入。
测试进程固定在 CPU 0、1。
这是固定并发的本地服务验收，没有重放整个生产环境的开放负载曲线和其他服务活动。

| 指标 | 修改前 | 修改后 |
| :--- | ---: | ---: |
| 请求数 | 45816 | 45816 |
| HTTP 200 | 45816 | 45816 |
| 请求平均 | 246.638 毫秒 | 65.887 毫秒 |
| 请求 p50 | 96.405 毫秒 | 67.689 毫秒 |
| 请求 p95 | 959.862 毫秒 | 99.488 毫秒 |
| 请求 p99 | 3210.077 毫秒 | 113.911 毫秒 |
| 请求最大 | 5950.181 毫秒 | 156.509 毫秒 |
| 全部请求耗时 | 88.711 秒 | 23.784 秒 |
| outbox 行数 | 45816 | 45816 |
| snapshot 行数 | 129036 | 129036 |

45816 个完整 HTTP JSON 响应的 SHA256 逐条一致。
本地 PG 每秒采样记录中的 `idle in transaction / ClientRead` 最大 query age 分别为 56.767 毫秒和 0.889 毫秒。
这些采样分别观察到 27 和 19 个事务内等待分组，采样没有覆盖每次事务的全部时间。
基线在本地复现了请求长尾，没有复现线上 10 秒超时，因此这些结果不能作为部署后的线上高峰验收。
内存核对中，包含完整 JSON、反序列化记录、索引和结果摘要的测试进程最大 RSS 为 669972 KiB。
新增反向索引需要额外内存，该 RSS 属于测试进程，不能当作线上 pod 的实测值。

原始输出保存在该服务 `.scratch/` 下的 `cpu-before.log`、`cpu-after.log`、`http-before.log`、`http-after.log`、`trip_search_peak_before-pg.jsonl`、`trip_search_peak_after-pg.jsonl` 和 `memory-after.log`。
修改前结果摘要保存在 `before-results.json` 和 `http-before-results.json`。

## 执行命令

下面命令从仓库根目录执行。
并发测试要求 `TEST_DATABASE_URL` 指向专用空数据库，测试会保留数据库内容供核验。

```bash
export TEST_SEARCH_RECORDING="$PWD/services/trip-planning-rs/.scratch/search-source.json"
export TEST_SEARCH_PURCHASES="$PWD/.scratch/runtime-fixes/goal-current-registry.json"
export TEST_SEARCH_EXPECTED="$PWD/services/trip-planning-rs/.scratch/before-results.json"
taskset -c 0,1 cargo test --release --manifest-path services/trip-planning-rs/Cargo.toml --features redis-impl --test search_peak_recorded recorded_search_cpu_and_results -- --ignored --nocapture

export TEST_SEARCH_HTTP_EXPECTED="$PWD/services/trip-planning-rs/.scratch/http-before-results.json"
taskset -c 0,1 cargo test --release --manifest-path services/trip-planning-rs/Cargo.toml --features redis-impl --test search_peak_recorded recorded_search_concurrent_postgres -- --ignored --nocapture

cargo test --release --manifest-path services/trip-planning-rs/Cargo.toml --features redis-impl --test search_peak_recorded recorded_index_updates_match_scan -- --ignored --nocapture

TEST_PLAN_SEGMENTS="$PWD/.scratch/runtime-fixes/goal-plan-segment-source.json" \
TEST_CAPACITY_EVENTS="$PWD/.scratch/runtime-fixes/goal-trip-capacity-filter-events.json" \
TEST_PLAN_EVENTS="$PWD/.scratch/runtime-fixes/goal-trip-plan-filter-events.json" \
cargo test --manifest-path services/trip-planning-rs/Cargo.toml --features redis-impl
```

普通测试执行 20 项并全部通过，包括现有完整记录重建和真实事件过滤测试。
新增 3 项记录测试另外显式执行并全部通过。
现有 2 项通过构造业务数据执行数据库写入的测试保持忽略，本次 PG 验收由真实记录并发测试完成。
重放测试使用完整录制数据检查重复 upsert、节点后到以及节点和 place 两种引用的查询结果与全量扫描一致。
