<div align="center">

# git-arena 后端

**在隔离沙盒里执行真实 Git 命令，把仓库状态读成一份图模型，同时驱动图形视图与终端。**

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white">
  <img alt="JGit" src="https://img.shields.io/badge/JGit-6.10-F05032?style=flat-square&logo=git&logoColor=white">
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white">
  <img alt="MyBatis-Plus" src="https://img.shields.io/badge/MyBatis--Plus-3.5-C71A36?style=flat-square">
  <img alt="Flyway" src="https://img.shields.io/badge/Flyway-migrations-CC0200?style=flat-square&logo=flyway&logoColor=white">
</p>

</div>

## 项目介绍

git-arena 是一个面向 Git 学习和协作训练的 Web 平台。后端负责这个平台里"真的在跑 Git"的那一半：

- 为每个会话创建**隔离的临时仓库**，用 [JGit](https://www.eclipse.org/jgit/) 在其中执行用户输入的 Git 命令。
- 执行后把仓库读成一份 **GitGraph 快照**（提交、分支、标签、HEAD、远程、工作区状态），返回给前端渲染。
- 提供**关卡系统**：按关卡定义建库、给出目标、校验用户操作后的仓库是否达成目标。
- 提供**协作房间**：共享一个裸 origin，每位成员一份克隆，推拉互相可见，并支持 Pull Request 的开启、评审与合并。
- 承载用户体系、关卡进度、积分成就、命令审计与沙盒回收等支撑能力。

命令不经 shell、不拼进程：Git 操作走 JGit 编程式 API，终端里的 `ls`/`cat`/`grep` 等辅助命令由纯 Java NIO 实现。详见 [安全模型](#安全模型)。

## 核心设计

### 一条执行链路

终端输入和图形面板按钮**转成同一种命令请求**，POST 到同一个接口，走同一条执行链路，最后返回同一份状态快照：

```text
终端输入 ─┐
          ├─→ POST /api/command ─→ CommandParser（白名单校验）
面板按钮 ─┘                              │
                                         ▼
                            GitExecutor / SandboxShellExecutor
                                （JGit / 纯 Java 文件操作）
                                         │
                                         ▼
                                    GraphMapper
                              （仓库 → GitGraph 快照）
                                         │
                                         ▼
                        CommandResponse { stdout, stderr, graph, cwd }
```

图形视图和终端因此**不存在两套状态**：谁操作都由后端执行，两边刷新的是同一份快照。

### GitGraph 快照

前后端契约的核心（当前 `version: 2`），由 `domain/graph/GitGraph.java` 定义、`git/GraphMapper.java` 生成：

```jsonc
{
  "version": 2,
  "commits": [
    { "id": "a1b2c3d", "seq": 1, "parents": [], "message": "init",
      "author": "alice", "timestamp": 1700000000, "unreachable": false }
  ],
  "branches": [ { "name": "main", "target": "a1b2c3d", "isRemote": false } ],
  "tags":     [ { "name": "v1.0", "target": "a1b2c3d" } ],
  "head":     { "type": "branch", "ref": "main" },
  "remotes":  [ { "name": "origin", "branches": [ { "name": "main", "target": "a1b2c3d" } ] } ],
  "workingDir": { "staged": ["a.txt"], "modified": [], "untracked": [] }
}
```

几个约定：

- `seq` 是稳定教学序号（C1、C2…），让图比短 hash 更易读。
- `unreachable: true` 是 reset / rebase 之后只有 reflog 能找回的提交，前端画成"幽灵节点"，不参与关卡校验。
- 快照是**只读的**：前端不预测结果，一切以后端执行后返回的新快照为准。
- 结构变更必须前后端同步并升级 `version`。

## 技术栈

| 领域 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.5 |
| Git 引擎 | JGit 6.10（纯 Java，不调用系统 git） |
| 持久化 | PostgreSQL · MyBatis-Plus 3.5 |
| 数据库迁移 | Flyway（启动自动迁移） |
| 缓存 / 会话 | Redis（登录 token、邮箱验证码） |
| 实时通信 | WebSocket + STOMP（SockJS 兼容） |
| 密码哈希 | spring-security-crypto（BCrypt，不引入 Security 过滤链） |
| 邮件 | spring-boot-starter-mail（注册验证码） |
| 其他 | Lombok · Bean Validation |

## 环境要求

- JDK 17 或更高版本
- Maven 3.9+（仓库内含 `.mvn/wrapper`，也可用 `mvnw`）
- PostgreSQL，且已建好空库 `git-arena`
- Redis
- 可选：SMTP 账号（仅注册时的邮箱验证码路径需要）

## 快速开始

**1. 建库**（表结构由 Flyway 在启动时自动创建，不需要手工执行 DDL）

```bash
createdb "git-arena"
```

**2. 配置连接**：默认值写在 `src/main/resources/application.yaml`，可用环境变量覆盖

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/git-arena?stringtype=unspecified"
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=your-password
export SPRING_DATA_REDIS_HOST=localhost
```

> 仓库里的 `application.yaml` 带的是本地开发用的默认值（含数据库口令和邮箱口令）。部署到任何共享环境前请改为通过环境变量或外部配置注入，并轮换这些凭据。

**3. 启动**

```bash
mvn spring-boot:run
```

服务监听 `http://localhost:8096`。启动时会自动执行 Flyway 迁移、灌入官方关卡与成就定义。

**4. 验证**

```bash
# 创建一个沙盒会话
curl -X POST http://localhost:8096/api/sandbox

# 用返回的 sessionId 执行命令
curl -X POST http://localhost:8096/api/command \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<sessionId>","command":"git init"}'
```

前端开发服务器默认跑在 `5173`，已配置把 `/api` 与 `/ws` 代理到 `8096`，见 [`../git-arena-frontend`](../git-arena-frontend)。

## 常用命令

```bash
# 启动开发服务
mvn spring-boot:run

# 运行测试（会连数据库并执行 Flyway 迁移）
mvn test

# 只编译
mvn compile

# 打包可执行 jar
mvn clean package
java -jar target/git-arena-0.0.1-SNAPSHOT.jar
```

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `8096` | 服务端口 |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/git-arena` | 数据库连接；`stringtype=unspecified` 供枚举列写入 |
| `spring.data.redis.host` / `.port` | `localhost` / `6379` | 会话 token 与验证码存储 |
| `spring.flyway.enabled` | `true` | 启动自动迁移，脚本在 `db/migration` |
| `git-arena.maintenance.enabled` | `true` | 定时运维作业总开关；关掉后**沙盒不再回收** |
| `git-arena.maintenance.sandbox-ttl` | `PT24H` | 沙盒台账过期窗口，成员活动即滑动续期 |
| `git-arena.maintenance.session-idle-ttl` | `PT6H` | 单人沙盒内存句柄空闲多久算废弃 |
| `git-arena.maintenance.batch-size` | `200` | 单轮回收扫描上限，避免长事务锁表 |

沙盒目录建在系统临时目录下：单人会话在 `${java.io.tmpdir}/git-arena-sandboxes/`，协作房间在 `${java.io.tmpdir}/git-arena-rooms/`。

## API 概览

所有接口统一包装为 `Result<T>`，业务错误用 `code` 表达而非 HTTP 状态码：

```jsonc
{ "code": 0, "message": "ok", "data": { } }   // code=0 成功，400/500 为错误
```

登录态是不透明 token（存 Redis），前端放在 `Authorization: Bearer <token>`。认证是**可选拦截**：带合法 token 即识别用户，不带则按匿名放行；需要登录的接口自行要求身份。

### 沙盒与命令

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/sandbox` | 创建沙盒会话 |
| `POST` | `/api/sandbox/{sessionId}/reset` | 重置沙盒 |
| `GET` | `/api/sandbox/{sessionId}/graph` | 读取当前 GitGraph 快照 |
| `POST` | `/api/command` | 执行一条命令，返回 stdout / stderr / 新快照 / 当前目录 |

### 认证与进度

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/send-code` | 发送邮箱验证码 |
| `POST` | `/api/auth/register` | 注册（可选邮箱验证路径） |
| `POST` | `/api/auth/login` | 用户名或邮箱 + 密码登录 |
| `POST` | `/api/auth/guest` | 游客登录，账号 24h 过期 |
| `POST` | `/api/auth/logout` | 吊销当前 token（幂等） |
| `GET` | `/api/auth/me` | 当前登录用户 |
| `GET` | `/api/progress` | 我的关卡进度 |
| `GET` | `/api/score/me` | 我的积分与流水 |
| `GET` | `/api/score/leaderboard` | 排行榜，`period=all` / `weekly` / `monthly`；时段榜口径是窗口内新增分 |
| `GET` | `/api/achievements/me` | 我的成就 |

### 关卡

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/levels` | 关卡列表（含我的完成状态） |
| `GET` | `/api/levels/{slug}` | 关卡详情（说明、目标图、提示） |
| `POST` | `/api/levels/{slug}/start` | 按关卡定义建库，返回沙盒会话与初始图 |
| `POST` | `/api/levels/{slug}/validate` | 校验是否达成目标；登录用户首次通过即记进度、发积分 |
| `POST` | `/api/levels/{slug}/hints/{hintId}/use` | 使用一条分级提示（不扣分） |

### 关卡编辑器

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/level-drafts` | 我创作的关卡列表 |
| `GET` / `PUT` / `DELETE` | `/api/level-drafts/{slug}` | 读取草稿 / 保存草稿 / 删除 |
| `POST` | `/api/level-drafts/{slug}/self-check` | 试跑自证闭环，不改状态 |
| `POST` | `/api/level-drafts/{slug}/publish` | 发布；自证三项全绿才通过 |
| `POST` | `/api/level-drafts/{slug}/unpublish` | 下架 |

发布必须过**自证闭环**：语义校验 + 零步操作必不通关 + 参考解必定通关。任一项不绿即拒绝并回传原因。

### 协作房间与 PR

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/rooms` | 建房（可带场景关卡 slug 物化共享 origin） |
| `POST` | `/api/rooms/join` | 用邀请码加入 |
| `GET` | `/api/rooms/{roomId}` | 房间视图：成员、PR 列表 |
| `GET` | `/api/rooms/{roomId}/origin-graph` | 共享 origin 的图 |
| `GET` | `/api/rooms/{roomId}/scenario` | 场景关卡说明与目标图 |
| `POST` | `/api/rooms/{roomId}/members/{memberId}/exec` | 成员在自己克隆里执行命令 |
| `POST` | `/api/rooms/{roomId}/members/{memberId}/validate` | 在成员克隆上校验场景关卡 |
| `POST` | `/api/rooms/{roomId}/pulls` | 开 PR |
| `POST` | `/api/rooms/{roomId}/pulls/{number}/merge` | 合并 PR（裸仓 in-core 合并） |
| `GET` | `/api/rooms/{roomId}/pulls/{number}/diff` | PR 三点差异 |
| `GET` / `POST` | `/api/rooms/{roomId}/pulls/{number}/reviews` | 评审串 / 提交评审 |
| `POST` | `/api/rooms/{roomId}/pulls/{number}/comments` | 追加评论（带行号即行级） |

**实时同步**：客户端连 `/ws`（SockJS），订阅 `/topic/rooms/{roomId}`，在有人加入、有人 push、PR 变更时收到房间快照。广播只带房间状态，每位成员的图仍各自实时读出，不引入第二份状态。

**合并闸门**：存在未被更晚评审取代的 `changes_requested` 就挡住合并；作者不能自批自己的 PR。行级评论用 `anchor_commit_sha` + `original_line` + `diff_hunk` 定格锚点，每次 push / merge 后由后端重算当前行号，定位不了就如实标为过时，不猜行号。

## 安全模型

执行用户输入的 Git 命令等于运行不可信输入，这是本项目最大的安全面。落地约束：

1. **JGit 编程式执行**，不拼 shell、不起子进程，天然免疫 shell 注入。
2. **命令白名单**（`security/CommandWhitelist.java`）：
   - Git 子命令 15 个 —— `init` `add` `commit` `log` `status` `branch` `checkout` `switch` `merge` `tag` `rebase` `fetch` `pull` `push` `remote`
   - 终端内建命令 23 个 —— `pwd` `cd` `ls` `cat` `head` `tail` `wc` `sort` `uniq` `grep` `mkdir` `rmdir` `touch` `cp` `mv` `rm` `find` `tree` `stat` `du` `echo` `whoami` `date` `help` `clear`（全部纯 Java NIO 实现）
   - 白名单之外一律拒绝并给出友好提示。
3. **路径守卫**（`security/PathGuard.java`）：`/` 与 `~` 都锚定到沙盒根，相对路径从终端当前目录解析；任何已存在路径段是符号链接即拒绝；`.git` 大小写不敏感拦截。对外只显示虚拟 `~` 路径，不暴露宿主机目录。
4. **沙盒隔离**：每个会话 / 房间成员一个独立目录，`remote` 只读、不允许用户增删远程；远程仓库是沙盒兄弟目录下的裸仓，走 file 协议，不出网。
5. **资源上限**：终端输出 128 KiB、单文件读 256 KiB、重定向写 1 MiB、复制 4 MiB、递归 ≤1000 条目 / ≤12 层；管道 ≤6 级且只允许只读命令入管，`||` 直接拒绝；命令 token ≤128。
6. **不执行 Git 钩子**。
7. **沙盒回收**：定时作业按台账与内存活跃度双路回收，避免磁盘泄漏。
8. **命令审计**：`command_logs` 异步落库，stderr 截断存储。

任何触及命令执行、文件路径或进程调用的改动，都要逐条对照这份清单自检。

## 官方关卡

关卡以 classpath 资源形式存放于 `src/main/resources/levels/*.level.json`，当前 **24 关**（23 关单人 + 1 关协作）：

| 分类 | 关卡数 | 内容 |
|---|---|---|
| `basics` | 7 | 首次提交、连续提交、`--amend`、`HEAD^` / `HEAD~`、分离头指针、打标签 |
| `branching` | 4 | 创建分支、切换分支、分叉发展、快进合并 |
| `merge` | 2 | 三方合并、压缩合并 |
| `rebase` | 2 | 变基到主干、变基冲突 |
| `conflict` | 1 | 解决合并冲突 |
| `remote` | 4 | fetch、pull、push、推送被拒绝 |
| `pr` | 4 | 双远程追踪、发起 PR、协作冲突救活 PR、变基后强推 |

每关包含初始仓库定义、目标图、匹配策略、断言、参考解和分级提示。**每关的参考解就是回归测试**：`LevelSelfCheckTest` 对全部关卡跑一遍"零步必不过 + 参考解必通过"。玩家创作的关卡存在 `levels` 表，发布后与官方关卡走同一构建与校验链路。

关卡定义格式见 [`../docs/level-spec.md`](../docs/level-spec.md) 与 [`../schemas/level-spec/v1`](../schemas/level-spec/v1)。

## 项目结构

```text
src/main/java/org/xiaoyu/gitarena/
├─ controller/          # REST 入口，只做校验与转发
├─ service/             # 业务：命令、图、关卡、协作、积分、成就、运维
│  └─ impl/
├─ git/                 # 唯一能碰 JGit 的地方
│  ├─ SandboxManager    # 沙盒目录的创建、定位与回收
│  ├─ SandboxRepo       # 单个沙盒仓库（含 origin 兄弟目录、终端 cwd）
│  ├─ GitExecutor       # git 子命令的 JGit 实现与真实版式输出
│  ├─ SandboxShellExecutor  # 23 个终端内建命令（纯 Java）
│  ├─ GraphMapper       # 仓库 → GitGraph 快照
│  ├─ LevelBuilder      # 关卡定义 → 真实仓库
│  ├─ RoomRepo          # 房间共享裸 origin、克隆、PR 差异与 in-core 合并
│  └─ CommitIdentity    # 提交身份（登录用户名 / 邮箱）
├─ security/            # 白名单、命令解析、路径守卫、token、当前用户
├─ domain/              # 领域对象与 DTO
│  ├─ graph/GitGraph    # §契约核心
│  ├─ dto/  entity/  level/  collab/
├─ mapper/              # MyBatis-Plus Mapper
└─ config/              # Web、WebSocket、定时作业、安全 Bean

src/main/resources/
├─ application.yaml
├─ db/migration/        # Flyway 脚本 V1 / V2
└─ levels/              # 24 个官方关卡定义
```

分层规矩：Controller 薄，业务在 Service，**只有 `git/` 包可以直接使用 JGit**，其余层通过 Service 间接使用。

## 数据库

Flyway 在启动时自动迁移，脚本在 `src/main/resources/db/migration/`，命名 `V<n>__desc.sql`。

| 领域 | 表 |
|---|---|
| 用户与进度 | `users` `user_level_progress` |
| 关卡 | `levels` `level_hints` `user_hint_usage` |
| 沙盒 | `sandbox_repos`（台账，供回收作业扫描） |
| 协作 | `rooms` `room_members` `pull_requests` `pr_reviews` `pr_comments` |
| 成长 | `score_events` `achievements` `user_achievements` |
| 审计 | `command_logs` |
| 排行榜 | 物化视图 `leaderboard_weekly` `leaderboard_monthly` |

房间、成员、PR **以数据库为真相**，内存只留运行时句柄；成员命令、开 PR、合 PR 一律以登录用户鉴权，`memberId` 只是展示标识、不是凭证。

字段级说明与存储边界见 [`../database.md`](../database.md)。

## 定时运维作业

由 `config/MaintenanceScheduler` 触发、`service/MaintenanceService` 执行（拆开是为了让集成测试绕过时钟直接验证动作）。作业内吞异常只记日志——调度线程抛出未捕获异常会让该作业永久停摆，对回收类任务等于静默的磁盘泄漏。

| 作业 | 周期 | 动作 |
|---|---|---|
| 时段榜刷新 | 5 分钟 | `REFRESH MATERIALIZED VIEW CONCURRENTLY` 周 / 月榜 |
| 游客过期清理 | 1 小时 | 清理过期游客；房内还有别人的房间跳过，避免连带删掉共享 origin |
| 沙盒回收 | 1 小时 | 台账侧 `active` / `idle` → `cleaning` → `cleaned` 三段推进；内存侧按最后活跃时刻回收无台账的匿名沙盒 |

## 测试

```bash
mvn test
```

测试会连数据库并执行 Flyway 迁移，因此需要本地 PostgreSQL 与 Redis 可用。覆盖重点：

- `git/` 包 —— JGit 封装、图映射、沙盒管理、终端内建命令、远程流程、提交身份
- `service/` 包 —— 目标匹配、关卡自证、冲突断言、diff 锚点重定位、PR 评审、房间场景、运维回收、认证与进度
- `security/` 包 —— 命令解析与白名单
- 集成测试 —— 两成员 push / fetch 互见、PR in-core 合并推进 origin、冲突探测、坏关卡的发布拦截

## 开发约定

- 分支命名 `feat/xxx`、`fix/xxx`、`docs/xxx`、`refactor/xxx`；提交信息遵循 Conventional Commits。
- 提交前：`mvn test` 通过；改动涉及命令执行的对照[安全模型](#安全模型)逐条自检；GitGraph 契约变更则前后端同步并升版本号；新增依赖需说明理由。
- 图形操作与命令行操作必须仍走同一条执行链路。

## 相关文档

- 项目宪法：[`../CLAUDE.md`](../CLAUDE.md) / [`../AGENTS.md`](../AGENTS.md)（双份镜像，改一份要同步另一份）
- 数据库结构：[`../database.md`](../database.md)
- 关卡定义格式：[`../docs/level-spec.md`](../docs/level-spec.md) · [`../schemas/level-spec/v1`](../schemas/level-spec/v1)
- 关卡内容规划：[`../level.md`](../level.md)
- 前端模块：[`../git-arena-frontend`](../git-arena-frontend)

## 设计灵感

- [oh-my-git](https://ohmygit.org/) —— 每个操作都执行真实 git，提交图实时渲染。
- [Learning Git Branching](https://learngitbranching.js.org/) —— 关卡制、目标状态校验、命令行沙盒。

git-arena 的差异化在**协作训练**：多人对同一远程推拉、制造并解决冲突、走一遍 PR 评审流程。
