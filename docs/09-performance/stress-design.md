# 全链路压测设计说明书(核心链路梳理 + 调查分析)

| Field | Value |
|---|---|
| Status | accepted-design |
| Date | 2026-07-10 |
| Owner | orchestrator(用户指令:功能波收完后执行;本文先行约束实现) |
| Scope | 买票/退票核心链路的并发正确性与性能;全链路压测程序设计 |
| Env caveat | kind 单节点、单副本、单 Postgres/Redis——结论用于**不变量验证、趋势、拐点与回归基线**,不代表生产容量 |

## 1. 核心链路梳理

### 1.1 购票链(黄金路径)

同步段(用户可感延迟,逐跳 HTTP):

```
客户端
 → trip-planning    GET  itineraries          (读模型,只读)
 → fare-pricing     POST /fare-quotes         (报价,写)
 → offer-management POST /offers              (冻结报价,写;消费行程/报价投影 → 投影时差退避点)
 → journey-order    POST /journey-orders      (建单;ADR-0003 后前置 identity 核验钩子)
```

异步段(saga,事件驱动,`booking-orchestration` 编排):

```
JourneyOrderCreated → StartBookingSaga → BookingSagaStarted
 步骤1 capacity-availability  占座 hold → confirm   (库存不变量所在)
 步骤2 payment                intent → capture      (ADR-0003 后经 payment-channel SIM 渠道)
 步骤3 entitlement-ticketing  出票                  (ADR-0003 后出站 seat-assignment 分配座位)
 → BookingSagaCompleted → JourneyOrderConfirmed
旁路:provider-integration(供应商确认)、notification、wallet(权益)、
      loyalty(积分,wave C 后)、invoicing(开票资格投影,wave A 后)
```

**争用焦点**(压测靶心):
1. `capacity-availability` 聚合——同车次同日全部并发写都落在同一行/同一聚合(超卖不变量的守卫,也是行锁/OCC 争用之王)。
2. `seat-assignment` SeatMap 分配(wave A 后)——同一编组的座位池并发分配,连座求解加剧持锁时间。
3. `offer-management` 投影时差——高并发下投影滞后放大,in-process 退避(0.5–8s)会转化为长尾延迟。
4. outbox relay 吞吐——每服务单 relay 轮询(250ms),事件洪峰时是排队点。
5. saga 编排器的每步事件往返——Redis Streams 消费组的扇出与 pending 积压。

### 1.2 退票链

```
客户端 → post-sales POST /post-sales-cases (开案,幂等)
       → evaluate(规则/阶梯费,wave B 后带时间阶梯)
       → approve → PostSalesApplied(事件)
 ├─ payment    ProcessRefund → RefundSettled(经 payment-channel 原路退回,wave A 后)
 ├─ journey-order 状态回写(CANCELLED/ADJUSTED)
 ├─ capacity-availability 库存归还(→ waitlist 消费 CapacityReleased 触发候补履约!)
 ├─ seat-assignment 座位回收(wave A 后)
 ├─ invoicing 红冲挂钩(wave A 后:RefundWithoutRedFlushObserved 观测)
 └─ finance-settlement / wallet / notification 旁路
```

**争用焦点**:同单并发退(幂等与状态机竞态)、退改互抢同一订单、库存归还与新购在同一 capacity 聚合上对撞、候补链的连锁放大(一次退票触发 waitlist 全自动购链)。

### 1.3 链路特征小结

| 特征 | 含义 |
|---|---|
| 同步段 4 跳 + 异步段 3 步 saga | 端到端"下单→确认"延迟 = HTTP 段 + 事件段,两段要分开计量 |
| 单聚合热点(capacity/seat) | 吞吐上限由单行写决定,不是 CPU |
| 幂等键全链(UUID-v7) | 重试风暴应零副作用——必须验证而非假设 |
| 事件扇出宽(1 事件 5-8 消费组) | 洪峰时消费组滞后是首个可观测症状 |

## 2. 压测场景设计

| # | 场景 | 负载模型 | 靶点 |
|---|---|---|---|
| S1 | 抢票风暴 | 闭环,N 买家 ≫ K 库存(如 200:50),同车次同日,瞬时并发 | capacity/seat 对撞、waitlist 溢出吸收 |
| S2 | 稳态阶梯 | 开环恒定到达率,购:退:查 ≈ 70:10:20,RPS 阶梯上探至拐点 | 全链延迟曲线、饱和点、outbox/消费组积压 |
| S3 | 退改风暴 | 同批订单并发退 + p% 重复提交 + 退/改同单互抢 | post-sales 状态机、资金守恒、幂等 |
| S4 | 买退交织 | 同一库存池买-退循环 | 库存归还路径、capacity 账平、候补连锁 |
| S5 | 重试风暴 | S2 负载上叠加 p% 客户端重试(同 Idempotency-Key) | 全链幂等单效 |
| S6 | 风暴中重启 | S2 中途滚动重启热点服务(复用 12-restart 机制) | 恢复期间零丢失、DLQ 零、saga 无卡死 |

## 3. 正确性审计器(auditor,比延迟更重要)

每轮压测后自动运行,直查 Postgres/Redis,全部硬断言:

1. **库存守恒**:∀段日:confirmed ≤ capacity;holds+confirmed+released 与事件账本配平;S1 中成功订单数**恰等于** K。
2. **座位唯一**:同车次日期 seatRef 无重复;STANDING 只在满座后出现。
3. **资金守恒**:∀订单:Σcaptures = 订单额;Σrefunds ≤ Σcaptures;payment↔payment-channel↔wallet 三账一致;红冲与退款配平(invoicing 违规观测流为空)。
4. **无卡单**:阈值(如 120s)内全部 saga 达终态;中间态订单数=0;outbox 全排空;**全 DLQ 零**。
5. **幂等单效**:重试组内每键恰一行效果;OCC 冲突计数>0 允许,丢失更新=0。
6. **落败干净**:S1 落败者全部收到契约错误码(非超时/非 5xx 无主),可选进入 waitlist。

## 4. 驱动器与观测

- **驱动器**:`deploy/stress/driver.py`——复用 loadgen 的请求构造代码,改造为:开环到达率控制(asyncio + 令牌桶)、逐请求延迟账本(端点级 p50/p95/p99/max、错误分类)、场景脚本化(S1–S6 各一配置)、结束时输出 JSON 报告供 auditor 合并。
- **观测三件套**:Jaeger 全链 trace(采样拉高)定位跳级延迟;`pg_stat_statements` 找热 SQL;Redis `XLEN`/`XPENDING` 按消费组测积压曲线。
- **辅助对照**:无状态热点服务(fare-pricing/offer)可临时 2-3 副本对照单副本,分离"单聚合瓶颈"与"计算瓶颈"。

## 5. 瓶颈假设与调查预案(执行时逐一证实/证伪)

| 假设 | 证据渠道 | 备选处置 |
|---|---|---|
| capacity 单聚合行锁是吞吐上限 | pg 行锁等待、该端点 p99 陡增 | 按段分片聚合;advisory lock + 短事务;批量确认 |
| OCC 重试风暴放大延迟 | 服务日志冲突计数、重试直方图 | 指数退避调参;热点路径改悲观锁 |
| outbox relay 250ms 轮询限吞吐 | 洪峰时 outbox 表深度曲线 | 批量拉取;通知式唤醒;多 relay 分片 |
| offer 投影时差长尾 | 退避重试计数、p99 vs p50 裂口 | 投影链路加速;报价快照直传 |
| seat 连座求解持锁过长 | seat 端点延迟分布双峰 | 求解移出事务;预分组索引 |
| Redis 消费组扇出滞后 | XPENDING 增长斜率 | 消费者并行度;按聚合分区流 |

## 6. 交付物与执行阶段

1. **阶段 0(本文档)**:设计定稿,合并入库。
2. **阶段 1**:`deploy/stress/`(driver + 6 场景配置 + auditor + README);跑 S2 小流量建立**基线报告** `docs/09-performance/baseline-<date>.md`。
3. **阶段 2**:S1/S3/S4/S5 全量,产出瓶颈调查报告(对照第 5 节假设逐条裁决),修复按波走标准闸。
4. **阶段 3**:S6 + 把"2 分钟压测冒烟+auditor"纳入波级联合闸(此后每波合并都过并发正确性小考)。
5. 执行排期:ADR-0003 全波(A–E)收完后启动;届时以 ADR-0004 形式确认排期与验收线。

## 7. 验收标准

- 六场景全部可复跑,auditor 六类不变量零违例;
- 基线报告含:两链端到端与逐跳延迟分位、拐点 RPS、各假设裁决;
- 至少一轮"发现瓶颈→修复→复测改善"的闭环记录;
- 压测冒烟进入波级闸并稳定运行两波。
