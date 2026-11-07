# 每日运行手册（cron 代理按此执行）

运行目录：`~/workspace/campus-buddy/daily-simulation/`，
当日输出：`runs/YYYY-MM-DD/`（users/、digest.md、changes.md、report.md）。

## 0. 环境恢复（虚拟机随时可能被重置，先做这步）

双库说明见 `DUAL-DB.md`。A 版（PostgreSQL，8080）是默认；B 版
（MySQL 8.0.28，8081）按当日抽签决定是否启用（见下）。

1. 查后端健康：`curl -s http://127.0.0.1:8080/api/health`，正常返回 ok 且
   users=21、activities=3 即通过。
2. 不通就重建：`useradd -m pgdemo`（如不存在）→ 复制
   `~/workspace/.portable/pgsql` 到 `/tmp/pgsql` 并 `chmod -R a+rX` →
   `LD_LIBRARY_PATH=/tmp/pgsql/usr/lib/x86_64-linux-gnu:/tmp/pgsql/usr/lib/postgresql/16/lib`
   下用 pgdemo 身份 initdb（`/tmp/pgdata2`，UTF8，C.utf8）→ postgresql.conf
   追加 port=5433、listen 127.0.0.1、socket /tmp/pgrun、**timezone='Asia/Shanghai'**
   （时区统一，2026-11-06 起必带；漏了它 PG `now()` 产 UTC 墙钟，
   `reserved_count` 与 `created_at` 默认值全错 8 小时）→ pg_ctl 启动 →
   建 postgres 超级用户 → createdb campus_buddy → 从
   `~/workspace/campus-buddy/database/backup-2026-11-04.sql` 恢复 →
   核对计数 21/3/10（A 版；2026-11-06 晚用户定种子改动后，demo01 退出
   周末学习组，活动参与由 11 变 10；B 版仍为 21/3/11）。重建后验时区：`SHOW timezone;` 应为 Asia/Shanghai，
   `SELECT now();` 应带 `+08`；系统时区也应为 Asia/Shanghai
   （`/etc/localtime` → Asia/Shanghai，`date` 应显示 CST）。
3. 启动 A 版后端：`DB_URL=jdbc:postgresql://127.0.0.1:5433/campus_buddy
   DB_USER=postgres INVITE_TTL=120 SWEEP_SECONDS=10 PORT=8080`，
   `~/workspace/.portable/jdk-17.0.21+9/bin/java -Dfile.encoding=UTF-8
   -Djava.net.preferIPv4Stack=true -Duser.timezone=Asia/Shanghai -jar
   ~/workspace/campus-buddy/backend/target/campus-buddy-1.0.0.jar`，
   再验健康。启动约需 14–16 秒，健康检查至少等 20 秒。**JVM 时区参数
   `-Duser.timezone=Asia/Shanghai` 必带（时区统一，2026-11-06 起）；漏了它
   `LocalDateTime.now()` 产 UTC 墙钟，活动时间整体晚 8 小时（2026-11-06 P0
   的根因）。**B 版同理（8081，见 DUAL-DB.md，启动前先轮换 cb_app 密码）。
4. **A/B 抽签（每天一次，步骤 1 之前做）**：抛一次硬币决定当天 6 名
   用户测 A 还是 B（按天随机，不按人随机）。结果与种子记入
   `runs/<日期>/ab-assignment.md` 与当日 digest。抽中 B 时：先确认
   MySQL 在跑（`mysqladmin --defaults-file=~/workspace/.portable/mysql-8.0.28/my.cnf ping`），
   不在就用 DUAL-DB.md 的命令启动（数据目录
   `~/workspace/.portable/mysql-data` 还在，原地启动即可）；再按
   DUAL-DB.md 先轮换 cb_app 密码再启动 B 版应用实例（8081，密码不存文件、
   每次启动现生成，见 DUAL-DB.md“启动 B 版”）。
   MySQL 没就绪时，循环照常只跑 PostgreSQL 并在报告里注明。MySQL 时区
   由 `~/workspace/.portable/mysql-8.0.28/my.cnf` 里的
   `default-time-zone=+08:00` 固定（不要写成 `time_zone=`，mysqld 会报
   unknown variable 而启动失败）；验活时顺带查
   `SELECT @@global.time_zone, NOW();` 应为 `+08:00`。
   **停应用时按端口逐个 kill（见 DUAL-DB.md）；`kill_backend.py`
   会杀掉所有 campus-buddy 的 Java 进程，双实例运行时绝不能用。**

## 1. 数据库快照

- A 版：`pg_dump` 当日一份到 `runs/YYYY-MM-DD/db-before.sql`（以 pgdemo 身份连
  127.0.0.1:5433；pg_dump 写 runs 目录会 Permission denied，先 dump 到 /tmp
  再 cp 过去）。
- B 版（当天抽中 B 或 QA 要测 B 时）：`mysqldump` 一份到
  `runs/YYYY-MM-DD/db-before-mysql.sql`（`--single-transaction --no-tablespaces`，
  命令见 DUAL-DB.md）。
- 用户体验结束、开发验证完成后，用快照恢复，当天体验数据不留库。

## 2. 用户体验（并行拉起 6 个子代理）

每人一份独立任务，任务里写清：你的人设文件路径、当日场景、
产品地址（当天抽中的版本：A 版 http://127.0.0.1:8080，B 版
http://127.0.0.1:8081；抽签结果见 `runs/<日期>/ab-assignment.md`）、
驱动方式（用 `~/workspace/.portable/venv-mobile/bin/python` + Playwright 写一段
临时脚本操作页面并截图，或直接调后端 API）、报告写到
`runs/YYYY-MM-DD/users/<名字>.md`（格式：试了什么、每步顺不顺、
卡点原话、建议 + 严重度 P0/P1/P2、截图路径）。

- 长期用户（3 名）：先读自己的 journal 文件，按人设和昨日记录继续体验。
  沈知行：组织一次活动并看报名情况；顾晓：逛大厅并报一个活动；
  陆远：检查通知与「我的活动」，并试一个边界情况（满员/截止/邀请过期）。
- 新用户（3 名）：从 `new-user-angles.md` 取当日 3 个角度并打勾记日期，
  现场生成身份，以首日用户视角完成登录→逛大厅→报名（或发布）全流程。

禁令（写进每个用户代理的任务里）：绝不点「恢复演示数据」；
不改他人数据；不直接改代码。

## 3. 产品经理汇总（串行，1 个子代理）

开工前先读 TEAM.md「能力配置」里林晚的四个 skill
（meeting-follow-up、documents、teamwork_delivery_guard、planning-task-breakdown），再读
6 份用户报告，按 TEAM.md 的林晚人设去重、归因、定优先级，
写 `runs/YYYY-MM-DD/digest.md`（含当日全部开发任务 +「待用户定」区）。
用户长期规则（2026-11-06）：**反馈里需要修的，当天全部修完，不设数量
上限、不推到明天**（取消原来“每天最多 3 项小改动”的上限）。只有三类
不进当日任务——新功能、需要改 SRS/SDD 文档的需求变更、与 REQUIREMENTS.md
硬约束冲突的事项——注明依据放「待用户定」区。用户 2026-11-06 已授权协调人决定
开放问题（见 DECISIONS.md）：有决定记录的待用户定事项可排入当日任务；
无决定记录的仍只放待用户定区，不擅自实施。**排任务前必读 REQUIREMENTS.md
（SRS v1.4 与 SDD v1.0 的硬约束：类别固定、组织者须自行报名、详情页不含
名单与联系方式、通知矩阵、R1～R5、不新增文档外字段）；与硬约束冲突的
事项不排入任务，注明依据放回待用户定区。**同步更新长期用户的 journal
（各追加 2–3 行当日体验摘要）。

## 4. 开发优化（串行：后端 + 前端各 1 个子代理，最后测试 1 个）

开工前各先读自己的 skill：沈默读 campus-buddy-backend、
superpowers-systematic-debugging、superpowers-test-driven-development，
苏晴读 claude-rea-design + MOBILE.md、antfu-vue、vue-debug，陈诺读
campus-buddy-qa、superpowers-verification-before-completion、webapp-testing
（路径见 TEAM.md「能力配置」）。

- 后端（沈默）、前端（苏晴）按 digest 的任务改代码。**先复现后修复**：
  每个任务动手前先复现问题（写失败测试，或跑一段复现脚本，或留复现
  步骤 + 截图/日志），复现成功再修；修完跑复现验证通过才算做完；
  复现不出来的先写明复现尝试与结论，不凭猜测改代码。改动记入
  `runs/YYYY-MM-DD/changes.md`（复现方法、根因、改了什么、证据）。
- 测试（陈诺）独立复核：后端测试在两个库上都跑（A 版 8080、B 版 8081，
  各 46/46；测试参数 INVITE_TTL=10 SWEEP_SECONDS=2，BASE_URL 换端口）、
  移动端测试 16/16（只跑当天抽中的版本）、数据库不变量复核（容量、
  唯一约束、重复/孤儿全 0，两库各查一遍，MySQL 用 NOW()）；不通过打回。

## 5. 恢复数据库并交付

1. 用步骤 1 的快照恢复，复核计数回到快照时状态。**恢复 PG 快照必须先
   dropdb + createdb 再 `psql -f`（pg_dump 不含 DROP，直接恢复会残留
   测试产生的多余行）；快照文件须先 cp 到 /tmp 并 chown pgdemo
   （runs 目录文件 pgdemo 读不了）。**B 版用 DROP/CREATE + `mysql <`
   恢复（命令见 DUAL-DB.md）。两个库的恢复互不影响，但各恢复各的
   快照，不要串。如当日有结构改动（迁移文件在
   `runs/YYYY-MM-DD/migrations/`），恢复后重新执行迁移文件，
   再复核计数与不变量；迁移须同步更新 `schema.sql`（见 DECISIONS.md
   「结构改动规则」）。
2. 写 `runs/YYYY-MM-DD/report.md`（当天摘要：用户反馈 Top 3、改了什么、
   没改什么及原因、明日关注点）。
3. 把摘要发到用户所在的 side chat（本对话），完整报告留档，不贴长文。

## 6. 同步 GitHub（每次自动推）

用户长期规则（2026-11-06）：每日循环与任何一批代码改动做完后不用问，
直接推 GitHub（lemonwater379/campus-buddy，main）。步骤：

1. 列出当日改动的文件（代码、测试、TEAM.md、RUNBOOK.md、DECISIONS.md、
   REQUIREMENTS.md、DUAL-DB.md、new-user-angles.md 等 UTF-8 文本）；
   不推 runs/ 报告与截图、日志、jar、二进制、截图、node_modules、target/、
   static 构建产物。清单写到 `runs/<日期>/` 外的临时文件（NUL 分隔）。
2. 按 `/opt/hatch/connectors/github/references/snapshot-push.md` 走：
   先用 `list_commits` 核对远端 main 的 head 与同步记录
   `~/workspace/campus-buddy.muse-github-snapshot-sync.json` 最新一条一致，
   再 `github push-files`，成功后把新提交 SHA 与清单追加进同步记录
   （先写 `.tmp-*` 再原子替换），最后再查一次 head 确认。
3. GitHub 写操作每次仍会弹出一次审批卡（安全规则，绕不过），由用户点
   一下确认；用户不在或超时未点，推送记为待推、不阻塞当日交付，
   下次运行重推。不要因为等审批而拖延报告交付。

## 7. 失败处理

- 环境重建失败：不硬跑用户体验，先修环境，当日只交付失败说明。
- 某用户代理失败：其余继续，digest 注明缺失的一份，不编造。
- 开发改动导致测试变红：回滚改动，当日重修；重修仍不通过才记入明日队列并说明原因，changes.md 如实记。
