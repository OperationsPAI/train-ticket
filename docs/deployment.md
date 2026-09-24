# Train Ticket 部署指南

## 当前常驻环境

常驻 Helm release 为 `train-ticket`，命名空间为 `train-ticket`。
当前 revision 283 的运行证据与既有测试结果统一见[当前状态](00-current-status.md)。
全部待办统一见 [TODO](TODO.md)。
本文中的构建、部署和测试入口仅供新环境使用，或者在后续明确开展更新并验证前提后使用。
本次文档收尾不执行构建、部署或新增测试。

禁止对当前 resident279 执行默认 `make deploy`、`make deploy-fast`、其依赖部署目标或缺少完整配置的 Helm upgrade。
Makefile 默认使用 `IMAGE_TAG=local` 和单独的 `values-cluster.yaml`，无法据此保留常驻 release 的完整配置与逐服务镜像引用。
`deploy-fast` 仍会应用 Helm 配置、执行种子脚本和冒烟写入，不能作为当前常驻环境的配置更新捷径。

常驻 Skaffold profile 按以下顺序加载配置：

1. [values-cluster.yaml](../deploy/helm/values-cluster.yaml) 指定镜像仓库和存储类型。
2. [values-case.yaml](../deploy/helm/values-case.yaml) 指定集中遥测、stdout 记录和 600 秒负载周期。
3. [values-resident.yaml](../deploy/helm/values-resident.yaml) 启用常驻 exporter 和 loadgen PVC。

[skaffold.yaml](../skaffold.yaml) 使用 `inputDigest` 生成镜像标签，通过 `setValueTemplates` 传入 `services.<name>.image` 和 `loadgen.image`。
chart 的镜像函数优先使用这些完整镜像引用。
后续更新常驻环境时，需要根据已部署的 Helm values、manifest 和镜像 digest 验证更新内容，并保留不属于该次更新的镜像引用。
仓库当前代码与运行中镜像的对应关系见[当前状态](00-current-status.md)。

## 新测试环境的部署入口

Helm chart 位于 [deploy/helm/train-ticket](../deploy/helm/train-ticket)。
服务与镜像清单由 chart 和 Skaffold 文件定义，文档不维护固定镜像数量。
基础工具包括 Docker、kubectl、Helm 3、Bash、Python 3 与 PyYAML。
kind 路径还需要已经创建的独立 kind 集群，Skaffold 路径需要 Skaffold。

### 独立 kind 测试环境

现有 `make deploy` 脚本可用于新建的独立 kind 测试环境。
`deploy/build-images.sh` 根据渲染清单构建 `train-ticket/<service>:<tag>` 并执行 `kind load`。
`values-cluster.yaml` 指向镜像仓库，因此新 kind 环境必须提供与本地加载镜像名称一致的 values 文件。
[部署资产说明](../deploy/README.md#separate-kind-cluster) 给出了该文件的完整最小示例。
以下示例假设已创建 `train-ticket-test` 集群与该环境的 `deploy/helm/values-test.local.yaml`，并已验证默认 StorageClass 和基础设施镜像可用。

```bash
# New, separate kind test environment only; verify prerequisites first.
make deploy KIND_CLUSTER=train-ticket-test KCTX=kind-train-ticket-test \
  NAMESPACE=train-ticket-test IMAGE_TAG=local \
  HELM_VALUES=deploy/helm/values-test.local.yaml
```

该目标依次构建并加载镜像、应用 Helm、检查数据库、重启使用相同标签的业务 Deployment、处理未就绪服务、写入种子数据并执行冒烟验证。
`make deploy-fast` 跳过镜像构建和显式镜像重启阶段，仍执行其余步骤。
各部署阶段通过 Makefile 前置依赖连接，执行后面的目标也会触发前面的步骤。
默认命令的目标命名空间仍是 `train-ticket`，新环境必须显式设置 `KCTX`、`KIND_CLUSTER` 和 `NAMESPACE`。

### 使用镜像仓库的新测试环境

Skaffold `case` profile 使用 `values-cluster.yaml` 和 `values-case.yaml`，通过命名空间区分测试 release。
该 profile 要求集中存储 `clickhouse.telemetry.svc.cluster.local` 已经可用，并验证目标环境的 StorageClass 与镜像仓库访问。

```bash
# New test namespace only; verify context, registry, storage and telemetry first.
skaffold run -p case --kube-context <test-context> \
  -n tt-case-<id> -d <reachable-registry>/<organization>
```

`skaffold build` 仅构建和推送镜像。
部署阶段位于 profile 中，`resident` profile 指向常驻命名空间，不能用于本次文档收尾。
使用 `-b` 限定构建对象不等于保留其他部署对象，后续更新仍需要检查完整渲染结果与已部署镜像引用。

### Production 与 ACR 配置

[values-prod.yaml](../deploy/helm/values-prod.yaml) 定义分片数据库和服务资源配置。
[values-acr.yaml](../deploy/helm/values-acr.yaml) 叠加指定仓库布局与目标环境配置。
现有 `make push-images` 和 `make deploy-acr` 可供经过前提验证的新目标环境使用。

| 入口 | 输入与行为 |
| --- | --- |
| `deploy/build-images.sh` | 构建本地镜像；`LOAD_INTO_KIND=0` 时仅构建。 |
| `make push-images` | 根据 `HELM_VALUES` 渲染目标镜像名称，将 `LOCAL_TAG` 对应的本地镜像推送为 `IMAGE_TAG`。 |
| `make deploy-acr` | 按 `HELM_VALUES_ACR` 的顺序应用配置，再执行数据库校验、服务就绪处理、种子数据和冒烟验证。 |
| `deploy/render-manifests.sh` | 输出指定 values 的渲染结果，供镜像与服务清单检查使用。 |

`global.imageRepo` 非空时，业务镜像使用 `<registry>/<org>/<repo>:<service>-<tag>` 布局。
基础设施镜像由 `train-ticket.infraImage` 根据仓库配置生成。
推送脚本要求本地源镜像存在，使用同一组 values 和标签才能与后续部署对应。

这些 values 文件包含具体环境的存储名称，不能直接视为任意集群的安装配置。
例如 ACR 文件为 PostgreSQL 设置 `juicefs-sc`，为 ClickHouse 和 Prometheus 设置 `ebs-ssd`。
新环境必须验证这些 StorageClass 以及基础设施镜像和 curl 镜像的可用性。
种子脚本和 E2E 脚本使用 `kubectl exec`，API 代理必须支持流式连接升级。
`SMOKE_IMAGE` 仅作用于冒烟探测 Pod，种子脚本的 curl Pod 仍使用脚本中指定的镜像。
相关后续工作统一记录于 [TODO](TODO.md)。

## 数据库与基础设施

每个 `postgres.instances.<name>` 渲染为独立 PostgreSQL Deployment、Service 和 PVC。
业务服务通过 `services.<name>.db` 指定数据库，通过 `pgInstance` 指定分片；缺省分片由 `postgres.defaultInstance` 决定。
`postgres` Service 是默认分片的 ExternalName 别名，没有对应 Pod。
数据库操作需要使用具体分片，例如 `postgres-core`。

| 配置 | 分片 |
| --- | --- |
| chart 默认配置与 cluster profile | `core` |
| production profile | `hot`、`txn`、`read`、`user`、`event`、`core` |

数据库清单必须覆盖渲染后的全部业务 DSN。
[db-bootstrap.yaml](../deploy/helm/train-ticket/templates/db-bootstrap.yaml) 根据各分片的数据库清单生成 `pre-upgrade,post-install` hook。
Job 等待 PostgreSQL 可连接后创建缺失数据库并授予权限，执行期限为 300 秒。
初始化 ConfigMap 仅在 PostgreSQL 数据目录为空时运行。
服务启动时执行自己的 schema migration。
[verify-databases.sh](../deploy/verify-databases.sh) 读取渲染后的数据库引用并逐分片核查。

| 组件 | 当前配置职责 |
| --- | --- |
| PostgreSQL | 持久业务记录、事件 outbox 与消费记录。 |
| Redis Streams | 事件传输；chart 不为 Redis 挂载持久卷，也未启用 AOF。 |
| OTel gateway | 接收服务通过 OTLP 发送的遥测。 |
| OTel agent | 采集 Pod 日志和节点信号。 |
| OTel cluster collector | 采集 Kubernetes 对象、事件及 Prometheus federation 数据。 |
| ClickHouse | 存储日志、trace 和指标；resident 与 case 使用集中实例。 |
| Prometheus | 采集指标并保存本地查询窗口。 |
| Mailpit | 接收模拟邮件的 SMTP 服务。 |

Redis 可连接不能证明事件已经消费，数据库存在不能证明业务流程已经完成。
业务验收需要同时查看客户端记录、业务引用和消费证据。

## 常驻 loadgen 配置与数据

运行配置由 [loadgen-config.yaml](../deploy/helm/train-ticket/loadgen-config.yaml) 渲染为 `loadgen-config` ConfigMap，挂载到 `/etc/loadgen/config.yaml`。
镜像不包含运行配置。
[deploy/loadgen-go/config.yaml](./config.yaml) 是本地开发样例。
修改 ConfigMap 源文件会改变 Pod 的 `checksum/config`，后续 Helm 更新可能替换 loadgen Pod。

| 项目 | 当前常驻值 |
| --- | --- |
| `loadgen.statePersistence.enabled` | `true` |
| `loadgen.statePersistence.claimName` | `loadgen-resident-state` |
| `loadgen.statePersistence.storageClass` | `local-path` |
| Deployment strategy | `Recreate` |
| `run.mode` | `open-loop` |
| `run.target_rps` | `20`，单位为每秒发起的 journey 数。 |
| `target.request_timeout_seconds` | `10` |
| `loadgen.diurnalPeriodSeconds` | `600`，通过 `LOADGEN_DIURNAL_PERIOD_SECONDS` 覆盖文件中的 1800。 |
| 记录输出 | `/data/requests.jsonl` 和 stdout。 |

`closed-loop` 使用 `run.workers` 个客户 worker，吞吐受响应时间、思考时间和会话间隔共同影响。
`open-loop` 根据 `run.target_rps` 和 `run.arrival` 发起 journey，每次到达创建独立 goroutine，`run.workers` 不限制其客户并发。
一个 journey 可以发出多个 HTTP 请求，因此 `target_rps` 不能直接当作 HTTP RPS。
staff 与 scalper 分别使用 `staff.workers` 和 `scalper.workers`。

`/data` 同时保存 registry、操作 checkpoint 和请求记录。
常驻更新必须保留 PVC、记录设置、600 秒周期、目标值 20 与 10 秒超时。
文件轮换仍限制历史记录长度，PVC 不等于完整验收窗口的永久归档。
完整字段、补偿成功与失败状态及恢复行为见 [loadgen README](./README.md)。

## 验证入口的使用范围

`deploy/seed.sh` 创建引用数据并暂停、恢复目标环境的 loadgen。
`make e2e` 也会暂停目标 loadgen，重启用例会替换服务 Pod。
`make smoke` 会创建业务数据并核查 PostgreSQL 持久记录。
这些入口供后续新测试环境验证使用，不能作为当前 resident 的只读状态查询。

既有测试结果与稳定运行窗口记录统一见[当前状态](00-current-status.md)。
全部剩余验收条件和实现工作统一见 [TODO](TODO.md)。
