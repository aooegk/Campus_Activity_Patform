# 构建与运行踩坑记录（本地便携环境，2026-10-29 已验证）

## Maven 打包
- 本机出网走代理，Java 不读 `http_proxy` 环境变量：需在 Maven settings 里补 `<proxies>`（见 /tmp/maven-settings.xml 的生成方式），且 Java 必须加 `-Djava.net.preferIPv4Stack=true`，否则 Java 在 IPv6 代理隧道解析上会失败（curl 能通、Java 不通）。
- 代理做 TLS 拦截：需把 /run/hatch/egress-tls/ca-bundle.pem 导入一份 JDK cacerts 副本，用 `-Djavax.net.ssl.trustStore=...` 指定。
- 成功命令（在 backend 目录）：`MAVEN_OPTS="-Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStore=/tmp/egress-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit" mvn -s /tmp/maven-settings.xml package -DskipTests`，产物 `target/campus-buddy-1.0.0.jar`（22 MB）。
- settings-aliyun.xml 本身只含镜像，不含代理和凭据；不要把代理凭据写进仓库文件。

## 运行时中文建表
- 本机 locale 是 POSIX，Java 默认字符集不是 UTF-8，Spring 读 schema.sql 会把中文分类名读成乱码，导致种子数据建表约束失败。启动必须加 `-Dfile.encoding=UTF-8`（run.bat 里已写死，勿删）。
- 曾因旧库残留乱码约束导致失败：处理方式是重建数据库 campus_buddy 再启动，不要只改 schema.sql（CREATE TABLE IF NOT EXISTS 不会更新旧约束）。

## 验收（2026-10-29，真实 PostgreSQL 5433）
- `tests/test_api.py`（INVITE_TTL=10、SWEEP_SECONDS=2）：15 项全过，另手动确认过期邀请 accept 返回 EXPIRED。
- 覆盖：报名转正式/候补、退出递补、接受转正式、超时顺延、20 并发恰好 4 正式、同一账号并发 10 次一条记录。
