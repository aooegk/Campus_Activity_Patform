# 双数据库运行手册（A 版 PostgreSQL + B 版 MySQL）

同一份 Java 代码服务两个库，不分叉。A 版是现有实现，B 版是 SDD
要求的 MySQL 8。两个库完全隔离：各自的服务器、数据目录、快照、
备份、应用实例、日志、端口。

## 对照表

| 项目 | A 版 | B 版 |
| --- | --- | --- |
| 数据库 | PostgreSQL 16.2 | MySQL 8.0.28 |
| 数据库端口 | 5433（127.0.0.1） | 3306（127.0.0.1） |
| 应用端口 | 8080 | 8081 |
| 应用地址 | http://127.0.0.1:8080 | http://127.0.0.1:8081 |
| 库名 | campus_buddy | campus_buddy |
| DB_URL | `jdbc:postgresql://127.0.0.1:5433/campus_buddy` | `jdbc:mysql://127.0.0.1:3306/campus_buddy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai` |
| 驱动 | `org.postgresql.Driver`（默认） | `com.mysql.cj.jdbc.Driver`（DB_DRIVER 环境变量） |
| 建表脚本 | `classpath:schema.sql`（默认） | `classpath:schema-mysql.sql`（DB_SCHEMA 环境变量） |
| 数据库用户 | postgres（超级用户） | cb_app（仅 campus_buddy.* 全部权限） |
| 数据库日志 | ~/workspace/backend.log | ~/workspace/backend-mysql.log |
| 快照 | runs/&lt;日期&gt;/db-before.sql（pg_dump） | runs/&lt;日期&gt;/db-before-mysql.sql（mysqldump） |

## 时区（全部东八区，2026-11-06 统一）

- **口径**：系统、JVM、PostgreSQL、MySQL、应用内所有时间处理与展示，全部按
  Asia/Shanghai（UTC+8）。时间字段直接存东八区墙钟，不再混用 UTC。代码里已
  删除全部 UTC↔上海转换（`shanghaiToUtcWall()`、`utcWallToShanghai()`、
  `fmtShanghaiMinute()` 的转换分支）；发布入口直接存前端发来的墙钟，显示直
  接用库里值。`application.properties` 保留
  `spring.jackson.time-zone=Asia/Shanghai`。此口径与 SDD §2.2（"存储采用
  UTC 语义，界面以 Asia/Shanghai 显示"）冲突，以用户指令为准，SDD 该条作废。
- **各处设置**：
  - 系统：`/etc/localtime` → Asia/Shanghai，`/etc/timezone` = Asia/Shanghai。
  - JVM：两个实例启动参数都加 `-Duser.timezone=Asia/Shanghai`
    （`LocalDateTime.now()` 即东八区墙钟；见下"应用实例"）。
  - PostgreSQL：`postgresql.conf` 加 `timezone='Asia/Shanghai'`
    （虚拟机替换后重建时也要带上，见 RUNBOOK 第 0 步）。验证：
    `SHOW timezone;` → Asia/Shanghai，`SELECT now();` → `+08`。
  - MySQL：`my.cnf` 写 `default-time-zone=+08:00`。**注意：`time_zone=+08:00`
    不是合法配置项，mysqld 会报 unknown variable 而启动失败。**验证：
    `SELECT @@global.time_zone, @@session.time_zone, NOW();` → `+08:00`。
  - JDBC：MySQL 的 URL 带 `serverTimezone=Asia/Shanghai`（见对照表）。
- **数据迁移**：2026-11-06 双库 15 列全部 +8 小时（PG `INTERVAL '8 hours'`，
  MySQL `INTERVAL 8 HOUR`），行数不变；种子备份
  `database/backup-2026-11-04.sql` 已从迁移后 PG 库重新生成（文件头注明
  "东八区口径，2026-11-06 迁移"）。完整记录：
  `runs/2026-11-06/tz-migration.md`。
- **快照口径警告**：`runs/2026-11-06/db-before-tz.sql` /
  `db-before-tz-mysql.sql` 是**迁移前（UTC 口径）的回滚快照**，只能用于
  "迁移本身失败时回滚"，绝不能作为日常恢复源——2026-11-06 验证收尾时
  从它恢复曾把 +8h 迁移 undo（后已重跑迁移并重拍快照）。日常恢复用当日
  快照（`db-before.sql` / `db-before-mysql.sql`）或迁移后快照
  `db-after-tz.sql` / `db-after-tz-mysql.sql`。
- **三处时区不一致会怎样错**（2026-11-06 P0 的根因就是这类错）：
  - JVM 是 UTC、其余是上海：发布入口把前端发的上海墙钟当 UTC 入库，
    所有活动时间整体晚 8 小时；`LocalDateTime.now()` 产 UTC 墙钟，
    邀请过期、截止判断全错 8 小时。
  - PG 是 UTC、JVM 是上海：`activity.created_at` 等靠 `DEFAULT now()`
    的列存 UTC 墙钟；`reserved_count` 子查询里的 `expires_at > now()`
    用 UTC 的 now() 比上海墙钟的 expires_at，预留数最多滞后 8 小时
    （仅显示，容量强制在 Java 侧，不受影响）。
  - MySQL 是 UTC、JVM 是上海：`DATETIME DEFAULT NOW()` 的列存 UTC
    墙钟；JDBC `serverTimezone` 与库时区不一致时驱动可能再换算一次，
    造成二次偏移。
- **Render 限制**：Render 的免费 PostgreSQL 时区是 UTC 且不可改；Dockerfile
  的启动命令已加 `-Duser.timezone=Asia/Shanghai`（JVM 走东八区），但
  Render 上 PG `now()` 仍产 UTC 墙钟。影响仅限两处，均不碰业务判断：
  (1) 列表/详情页的 `reserved_count`（`expires_at > now()`）显示最多
  滞后 8 小时——容量强制、邀请过期、截止判断都在 Java 侧（JVM 东八区），
  不受影响；(2) 靠 `DEFAULT now()` 填充的 `created_at`/`updated_at`
  （如 activity 创建时）会是 UTC 墙钟，仅为记录时间。Render 上线前若要
  消除这两处，可把 SQL 里的 `now()` 换成 Java 传入的当前时间参数。

## 安装位置

- PostgreSQL：`~/workspace/.portable/pgsql`（程序），数据目录 `/tmp/pgdata2`
  （虚拟机替换后按 RUNBOOK 第 0 步重建，见下）。
- MySQL：tarball `~/workspace/.portable/mysql-8.0.28-linux-glibc2.12-x86_64.tar.xz`，
  解压目录 `~/workspace/.portable/mysql-8.0.28`，数据目录
  `~/workspace/.portable/mysql-data`（持久目录，绝不放 /tmp），
  配置 `~/workspace/.portable/mysql-8.0.28/my.cnf`，
  socket `~/workspace/.portable/mysql-data/mysql.sock`。
  依赖的 libaio.so.1、libtinfo.so.5 在 `mysql-8.0.28/lib/private/`（未动系统库）。
- root 账号是 `--initialize-insecure` 的空密码，仅限本机 socket/127.0.0.1，
  不要对外暴露，不要写进任何文件。
- cb_app 密码：**不存、不写文件、不进仓库**。每次启动 B 版前，现生成一个
  随机密码，用 root 经 socket 改掉 cb_app 的密码，再以 `DB_PASSWORD` 环境
  变量传给应用（见下“启动 B 版”）。密码只活在当次启动的 shell 与应用
  进程里，重启即换，旧密码随之失效。root 本身是空密码、仅限本机 socket，
  是改密码的唯一入口；不要给 cb_app 设固定密码。

## 启动

### 数据库服务器

```bash
# PostgreSQL（虚拟机替换后按 RUNBOOK 第 0 步重建；已在跑时跳过）
# 配方：useradd pgdemo → cp -r ~/.portable/pgsql /tmp/pgsql → initdb /tmp/pgdata2
# → postgresql.conf 追加 port=5433、listen 127.0.0.1、socket /tmp/pgrun、
#   timezone='Asia/Shanghai' → pg_ctl 启动 → 建 postgres 超级用户 → createdb campus_buddy → 从备份恢复

# MySQL（已装好时只需启动；虚拟机替换后数据目录还在，原地启动）
~/workspace/.portable/mysql-8.0.28/bin/mysqld \
  --defaults-file=/home/hatch/workspace/.portable/mysql-8.0.28/my.cnf \
  --daemonize --user=root
# 验活：
~/workspace/.portable/mysql-8.0.28/bin/mysqladmin \
  --defaults-file=/home/hatch/workspace/.portable/mysql-8.0.28/my.cnf ping
```

### 应用实例（同一份 jar）

```bash
J=~/workspace/.portable/jdk-17.0.21+9/bin/java
JAR=~/workspace/campus-buddy/backend/target/campus-buddy-1.0.0.jar

# A 版（正常参数 INVITE_TTL=120 SWEEP_SECONDS=10）
DB_URL=jdbc:postgresql://127.0.0.1:5433/campus_buddy DB_USER=postgres \
  INVITE_TTL=120 SWEEP_SECONDS=10 PORT=8080 \
  $J -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -Duser.timezone=Asia/Shanghai -jar $JAR \
  > ~/workspace/backend.log 2>&1 &

# B 版（正常参数；启动前先轮换一次密码，不写文件——2026-11-06 已实测通过）
NEWPW=$(openssl rand -hex 16)
~/workspace/.portable/mysql-8.0.28/bin/mysql \
  -S ~/workspace/.portable/mysql-data/mysql.sock -u root \
  -e "ALTER USER 'cb_app'@'127.0.0.1' IDENTIFIED BY '$NEWPW'; FLUSH PRIVILEGES;"
DB_URL='jdbc:mysql://127.0.0.1:3306/campus_buddy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai' \
  DB_USER=cb_app DB_PASSWORD="$NEWPW" \
  DB_DRIVER=com.mysql.cj.jdbc.Driver DB_SCHEMA=classpath:schema-mysql.sql \
  INVITE_TTL=120 SWEEP_SECONDS=10 PORT=8081 \
  $J -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -Duser.timezone=Asia/Shanghai -jar $JAR \
  > ~/workspace/backend-mysql.log 2>&1 &
```

启动约需 14–16 秒，健康检查至少等 20 秒再验：

```bash
curl -s http://127.0.0.1:8080/api/health   # A：ok 且 users=21、activities=3
curl -s http://127.0.0.1:8081/api/health   # B：同上
```

测试参数（后端测试用）：`INVITE_TTL=10 SWEEP_SECONDS=2`，测完切回正常参数。

## 停止

**`~/workspace/kill_backend.py` 会杀掉所有 campus-buddy 的 Java 进程，
双实例运行时绝不能用。** 按端口逐个停：

```bash
# 停 A（8080）或 B（8081），把端口号换掉即可
PID=$(ss -tlnp 2>/dev/null | grep ':8080 ' | grep -o 'pid=[0-9]*' | cut -d= -f2)
[ -n "$PID" ] && kill $PID

# 停 MySQL 服务器
~/workspace/.portable/mysql-8.0.28/bin/mysqladmin \
  --defaults-file=/home/hatch/workspace/.portable/mysql-8.0.28/my.cnf shutdown

# 停 PostgreSQL
su pgdemo -c 'LD_LIBRARY_PATH=/tmp/pgsql/usr/lib/x86_64-linux-gnu:/tmp/pgsql/usr/lib/postgresql/16/lib /tmp/pgsql/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pgdata2 stop'
```

## 快照与恢复

种子标准：21 用户、3 活动、11 参与、0 邀请、0 通知、0 反馈。
恢复前先停掉对应版本的应用实例。

```bash
# --- A 版：pg_dump 快照 ---
su pgdemo -c 'LD_LIBRARY_PATH=/tmp/pgsql/usr/lib/x86_64-linux-gnu:/tmp/pgsql/usr/lib/postgresql/16/lib /tmp/pgsql/usr/lib/postgresql/16/bin/pg_dump -h 127.0.0.1 -p 5433 -U postgres -d campus_buddy' \
  > /tmp/db-before.sql
cp /tmp/db-before.sql runs/<日期>/db-before.sql

# --- A 版：恢复（pg_dump 不含 DROP，必须先 dropdb + createdb，否则多余行残留） ---
# 先停 A 版应用；快照文件是 root 权限，pgdemo 读不了：先 cp 到 /tmp 并 chown pgdemo
cp runs/<日期>/db-before.sql /tmp/db-before-restore.sql && chown pgdemo:pgdemo /tmp/db-before-restore.sql
export LD_LIBRARY_PATH=/tmp/pgsql/usr/lib/x86_64-linux-gnu:/tmp/pgsql/usr/lib/postgresql/16/lib
su pgdemo -c '/tmp/pgsql/usr/lib/postgresql/16/bin/dropdb -h 127.0.0.1 -p 5433 -U postgres campus_buddy'
su pgdemo -c '/tmp/pgsql/usr/lib/postgresql/16/bin/createdb -h 127.0.0.1 -p 5433 -U postgres campus_buddy'
su pgdemo -c '/tmp/pgsql/usr/lib/postgresql/16/bin/psql -h 127.0.0.1 -p 5433 -U postgres -d campus_buddy -f /tmp/db-before-restore.sql'

# --- B 版：mysqldump 快照（--no-tablespaces 可免 PROCESS 权限警告） ---
~/workspace/.portable/mysql-8.0.28/bin/mysqldump \
  --defaults-file=/home/hatch/workspace/.portable/mysql-8.0.28/my.cnf \
  -u cb_app -p'<DB_PASSWORD>' -h 127.0.0.1 -P 3306 \
  --default-character-set=utf8mb4 --single-transaction --no-tablespaces \
  campus_buddy > runs/<日期>/db-before-mysql.sql

# --- B 版：恢复（先停 B 版应用） ---
M=~/workspace/.portable/mysql-8.0.28/bin/mysql
$M -S ~/workspace/.portable/mysql-data/mysql.sock -u root \
  -e "DROP DATABASE campus_buddy; CREATE DATABASE campus_buddy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
$M -S ~/workspace/.portable/mysql-data/mysql.sock -u root campus_buddy \
  < runs/<日期>/db-before-mysql.sql
```

恢复后复核计数（A 版应回到 21/3/10/0/0/0、B 版 21/3/11/0/0/0——
2026-11-06 晚用户定 A 版种子改动：demo01 退出周末学习组，A 版活动
参与由 11 变 10，B 版未改）与不变量（见下）。

## 不变量复核（两库都必须全 0）

```sql
-- 容量超限、重复参与、孤儿参与、孤儿邀请、孤儿通知；PG 用 now()，MySQL 用 NOW()
SELECT
 (SELECT count(*) FROM activity a WHERE
   (SELECT count(*) FROM participation p WHERE p.activity_id=a.id AND p.status='FORMAL')
   + (SELECT count(*) FROM invitation i JOIN participation p ON p.id=i.participation_id
      WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > now()) > a.capacity),
 (SELECT count(*) FROM (SELECT activity_id,user_id FROM participation
   GROUP BY activity_id,user_id HAVING count(*)>1) d),
 (SELECT count(*) FROM participation p LEFT JOIN activity a ON a.id=p.activity_id
   LEFT JOIN app_user u ON u.id=p.user_id WHERE a.id IS NULL OR u.id IS NULL),
 (SELECT count(*) FROM invitation i LEFT JOIN participation p ON p.id=i.participation_id WHERE p.id IS NULL),
 (SELECT count(*) FROM notification n LEFT JOIN app_user u ON u.id=n.user_id WHERE u.id IS NULL);
```

## A/B 抽签（每日循环用）

- 按“天”随机，不按“人”随机：每天运行前抛一次硬币，当天 6 名用户
  全部测抽中的版本（用户之间会互相交互，拆到两个库上流程就测不了）。
- 抽签结果与种子记入 `runs/<日期>/ab-assignment.md` 与当日 digest。
- QA 每天对两个库都跑后端测试（46/46）；用户与移动端测试只跑当天
  抽中的版本。
- MySQL 没就绪时，循环照常只跑 PostgreSQL 并在报告里注明。

## 已踩过的坑（不要重犯）

1. **JDBC URL 的字符集参数必须用 Java 名 `UTF-8`，不能用 `utf8mb4`**：
   `characterEncoding=utf8mb4` 会报 `Unsupported character encoding 'utf8mb4'`，
   应用启动失败。正确：`characterEncoding=UTF-8`（服务器端已是 utf8mb4）。
2. **MySQL 的 TEXT 列不能有 DEFAULT**：`description TEXT NOT NULL DEFAULT ''`
   报 `BLOB, TEXT... can't have a default value`。schema-mysql.sql 里已去掉，
   Java 代码每次插入都传 description，不依赖默认值。
3. **MySQL 驱动把 DATETIME/TIMESTAMP 列返回为 `LocalDateTime`**，
   PostgreSQL 驱动返回为 `Timestamp`。代码里禁止 `(Timestamp)` 强转，
   统一用 `BuddyService.asLocalDateTime()` 归一化（也统一了 JSON 时间格式为 ISO）。
4. **`INSERT … RETURNING id`、`ON CONFLICT`、`ALTER SEQUENCE` 是 PG 专属**：
   已改成 KeyHolder（列名数组 `new String[]{"id"}`——注意不能用
   `Statement.RETURN_GENERATED_KEYS`，PG 驱动会把整行当生成键返回导致
   `getKey()` 多键报错）、`INSERT IGNORE` / `ON CONFLICT` 按产品名分支、
   `ALTER TABLE … AUTO_INCREMENT = 1` / `ALTER SEQUENCE` 按产品名分支。
5. **重复注册的报错文案两库不同**：PG 是 `duplicate key`，MySQL 是
   `Duplicate entry …`。注册接口按小写同时匹配两者。
6. **test_audit.py 的 A10 停用步骤原来写死 psql（只改 PG）**：
   已按 BASE_URL 端口分支，B 版走 mysql 客户端经 socket 以 root 执行
   UPDATE（不写密码进文件）。断言本身未动。
7. **MySQL 客户端直连默认字符集是 latin1**：命令行操作中文时加
   `--default-character-set=utf8mb4`，否则中文会被二次编码入库。
8. **启动慢**：两个实例启动各需约 14–16 秒，健康检查至少等 20 秒；
   15 秒就去 curl 会误判为没起来。
9. **恢复 PG 快照必须先 dropdb + createdb**：pg_dump 不含 DROP，
   直接 `psql -f` 会残留测试产生的多余行（曾出现 23 用户）。
10. **MySQL 8.0.28**：阿里云镜像 MySQL-8.0/ 目录最高只有 8.0.28，
    glibc2.17 只有 minimal 包，因此用的是 8.0.28 glibc2.12 完整包
    （MD5 已验证）。仍是官方 MySQL 8.0.x，满足 SDD 的 MySQL 8 要求。

## 验证记录（2026-11-06 首次双库验证，jar 24,890,644 字节）

- A 版：后端测试 46/46（test_api 15、test_full_flow 16、test_audit 15），
  移动端 16/16。
- B 版：后端测试 46/46，移动端 16/16。
- 隔离：A 新建活动 B 查不到；恢复 A 快照后 B 计数不变（21/4/11/0/0/0，
  4 个活动含移动端测试发布的那 1 个）；端口与日志互不干扰
  （A 只提 8080、B 只提 8081，日志无交叉引用）。
- 不变量：两库容量超限、重复参与、孤儿行全 0；两库最终恢复到
  21/3/11/0/0/0，双实例以正常参数（120/10）运行中。
