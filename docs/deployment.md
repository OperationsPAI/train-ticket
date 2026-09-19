# Train-Ticket 部署指南

## 快速开始

Helm 是唯一的部署路径。原先 `deploy/` 下的 kustomize 覆盖层已经退役并删除，
所有清单都由 chart `deploy/helm/train-ticket` 渲染。

### 1. 构建并推送镜像

```bash
# 构建所有 39 个服务镜像并推送到 Docker Hub
DOCKER_BUILDKIT=1 skaffold build --tag latest
```

本地 kind 集群走另一条路：`deploy/build-images.sh` 从**渲染后的 release**
（`deploy/render-manifests.sh`）里提取 `train-ticket/<name>:` 镜像列表，
构建后 `kind load` 侧载进节点，因此列表不可能与集群实际拉取的镜像不一致。

### 2. 部署到 K8s 集群

```bash
# 开发环境（单 PG 分片，最小资源）
helm upgrade --install train-ticket deploy/helm/train-ticket \
  --namespace train-ticket --create-namespace --wait

# 开发集群（镜像走 registry + local-path StorageClass）
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-cluster.yaml \
  --namespace train-ticket --create-namespace --wait

# 生产环境（6 PG 分片，充足资源，镜像走代理）
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-prod.yaml \
  --namespace train-ticket-prod --create-namespace --wait
```

用 `upgrade --install` 而不是 `install`：它是幂等的，重复执行安全，也是应用变更的
预期方式。`--wait` 会阻塞到每个 Deployment 都 Available。

kind 集群上一条命令就够：

```bash
make deploy        # 完整流程：构建镜像 + 安装 + 建库校验 + 种子数据 + 冒烟验证
make deploy-fast   # 同上，但跳过 39 个镜像的重建（chart-only 变更用这个）
```

详见 `deploy/README.md`。

### 2b. 部署到真集群（经镜像仓库）

`make deploy` 只服务 kind：它最后一步是 `kind load docker-image`，镜像从不经过仓库，
所以它部署不了这台机器以外的任何集群。真集群走另一条路径，多一个**推镜像**的步骤：

```bash
# 1. 推镜像（9 GB，改清单/values 时不必重推）
make push-images LOCAL_TAG=local IMAGE_TAG=20260913 \
  HELM_VALUES="deploy/helm/values-prod.yaml deploy/helm/values-acr.yaml"

# 2. 部署 + 校验 + 灌种子 + 冒烟
make deploy-acr KCTX=<context> NAMESPACE=train-ticket-prod IMAGE_TAG=20260913
```

`values-acr.yaml` 是环境档案，叠在 `values-prod.yaml` 上，承载四处**目标环境强制**
的差异（文件里逐条写了原因）：

1. **镜像走单个仓库**。`global.imageRepo=trainticket` 把 45 个镜像全部收进一个仓库，
   用 tag 区分（`journey-order-20260913`、`postgres-16-alpine`）。39 个服务各占一个
   仓库的话，公开/私有开关和仓库配额都要按 39 份来配。
2. **infra 镜像一并镜像进去**。不能沿用 `imageRegistry`——`pair-cn-*` 是 docker.io 的
   加速镜像所以 `library/postgres` 能透传，普通仓库不能。把 infra 打回 docker.io 也不行：
   该集群到 `auth.docker.io` 超时，`curlimages/curl`、`jaegertracing/all-in-one`、
   `axllent/mailpit` 全部 ImagePullBackOff（`postgres`/`redis` 能起来只是节点上有缓存）。
   所以 release 只依赖一个可达的仓库。
3. **StorageClass 换 `juicefs-sc`**。`ebs-ssd` 是 Volces VKE 的，这里不存在。
4. **loadgen 从 30 副本降到 3**。30 是给 20 节点压测集群的，首次起来就压满整栈不是
   验证顺序该有的样子。

改动落在 chart 和脚本里：`_helpers.tpl` 的 `train-ticket.image` / `train-ticket.infraImage`、
`deploy/push-images.sh`（新增）、`deploy/helm/values-acr.yaml`（新增），以及
`render-manifests.sh` / `verify-databases.sh` 支持多 `-f` 分层。

**已知限制**：`deploy/seed.sh`、`deploy/smoke.sh` 和整个 `deploy/e2e/` 套件都通过
`kubectl exec` 把 curl 打进一个 pod 来做 HTTP 调用。如果 kubeconfig 指向的是
**路径式反代端点**（如 `https://<ip>:8443/school`）且该代理不转发 `Upgrade`，
exec 和 port-forward 都会以 `Upgrade request required` 失败，而 `get` / `logs`
正常——在这种情况下部署和建库能做，种子数据和冒烟做不了，需要一个到 API server
直连的 kubeconfig。区分方法：

```bash
kubectl -n train-ticket-prod exec deploy/redis -- redis-cli ping   # 通即可
```

### 3. 自定义部署

```bash
# 使用镜像代理
helm upgrade --install train-ticket deploy/helm/train-ticket \
  --set global.imageRegistry=pair-cn-guangzhou.cr.volces.com

# 指定 StorageClass
helm upgrade --install train-ticket deploy/helm/train-ticket \
  --set postgres.storage.storageClass=ebs-ssd
```

### 4. 建库（自动，无需手工步骤）

数据库由 chart hook 创建：`deploy/helm/train-ticket/templates/db-bootstrap.yaml`
按分片渲染出 Job（默认 profile 下是 `postgres-core-bootstrap`），注解为
`helm.sh/hook: pre-upgrade,post-install`，所以每次 `helm upgrade --install` 都会
对运行中的 Postgres 执行一次，缺哪个库补哪个库，失败则整个 release 失败。

为什么是 `pre-upgrade` 而不是 `post-upgrade`：upgrade 的顺序是「应用资源 →
`--wait` 阻塞到全部 Available → post-upgrade hook」。而**新增一个数据库的那次
upgrade，同时也在滚动依赖这个库的服务**——该 Pod 永远无法 Available，`--wait`
会一直阻塞到 `--timeout` 用完，本该建库的 hook 根本轮不到执行，形成死锁。
`pre-upgrade` 没有这个问题：上一版 release 的 Postgres 还在跑，库先建好，服务再
滚上去。`post-install` 覆盖另一种情况——全新安装时 pre-install 阶段还没有服务器
可连，而空 PGDATA 意味着 initdb 已经建好了一切，它只是 `helm uninstall` 之后数据卷
仍然残留这种边角情况的兜底。

`postgres-<shard>-initdb` ConfigMap（挂在 `/docker-entrypoint-initdb.d`）**只在
PGDATA 为空时执行**，chart 挂的是 PVC，所以在已初始化的卷上往
`postgres.instances.<shard>.databases` 里加名字对 `helm upgrade` 是无效的——这就是
`group_booking` 长期缺失的原因。hook 之外还有 `deploy/verify-databases.sh`，它从
渲染后的 release 里读 `@postgres-<shard>:5432/<db>` DSN 逐分片核对，能抓到 hook
结构上抓不到的那一类 bug：DSN 指向了任何分片列表里都没有的库。

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
| PostgreSQL | 关系数据库（6 分片，每片一个 Deployment + Service `postgres-<shard>`） | 热分片 4c/8g |
| Redis | 事件总线 (Streams) + 缓存 | 4c/8g, maxmemory 6GB |
| Jaeger | 分布式链路追踪（Badger 持久卷） | `jaeger.storage.size` / `jaeger.retention.spanStoreTtl` |
| OTel Collector | 遥测收集 | - |
| Mailpit | 邮件模拟 | - |

---

## PG 分库策略

生产环境（`values-prod.yaml`）使用 6 个 PG 分片，按访问模式分组：

```
postgres-hot   ← journey_order, booking_orchestration              (2 库，写密集)
postgres-txn   ← payment, entitlement_ticketing, post_sales...     (5 库，事务链)
postgres-read  ← trip_planning, fare_pricing, offer_management...  (7 库，读密集)
postgres-user  ← account, traveler_profile, identity_verification  (3 库)
postgres-event ← notification, reporting, risk_compliance...       (4 库，事件侧)
postgres-core  ← 其余 17 个库                                       (轻负载)
```

每个分片是独立的 Deployment + Service，名字就是 `postgres-<shard>`。另外还有一个名为
`postgres` 的 Service，但它是指向默认分片（`postgres.defaultInstance`）的
ExternalName 别名，**没有任何 Pod**——所以 `kubectl exec deploy/postgres` 不再可用，
要连数据库请用具体分片，例如 `kubectl exec deploy/postgres-core`。

开发集群 profile（`values-cluster.yaml`）只有一个 `core` 分片，38 个库全在里面。

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
    # 不设 pgInstance → 默认 postgres.defaultInstance，即 postgres-core:5432
```

所有分片 `databases` 的并集必须覆盖每个服务的 `services.<name>.db`；漏一个的表现是
服务能启动、能连上服务器，但第一次查询就失败。`deploy/verify-databases.sh` 就是用来
把这件事变响的。

---

## 压测指南

### Loadgen 配置

集群里实际生效的配置是 `deploy/helm/train-ticket/loadgen-config.yaml`（chart 把它渲染成
`loadgen-config` ConfigMap，并把内容的哈希写进 Pod 注解，所以改完这个文件重新
`helm upgrade` / `make deploy-fast` 会自动滚动 Deployment）。
`deploy/loadgen-go/config.yaml` 只是本地开发样例，不进集群。

```yaml
# deploy/helm/train-ticket/loadgen-config.yaml
run:
  mode: closed-loop                         # 或 open-loop（按 target_rps 定速）
  workers: 50                               # 并发虚拟用户（goroutine）
  think_time_seconds: { min: 0, max: 0 }    # 压测 profile 用零思考时间
staff:
  workers: 60                               # 队列消费侧，需随 workers 同步放大
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
| PG 单实例争用 | 38 库共享 1 PG | → 6 PG 分片（`postgres.instances` + 每服务 `pgInstance`） |
| Redis OOM | maxmemory 512MB + noeviction | → 生产 6GB maxmemory / 8Gi limit（kind 3GB / 4Gi） |
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
kubectl -n train-ticket-prod exec deploy/redis -- redis-cli XINFO GROUPS events:journey-order

# 清理 PG 表膨胀（紧急）。注意要指定具体分片：`postgres` 是 ExternalName 别名，
# 没有 Pod，exec 不了。journey_order 在 hot 分片上。
kubectl -n train-ticket-prod exec deploy/postgres-hot -- psql -U trainticket -d journey_order \
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
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-prod.yaml \
  -n train-ticket-prod --wait

# 3. 验证
python3 deploy/stress/oracle-new-services.py   # 15/15 PASS
python3 deploy/stress/auditor.py --scenario deploy/stress/scenarios/s1-rush.yaml  # 6/6 PASS
```
