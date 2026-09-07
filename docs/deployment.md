# Train-Ticket 部署指南

## 快速开始

### 1. 构建并推送镜像

```bash
# 构建所有 39 个服务镜像并推送到 Docker Hub
DOCKER_BUILDKIT=1 skaffold build --tag latest
```

### 2. 部署到 K8s 集群

```bash
# 开发环境（单 PG 实例，最小资源）
helm install train-ticket deploy/helm/train-ticket/ \
  --namespace train-ticket --create-namespace

# 生产环境（4 PG 分片，充足资源，镜像走代理）
helm install train-ticket deploy/helm/train-ticket/ \
  -f deploy/helm/values-prod.yaml \
  --namespace train-ticket-prod --create-namespace
```

### 3. 自定义部署

```bash
# 使用镜像代理
helm install train-ticket deploy/helm/train-ticket/ \
  --set global.imageRegistry=pair-cn-guangzhou.cr.volces.com

# 指定 StorageClass
helm install train-ticket deploy/helm/train-ticket/ \
  --set postgres.storage.storageClass=ebs-ssd
```

---

## 架构概览

### 服务清单（39 个微服务）

| 分类 | 服务 | 语言 | 说明 |
|------|------|------|------|
| **核心购票链** | journey-order | Java | 订单聚合 |
| | booking-orchestration | Java | Saga 编排 |
| | trip-planning | Python | 行程搜索 |
| | fare-pricing | Python | 票价计算 |
| | offer-management | Java | 报价管理 |
| | capacity-availability | Rust | 余票管理 |
| **支付链** | payment | Java | 支付意向+扣款 |
| | payment-channel | Java | 支付渠道 |
| | entitlement-ticketing | Rust | 出票 |
| **售后** | post-sales | Java | 退票/改签 |
| | finance-settlement | Java | 结算 |
| **用户** | account | TypeScript | 账户 |
| | traveler-profile | TypeScript | 旅客档案 |
| | identity-verification | Python | 身份验证 |
| **新服务 (Wave B-E)** | loyalty-membership | TypeScript | 会员积分 |
| | travel-insurance | Go | 旅行保险 |
| | group-booking | Java | 团体预订 |
| | corporate-travel | Python | 企业差旅 |
| | marketing-campaign | Java | 营销活动 |
| **其他** | 20 个辅助服务 | 多语言 | 风控/通知/审计等 |

### 基础设施

| 组件 | 说明 | 生产配置 |
|------|------|---------|
| PostgreSQL | 关系数据库（4 分片） | 每实例 4c/8g |
| Redis | 事件总线 (Streams) + 缓存 | 4c/8g, maxmemory 4GB |
| Jaeger | 分布式链路追踪 | - |
| OTel Collector | 遥测收集 | - |
| Mailpit | 邮件模拟 | - |

---

## PG 分库策略

生产环境使用 4 个 PG 实例，按访问模式分组：

```
postgres-hot  ← journey_order, booking_orchestration     (写密集)
postgres-txn  ← payment, entitlement_ticketing, post_sales... (事务链)
postgres-read ← trip_planning, fare_pricing, offer_management... (读密集)
postgres-core ← 其余 24 个库                               (轻负载)
```

在 `values-prod.yaml` 中通过 `pgInstance` 字段配置每个服务的 PG 路由：

```yaml
services:
  journey-order:
    db: journey_order
    pgInstance: hot        # → postgres-hot:5432
  trip-planning:
    db: trip_planning
    pgInstance: read       # → postgres-read:5432
  account:
    db: account
    # 不设 pgInstance → 默认 postgres-core:5432
```

---

## 压测指南

### Loadgen 配置

```yaml
# deploy/k8s/loadgen-config.yaml
run:
  mode: closed-loop                         # 或 open-loop（按 target_rps 定速）
  workers: 200                              # 并发虚拟用户（goroutine）
  think_time_seconds: { min: 0, max: 0.05 } # 压测时减小
staff:
  workers: 40                               # 队列消费侧，需随 workers 同步放大
```

Loadgen 是单进程 Go 程序（`deploy/loadgen-go`），并发来自 goroutine，
没有多进程 fan-out。提高单 pod 吞吐请调 `run.workers`（以及相应的
`staff.workers`），不要再设 `LOADGEN_PROCESSES`——该变量属于已删除的
Python 实现，现在不起任何作用。

### 阶梯压测

```bash
# 逐步增加 loadgen pod 数量，观察延迟拐点
for pods in 1 2 4 8 12 16; do
  kubectl -n train-ticket-prod scale deployment loadgen --replicas=$pods
  sleep 40
  # 收集 RPS + p95 延迟 + PG CPU
done
```

### 单实例基线（2c/6g 服务, 4c/8g PG）

| 负载 | RPS | jo p95 | 瓶颈 |
|------|-----|--------|------|
| 轻 (~50 RPS) | 49 | 13ms | 无 |
| 中 (~150 RPS) | 149 | 90ms | PG 事务排队 |
| 高 (~200 RPS) | 193 | 453ms | PG 饱和 |

### 已知瓶颈及优化

| 瓶颈 | 根因 | 已做优化 |
|------|------|---------|
| Event consumer lag | XREADGROUP BLOCK 2s COUNT 10 | → BLOCK 100ms COUNT 100 (env 可调) |
| PG 表膨胀 | outbox/processed_events 不清理 | → relay 自动清理 (每 20 次 poll) |
| PG 单实例争用 | 38 库共享 1 PG | → 4 PG 分片 |
| Redis OOM | maxmemory 512MB + noeviction | → 4GB maxmemory |
| Loadgen 单进程 | 旧 Python asyncio 单核 ~10 RPS | → 改写为 Go，单进程 goroutine 并发（`run.workers`） |

---

## 运维手册

### 常用命令

```bash
# 查看所有 pod 状态
kubectl -n train-ticket-prod get pods

# 查看服务延迟
kubectl -n train-ticket-prod logs -l app.kubernetes.io/name=loadgen --tail=2 | grep '\[stats\]'

# 查看 PG CPU
kubectl -n train-ticket-prod top pod -l app.kubernetes.io/component=database

# 查看 event consumer lag
kubectl -n train-ticket-prod exec <redis-pod> -- redis-cli XINFO GROUPS events:journey-order

# 清理 PG 表膨胀（紧急）
kubectl -n train-ticket-prod exec <pg-pod> -- psql -U trainticket -d journey_order \
  -c "DELETE FROM outbox WHERE published_at IS NOT NULL; VACUUM;"

# 正确性验证
python3 deploy/stress/auditor.py --scenario deploy/stress/scenarios/s1-rush.yaml
python3 deploy/stress/oracle-new-services.py
```

### 环境变量调优

| 变量 | 默认 | 说明 |
|------|------|------|
| `CONSUMER_THREADS` | 1 | Java 服务 event consumer 线程数 |
| `CONSUMER_BLOCK_MS` | 100 | XREADGROUP 阻塞超时 (ms) |
| `CONSUMER_BATCH_COUNT` | 100 | XREADGROUP 每次读取条数 |
| `OUTBOX_POLL_INTERVAL_MS` | 50 | Outbox relay 轮询间隔 |
| `GOMAXPROCS` | CPU limit | Loadgen Go 运行时并行度（与 cgroup CPU limit 对齐） |
| `PLATFORM_JAVA_KIT_REDIS_ENABLED` | false | Java 服务启用 Redis event subscriber |

### post-sales 特殊配置

post-sales 有自己的 Redis 配置（不使用 platform-kit 的），需要：
```bash
kubectl set env deployment/post-sales \
  SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true \
  PLATFORM_JAVA_KIT_REDIS_ENABLED-  # 删除此变量
```

---

## CI/CD 流程

```bash
# 1. 代码变更后构建
DOCKER_BUILDKIT=1 skaffold build --tag latest

# 2. 部署到生产
helm upgrade train-ticket deploy/helm/train-ticket/ \
  -f deploy/helm/values-prod.yaml \
  -n train-ticket-prod

# 3. 验证
python3 deploy/stress/oracle-new-services.py   # 15/15 PASS
python3 deploy/stress/auditor.py --scenario deploy/stress/scenarios/s1-rush.yaml  # 6/6 PASS
```
