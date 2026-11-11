# 找个搭子 · 校园活动组队与候补平台
## 软件开发全过程文档（需求 → 设计 → 实现 → 测试 → 部署 → 运维）

- 课程：计算机学院《现代软件工程》课程设计（数据库方向）
- 小组：13 小组 —— 陈子逸、张梓轩、吉浩铭、唐裕翔
- 选题来源：课程设计选题参考（2026.10）“社团活动管理与报名系统”
- 需求依据：《校园活动组队平台需求规格说明书》CEM-SRS-0001（2026 年 9 月）
- 技术路线：Vue 3 + Spring Boot 3.3.5 + PostgreSQL 16
- 版本状态：已验证报名、候补递补、组织者编辑、会话、参与历史、签到核对和评价。各批次变更与剩余设计项见 [CHANGELOG.md](CHANGELOG.md)。
- 阅读方式：想演示只看第 5、6 节；想评审设计看第 2、3 节；想复现看第 5 节和 BUILD-NOTES.md

---

## 1. 需求分析

### 1.1 问题与用户
校园里想打球、自习、拼桌游时，常常找不到搭子；活动凑不齐人就取消。
即使有人发起活动，名额满了之后也只能在群里口头排队：谁先来、谁替补、
退出一个名额补不及时、邀请发出去没人确认也不知道该顺延给谁。
用户分三类：普通同学（报名 / 候补 / 确认）、组织者（发布 / 取消 / 看名单）、
管理员（P2，后续）。

### 1.2 功能需求（按 SRS 优先级）

| 优先级 | 功能 | 本版状态 |
|---|---|---|
| P0 | 注册、登录 | 已实现（PBKDF2 散列，演示级内存会话，见 3.4） |
| P0 | 发布活动（标题、类别、地点、上限、截止 / 开始 / 结束时间） | 已实现，含时间顺序与上限校验 |
| P0 | 报名：未满转正式，满员转候补并给排名 | 已实现 |
| P0 | 正式退出 → 自动给候补第 1 发限时邀请 | 已实现 |
| P0 | 接受 / 拒绝邀请；拒绝后顺延 | 已实现 |
| P0 | 邀请超时 → 自动失效并顺延到下一候补；过期确认接口直接拒绝 | 已实现（定时扫描 + 接口双重判定） |
| P0 | 退出后可重新报名，复用同一记录、不保留原排名 | 已实现（前端新增重新报名入口） |
| P0 | 取消活动：作废待确认邀请，参与记录置取消 | 已实现（仅组织者） |
| P1 | 通知：报名成功、进入候补、收到邀请、活动变更；未读数、全部已读 | 已实现，通知按（用户，事件键）去重 |
| P1 | 活动大厅搜索、按类别过滤；我的活动；组织者名单与待确认邀请 | 已实现 |
| P2 | 管理后台、活动评价（1–5 分，≤500 字） | 活动评价已实现并校验到场资格。管理后台待实现 |

### 1.3 非功能需求
- 一致性优先于花哨：人数、排名、邀请终态在并发下也不能错（验收见第 4 节）。
- 时间以服务器时间为准；确认窗口与扫描周期可配置，演示时可缩短。
- 国内可直连：Maven 走阿里云镜像，npm 走 npmmirror；前端打包进后端，不依赖国外 CDN。
- Windows 可演示：JDK 17 + PostgreSQL 装好后，双击 run.bat 即可。

### 1.4 核心业务规则（写进代码与测试的四条铁律）
1. 有效占用 = 正式人数 + 未过期待确认邀请数，永远 ≤ 人数上限。
2. 同一活动同一用户只有一条当前参与记录（唯一约束 + 幂等返回）。
3. 邀请只能有一种终态：接受 / 拒绝 / 超时 / 失效互相竞争，先成功提交者生效。
4. 候补按入队时间排，时间相同按参与记录编号排；待确认单独显示，不占候补队列。

---

## 2. 系统设计

### 2.1 架构
```
浏览器（Vue 3 单页应用，打包进 Jar 的 static/）
        │  REST /api，JSON，Bearer token
Spring Boot 3（Controller → Service 事务层 → JdbcTemplate）
        │  JDBC
PostgreSQL 16（6 张表，见 2.2）
        │  @Scheduled 定时任务（活动生命周期 + 到期邀请扫描）
```
不引入 Redis、消息队列：候补与邀请的状态机全部由数据库事务 + 唯一约束保证，
演示规模下这是最少外部依赖、最容易在 Windows 上复现的方案。

### 2.2 数据模型（ER 与表）
用户 1–N 活动（组织者）；用户 N–M 活动（经参与记录）；参与 1–N 邀请历史，
但同一时刻只能有一个待确认邀请；用户 1–N 通知；参与 1–1 评价（P2）。

| 表 | 关键字段 | 关键约束 |
|---|---|---|
| app_user（SRS 的 user，PostgreSQL 保留字故改名） | account、password_hash、nickname、role、status | account 唯一 |
| activity | organizer_id、title、category、capacity、deadline、start_time、end_time、status | 类别枚举；deadline ≤ start < end；status OPEN/READY/RUNNING/ENDED/CANCELLED |
| participation | activity_id、user_id、status、queued_at、joined_at | (activity_id, user_id) 唯一；status FORMAL/WAITING/INVITED/WITHDRAWN/EXPIRED/CANCELLED |
| invitation | participation_id、status、issued_at、expires_at、event_key | event_key 唯一；(status, expires_at) 组合索引供扫描 |
| notification | user_id、activity_id、type、content、event_key、read_at | (user_id, event_key) 唯一，防重复通知 |
| feedback（P2 预留） | participation_id、score、content | participation_id 唯一；score 1–5 |

完整脚本：`源码/database/schema.sql`（与后端内嵌脚本一致）。

### 2.3 状态机
- 参与：WAITING →（递补）→ INVITED →（接受）→ FORMAL；INVITED →（拒绝 / 超时）→ WITHDRAWN / EXPIRED；
  FORMAL / WAITING →（退出）→ WITHDRAWN；退出后重新报名复用同一记录。
- 邀请：PENDING → ACCEPTED / REJECTED / EXPIRED / INVALID，且只能转换一次（条件更新 `WHERE status='PENDING'`，影响行数必须为 1）。
- 活动：OPEN → READY → RUNNING → ENDED（定时任务按截止 / 开始 / 结束时间推进）；组织者可置 CANCELLED。

### 2.4 核心算法（为什么不会超员、不会插队、不会一女二嫁）
- 报名：`SELECT … FOR UPDATE` 锁活动行，串行化容量核算；先算有效占用再决定正式 / 候补；已有正式 / 候补 / 待确认记录时幂等返回原结果。
- 退出递补：同一事务内释放名额并 `promote()`：锁活动行 → 取候补第 1（queued_at, id）→ 发邀请，过期时间取 min(现在 + 确认窗口，报名截止）。
- 超时：定时任务逐条 `expireOne()`，用条件更新抢占邀请终态；确认接口即使先于扫描到达，也先按实际过期时刻判定，过期则置 EXPIRED 并顺延。
- 通知去重：事件键由业务事件 + 用户 + 时间构成，唯一约束兜底。

### 2.5 接口清单（统一前缀 /api，错误统一 `{ok:false,error}`）
GET /health；POST /login、/logout、/register；GET /me；
GET /activities（支持搜索、类别、校区、时段和分页，带正式 / 候补 / 预留计数与本人状态）；
GET /activities/{id}（返回活动版本，组织者可取得名单）；GET /activities/{id}/roster（组织者按状态分页查询）；
POST /activities、PUT /activities/{id}（版本和修改原因）、POST /activities/{id}/cancel（版本、取消原因、确认与受影响人数）；
POST /activities/{id}/signup（有参与条件时携带确认标志）、POST /participations/{id}/withdraw；
POST /invitations/{id}/accept、/reject；
GET /my、GET /my/participations/{id}/history；
签到和出勤核对使用 `/activities/{id}/attendance` 系列接口。评价汇总和本人评价使用 `/activities/{id}/feedback`。
GET /notifications（支持分页）、POST /notifications/{id}/read、POST /notifications/read-all、POST /demo/reset（仅演示）。

活动及参与的写请求可附加 `Idempotency-Key`。回执按账号和请求键保存，在业务事务内记录已提交响应。相同键对应不同请求时返回 HTTP 409。

### 2.6 技术选型理由
Vue 3 + Vite：单页应用、构建产物小（约 79 KB JS），可直接打包进 Jar；
Spring Boot 3 + JdbcTemplate：事务与定时任务开箱即用，SQL 可控，适合把约束写进数据库；
PostgreSQL：行锁、唯一约束、事务隔离正是本题的考点，比用内存队列更能证明数据库课设的工作量。

---

## 3. 实现

### 3.1 模块结构
- 后端 `backend/src/main/java/com/campusbuddy/`：
  `CampusBuddyApplication`（启动 + 定时开关）、`controller/ApiController`（接口与入参校验）、
  `service/AuthService`（PBKDF2WithHmacSHA256，10 万次散列；内存 token 会话）、
  `service/BuddyService`（报名 / 退出 / 递补 / 确认 / 详情，全部核心事务）、
  `service/SweepService`（活动生命周期 + 到期邀请扫描）、`service/SeedRunner`（空库才种演示数据）。
- 前端 `frontend/src/`：`App.vue`（大厅 / 详情 / 我的活动 / 通知 / 发布 / 注册 / 重新报名）、
  `main.js`、`style.css`；开发时代理 /api → localhost:8080。
- 配置分离：端口、数据库连接、确认窗口、扫描周期全部走环境变量
  （PORT、DB_URL、DB_USER、DB_PASSWORD、INVITE_TTL、SWEEP_SECONDS），不写死路径和密码。

### 3.2 演示数据（密码均为 123456）
组织者 organizer；同学 demo01–demo20。
种子活动：A“周六羽毛球双打”上限 4（3 正式）；B“桌游之夜”上限 4（4 正式 + demo05/06 候补）；
C“周末学习组”上限 10（2 正式）。时间均为相对启动时刻 +2 天截止、+3 天开始。

### 3.3 本轮补齐的可用性缺口（审计后修复）
1. 只有登录、没有注册：前端登录区新增“注册新账号”（账号 4–32 字符、密码至少 6 位、昵称可选），注册后自动登录；重复账号后端返回“账号已存在”。
2. 退出 / 过期后详情页不再出现报名按钮：改为本人状态为 WITHDRAWN / EXPIRED / CANCELLED 时仍可报名；“我的活动”里对应记录新增“重新报名”按钮；后端复用同一参与记录，候补时间重置、不保留排名，符合 SRS 3.3.5.2。

### 3.4 独立审核后的修复（2026-10-29，4 个独立审核员分别复核）
- PPT：修正了汇报时长（14 页版约 6 分钟，不是 4–5 分钟）、P0/P1 表述矛盾、app_user 表名、
  演示页“三个数加起来对得上”的数学错误、计划页把已完成写成未来计划的问题；通知与发布截图做了裁剪放大。
- 网页：修了计数器初始值（1/10 → 1/14）、空格键一次翻两页、窄屏底部栏与截图溢出。
- 后端（严重问题，均先复现再修）：接受邀请与退出并发死锁（统一锁顺序为参与 → 邀请 → 活动）；
  并发创建活动用 `SELECT MAX(id)` 会返回别人的 id，改用 `INSERT … RETURNING id`；
  演示重置匿名可调用且序列重启后旧 token 能冒用新用户，改为必须登录且重置后清空全部会话；
  停用账号旧 token 仍可操作，改为每次鉴权查账号状态；注册昵称超长被误报为账号已存在、
  非法入参与不存在记录返回 500 的问题一并改为 400 与准确报错；种子用户 id 不再按序号推算。
- 前端 UI（Claude 逆向风格）：画布改为 #FAF9F5、文字 #141413、强调 #D97757、边框 #E8E6DC；
  深海军蓝顶栏改为米白底 + 底边框；标题改衬线栈并拉开字号层级；按钮分层（主操作实心陶土橙，
  取消/拒绝改为橙字描边，不再与当前导航同色）；详情卡移到搜索卡之后；时间统一显示为 YYYY-MM-DD HH:mm；
  登录与发布表单改为标签在上、输入在下。改后截图已全部重截为新界面，递补邀请与通知为同一场演示，
  截止时间一致。

### 3.5 已知局限（不回避）
- 浏览器使用持久化 Cookie 会话，写请求携带 CSRF 令牌。移动端兼容 Bearer 令牌。公网部署需使用 HTTPS，并配置 `SESSION_SECURE=true`。
- 大厅、我发布的活动、组织者名单和通知支持分页，每页最多 50 条。
- 活动评价已实现。信用结算、独立申诉、活动治理与管理员恢复流程仍待实现。
- 演示重置接口 `/demo/reset` 默认关闭。仅对独立演示库配置 `DEMO_ENABLED=true`，登录后可重置。

---

## 4. 测试

### 4.1 测试策略
接口自动化为主（Python urllib，无第三方依赖，可直接重跑），分三层：
功能流（按用户真实顺序）、验收项（SRS 第 5 章的硬指标）、并发与边界（多线程同时请求）。
浏览器人工验证为辅：打开页面、快速登录、看大厅与详情是否与接口数据一致。

### 4.2 测试结果（2026-10-29，真实 PostgreSQL，INVITE_TTL=10 秒、SWEEP_SECONDS=2 秒）

**端到端全流程 `tests/test_full_flow.py`：16 项全过**
注册新用户、重复账号被拒绝、组织者发布活动（上限 2）、前 2 人正式、第 3 人候补第 1、
正式退出后候补收到邀请、接受邀请转正式、收到至少 2 条通知、全部已读后未读为 0、
再次退出后下一候补收到邀请、等超时后邀请过期、过期后重新报名复用同一记录、
组织者取消活动、取消后状态为 CANCELLED、取消后报名被拒绝。

**审计脚本 `tests/test_audit.py`：15 项全过**（修复前仅 1 项通过；含死锁 12 轮 0 次 500、
并发创建 id 一一对应、旧 token 冒用被拒、匿名重置被拒、停用 token 被拒、非法入参与不存在记录返回 400）。
三套合计 46/46 通过。

**验收脚本 `tests/test_api.py`：15 项全过（重跑两次一致）**
种子活动上限 4 已有 3 正式；第 4 人正式、第 5 人候补第 1、第 6 人候补第 2；
正式退出触发递补且正式 3 + 预留 1 = 上限；接受转正式后正式 4、预留 0；
超时后原邀请失效并顺延到下一候补；过期邀请再点接受返回 EXPIRED；
20 人同时报名上限 4 的活动恰好 4 正式、16 候补；同一账号并发 10 次只有一条有效记录。

**页面验证**：用 curl 直接请求 http://127.0.0.1:8080，返回 200 与“找个搭子”首页 HTML，
证明 Jar 内嵌的前端确实能被 served；登录、大厅、详情、报名、候补、递补的全部交互
已由上述接口自动化覆盖（前端调用的正是这些接口）。
另外说明一个实测缺口：本机的 live 浏览器任务两次都未能打开 localhost 标签页
（第一次 5 次、第二次 4 次尝试均返回“已打开新标签页”但标签页列表始终为空，
页面从未开始加载，也没有出现连接被拒绝等报错）；这是浏览器运行环境本身的故障，
不是演示应用的问题，两次尝试均未改动演示数据，所以本轮没有拿到浏览器点击截图。
这不影响 Windows 用户在自己浏览器里打开——上面 curl 与接口结果已证明服务本身正常，
Windows 上的浏览器打开方式见 5.1。

### 4.3 缺陷与修复记录

2026-11-11 起的变更与验收结果见 [CHANGELOG.md](CHANGELOG.md)。
- 构建期 Maven 走代理失败：根因是 Java 不读环境变量代理且 IPv6 隧道解析失败，
  改为代理配置 + 强制 IPv4 + 代理证书导入信任库后打包成功（BUILD-NOTES.md）。
- 种子建表中文约束乱码：根因是本机 locale 为 POSIX、Java 默认字符集非 UTF-8，
  启动加 `-Dfile.encoding=UTF-8` 解决；旧库残留乱码约束需重建数据库（CREATE TABLE IF NOT EXISTS 不会修复旧约束）。
- 测试脚本初版字段名与接口实际返回不一致（my_status / my_rank、列表直返）：按接口实测修正脚本，
  不改接口去迁就测试。

---

## 5. 部署与使用

### 5.1 Windows 演示（推荐，课堂现场）
1. 装 JDK 17、PostgreSQL 16；新建数据库：`psql -U postgres -c "CREATE DATABASE campus_buddy ENCODING 'UTF8';"`
2. 解压完整包，进 `运行包`，双击 `run.bat`，浏览器打开 http://localhost:8080。
3. 演示账号密码均为 123456：organizer、demo01–demo20；页面有快速登录与“恢复演示数据”。
4. 想现场快一点看超时顺延：关掉程序，设 `INVITE_TTL=10`、`SWEEP_SECONDS=2` 再启动。

### 5.2 演示脚本（对应 PPT 第 7 页，约 3 分钟）
羽毛球活动（上限 4，已有 3 正式）→ 第 4 人报名转正式，第 5 人候补第 1 →
第 4 人退出 → 候补第 1 收到限时邀请（正式 3 + 预留 1）→ 接受转正式；
另起一轮：退出 → 下一候补收邀请 → 等超时 → 自动顺延。
全程盯三个数字：正式人数、预留邀请数、候补排名。

### 5.3 从源码构建（可选）
前端：`源码/frontend` 下 `npm install`（已配 npmmirror）→ `npm run build` →
把 `dist` 拷入 `源码/backend/src/main/resources/static/`；
后端：`源码/backend` 下 `mvn -s settings-aliyun.xml package -DskipTests` →
产物 `target/campus-buddy-1.0.0.jar`。启动务必带 `-Dfile.encoding=UTF-8`。

---

## 6. 汇报材料
- `汇报/CampusBuddy-第一次汇报-含截图版.pptx`：14 页，全册讲稿备注 1528 字，约 7 分钟；第 6 页为 ER 图（ERAlchemy 连正在运行的 PostgreSQL 反向生成，Graphviz 渲染，见汇报/assets/erd-postgresql.png 与 .dot）；讲解侧重数据库用法（行锁串行化、条件更新抢终态、唯一约束与组合索引分工、超时扫描），PPT 与网页内容一致；
  前 9 页讲背景、技术路线与目标，第 10–13 页为运行截图（登录与大厅、递补邀请、通知、发布活动），
  第 14 页结尾；截图页讲时每张一句话带过。
- `汇报/CampusBuddy-网页展示-含截图版.html`：同一套内容的网页版，单文件、图片已内嵌、方向键翻页、讲稿开关。
- `汇报/screenshots/`：上述截图原图（登录与大厅、大厅、递补邀请、通知、发布活动、我的活动）。
- 两次汇报分工：第一次讲背景、技术路线与目标（本包的 PPT 与网页）；
  第二次（6–7 分钟）按 5.2 脚本现场演示，不只放录像；期末交纸质课程设计报告时以本文档为底稿扩写。

## 7. 总结与后续
已完成的是一个真正能注册、发布、报名、候补、递补、超时顺延、通知、取消的完整可用版本，
核心数据库规则全部落到事务与约束里并通过并发与超时验收，不是只有页面的演示壳。
后续工作包括管理员治理、投诉和独立申诉、信用结算、人工密码恢复与部署验收。