# 每日运行手册（cron 代理按此执行）

运行目录：`~/workspace/campus-buddy/daily-simulation/`，
当日输出：`runs/YYYY-MM-DD/`（users/、digest.md、changes.md、report.md）。

## 0. 环境恢复（虚拟机随时可能被重置，先做这步）

1. 查后端健康：`curl -s http://127.0.0.1:8080/api/health`，正常返回 ok 且
   users=21、activities=3 即通过。
2. 不通就重建：`useradd -m pgdemo`（如不存在）→ 复制
   `~/workspace/.portable/pgsql` 到 `/tmp/pgsql` 并 `chmod -R a+rX` →
   `LD_LIBRARY_PATH=/tmp/pgsql/usr/lib/x86_64-linux-gnu:/tmp/pgsql/usr/lib/postgresql/16/lib`
   下用 pgdemo 身份 initdb（`/tmp/pgdata2`，UTF8，C.utf8）→ postgresql.conf
   追加 port=5433、listen 127.0.0.1、socket /tmp/pgrun → pg_ctl 启动 →
   建 postgres 超级用户 → createdb campus_buddy → 从
   `~/workspace/campus-buddy/database/backup-2026-11-04.sql` 恢复 →
   核对计数 21/3/11。
3. 启动后端：`DB_URL=jdbc:postgresql://127.0.0.1:5433/campus_buddy
   DB_USER=postgres INVITE_TTL=120 SWEEP_SECONDS=10 PORT=8080`，
   `~/workspace/.portable/jdk-17.0.21+9/bin/java -Dfile.encoding=UTF-8
   -Djava.net.preferIPv4Stack=true -jar
   ~/workspace/campus-buddy/backend/target/campus-buddy-1.0.0.jar`，
   再验健康。

## 1. 数据库快照

`pg_dump` 当日一份到 `runs/YYYY-MM-DD/db-before.sql`（以 pgdemo 身份连
127.0.0.1:5433）。用户体验结束、开发验证完成后，用此快照恢复，
当天体验数据不留库。

## 2. 用户体验（并行拉起 6 个子代理）

每人一份独立任务，任务里写清：你的人设文件路径、当日场景、
产品地址 http://127.0.0.1:8080、驱动方式（用
`~/workspace/.portable/venv-mobile/bin/python` + Playwright 写一段
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

开工前先读 TEAM.md「能力配置」里林晚的三个 skill
（meeting-follow-up、documents、teamwork_delivery_guard），再读
6 份用户报告，按 TEAM.md 的林晚人设去重、归因、定优先级，
写 `runs/YYYY-MM-DD/digest.md`（含当日最多 3 项开发任务 +「待用户定」区，
数据库结构类建议只放待用户定区）。同步更新长期用户的 journal
（各追加 2–3 行当日体验摘要）。

## 4. 开发优化（串行：后端 + 前端各 1 个子代理，最后测试 1 个）

开工前各先读自己的 skill：沈默读 campus-buddy-backend，苏晴读
claude-rea-design + MOBILE.md，陈诺读 campus-buddy-qa（路径见
TEAM.md「能力配置」）。

- 后端（沈默）、前端（苏晴）按 digest 的任务改代码，改动记入
  `runs/YYYY-MM-DD/changes.md`（改了什么、为什么、证据）。
- 测试（陈诺）独立复核：后端测试保持通过、移动端测试 16/16、
  数据库不变量复核（容量、唯一约束、重复/孤儿全 0）；不通过打回。

## 5. 恢复数据库并交付

1. 用步骤 1 的快照恢复，复核计数回到快照时状态。
2. 写 `runs/YYYY-MM-DD/report.md`（当天摘要：用户反馈 Top 3、改了什么、
   没改什么及原因、明日关注点）。
3. 把摘要发到用户所在的 side chat（本对话），完整报告留档，不贴长文。

## 6. 失败处理

- 环境重建失败：不硬跑用户体验，先修环境，当日只交付失败说明。
- 某用户代理失败：其余继续，digest 注明缺失的一份，不编造。
- 开发改动导致测试变红：回滚改动，changes.md 如实记，次日再议。
