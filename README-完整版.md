# 找个搭子 · 校园活动组队与候补平台（完整版）

计算机学院《现代软件工程》课程 13 小组：陈子逸、张梓轩、吉浩铭、唐裕翔。
选题对应课程设计参考题目“社团活动管理与报名系统”，需求文档《校园活动组队平台需求规格说明书》（CEM-SRS-0001）。

## 这个包里有什么

| 目录 / 文件 | 内容 |
|---|---|
| `汇报/CampusBuddy-第一次汇报-浅色版.pptx` | 第一次汇报 PPT，10 页，16:9，每页带讲稿备注，约 4–5 分钟；浅米白底 + 陶土橙，背景图为现生浅色水彩 |
| `汇报/CampusBuddy-网页展示.html` | 同一套汇报内容的网页版，单文件（含背景图），浏览器打开，方向键 / 按钮翻页，点“讲稿”看备注 |
| `汇报/assets/` | PPT / 网页用的 3 张背景图（封面、内容页、桌游） |
| `汇报/build_ppt_v3.py` | 生成浅色版 PPT 的脚本（python-pptx） |
| `运行包/campus-buddy-1.0.0.jar` | 已打包好的后端 + 前端（Spring Boot 3.3.5，22 MB），不需要再装 Node |
| `运行包/run.bat` | Windows 双击启动脚本 |
| `运行包/README-Windows.md` | Windows 运行说明（前置、演示顺序、改配置、常见问题） |
| `源码/backend/` | Spring Boot 源码：接口、报名 / 退出 / 递补事务、超时扫描、种子数据 |
| `源码/frontend/` | Vue 3 源码：活动大厅、详情、我的活动、通知、发布活动 |
| `源码/database/schema.sql` | PostgreSQL 建表脚本（6 张表，与后端内嵌脚本一致） |
| `源码/tests/test_api.py`、`test_api.log` | 对真实 PostgreSQL 的验收脚本与通过日志 |
| `BUILD-NOTES.md` | 本地构建踩坑记录（Maven 代理、中文建表字符集） |

## 技术路线

Vue 3 + Spring Boot 3 + PostgreSQL。依赖源全部换成国内可直连的：
Maven 走阿里云镜像，npm 走 npmmirror；前端打包进后端 Jar，不依赖国外 CDN。

核心数据库规则（需求文档第 4、5 章）：
- 有效占用 = 正式人数 + 未过期待确认邀请数，必须 ≤ 活动人数上限
- 同一活动同一用户只有一条当前参与记录
- 邀请只能有一种终态（接受 / 拒绝 / 超时 / 失效互相竞争，先成功者生效）
- 候补按入队时间排，时间相同按参与记录编号排；退出重进重新排队

## 最快上手（Windows，演示用）

1. 装 JDK 17、PostgreSQL 16；新建数据库：`psql -U postgres -c "CREATE DATABASE campus_buddy ENCODING 'UTF8';"`
2. 解压后进 `运行包`，双击 `run.bat`，浏览器打开 http://localhost:8080
3. 演示账号密码都是 `123456`：组织者 `organizer`，同学 `demo01` … `demo20`

演示顺序（对应 PPT 第 7 页）：羽毛球活动（上限 4，已有 3 正式）→ 第 4 人报名转正式，第 5 人候补第 1 → 第 4 人退出 → 候补第 1 收到限时邀请 → 接受转正式，或等超时自动顺延。

## 验收结果（2026-10-29，真实 PostgreSQL）

`源码/tests/test_api.py` 15 项全过，另手动确认过期邀请再点接受返回已过期：
报名转正式 / 候补、退出递补、接受转正式、超时顺延、20 人并发报名上限 4 恰好 4 正式、同一账号并发 10 次只有一条记录。

## 从源码重新构建（可选，不演示必需）

- 前端：进 `源码/frontend`，`npm install`（已配 npmmirror），`npm run build`，把 `dist` 拷入 `源码/backend/src/main/resources/static/`。
- 后端：进 `源码/backend`，`mvn -s settings-aliyun.xml package -DskipTests`，产物在 `target/campus-buddy-1.0.0.jar`。
- 注意：启动必须加 `-Dfile.encoding=UTF-8`（run.bat 里已写好），否则中文建表脚本会乱码；本地代理环境的坑见 `BUILD-NOTES.md`。


## 源码启动时的演示模式

后端默认使用 PostgreSQL。使用 MySQL 时，设置 `DB_URL=jdbc:mysql://127.0.0.1:3306/campus_buddy`、`DB_DRIVER=com.mysql.cj.jdbc.Driver` 和 `DB_SCHEMA=classpath:schema-mysql.sql`。两种数据库均通过 `DB_USER` 和 `DB_PASSWORD` 设置连接账号，并用 `java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar target/campus-buddy-1.0.0.jar` 启动。

仅在独立的演示或测试数据库上设置 `DEMO_ENABLED=true`。开启后，空库会初始化演示账号和活动，登录后可使用恢复入口。网页加 `?demo=1` 显示演示页脚。默认关闭种子数据和重置接口。

运行 `tests/test_api.py` 或 `tests/test_full_flow.py` 前，在测试后端设置 `INVITE_TTL=10`、`SWEEP_SECONDS=2`、`INVITE_DAY_START_HOUR=0` 和 `INVITE_DAY_END_HOUR=24`。通过 `BASE_URL` 指向测试后端。这两份测试会重置该库的演示数据。

公网部署使用 HTTPS，并设置 `SESSION_SECURE=true`。浏览器会话通过 HttpOnly Cookie 保存，原生 App 继续使用 Bearer 令牌。


## 分支验收

在独立演示库上启用 `DEMO_ENABLED=true`。用 `BASE_URL` 指定后端地址，按需运行 `tests/test_api.py`、`tests/test_full_flow.py` 和对应的专项脚本。部分脚本会重置演示库。

网页冒烟脚本为 `tests/test_web_smoke.cjs`。将 `CAMPUS_PLAYWRIGHT` 指向已安装的 Playwright Core 模块，将 `CAMPUS_BROWSER` 指向本机 Chromium 或 Edge 可执行文件，再运行 `node tests/test_web_smoke.cjs`。如已运行 `tests/test_attendance.py` 生成评价测试数据，可设置 `CAMPUS_VERIFY_FEEDBACK=1` 验证出勤和评价页面。

本分支的变更和待办见 [CHANGELOG.md](CHANGELOG.md)。
