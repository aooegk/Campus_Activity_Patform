package com.campusbuddy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 核心业务：报名 / 退出 / 递补确认 / 超时顺延，全部在事务内完成。
 * 并发控制：先 SELECT ... FOR UPDATE 锁定活动行，串行化同一活动的容量核算，
 * 等价于 SRS 要求的行锁；邀请终态用 WHERE status='PENDING' 条件更新，
 * 影响行数 != 1 即说明已被另一方抢先处理。
 * 有效占用 = 正式人数 + 未过期待确认邀请数，必须 <= capacity。
 */
@Service
public class BuddyService {
    private final JdbcTemplate jdbc;
    private final int inviteTtlSeconds;
    /** 启动时从 DataSource 取数据库产品名判断一次并缓存：MySQL 用 INSERT IGNORE，
     *  PostgreSQL 保持 ON CONFLICT DO NOTHING。两分支语义相同：event_key 冲突时静默跳过。 */
    private final boolean mysql;

    public BuddyService(JdbcTemplate jdbc, @Value("${app.invite-ttl-seconds:120}") int inviteTtlSeconds) {
        this.jdbc = jdbc;
        this.inviteTtlSeconds = inviteTtlSeconds;
        this.mysql = detectMysql(jdbc);
    }

    private static boolean detectMysql(JdbcTemplate jdbc) {
        try (var con = jdbc.getDataSource().getConnection()) {
            return con.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        } catch (Exception e) {
            throw new IllegalStateException("无法识别数据库产品", e);
        }
    }

    public int inviteTtlSeconds() { return inviteTtlSeconds; }

    /** 全站时间约定（2026-11-06 任务 1/2 根因，见当日 changes.md）：
     *  库里 TIMESTAMP 列存的是 UTC 墙钟时间：JVM 默认时区 UTC、PG now() UTC、
     *  Java LocalDateTime.now() 取 UTC 墙钟，三者一致；Jackson 按
     *  spring.jackson.time-zone=Asia/Shanghai 把存入的时刻渲染成 +08:00，
     *  前端直接显示即为用户填的本地时间。
     *  唯一的例外是发布入口：前端 datetime-local 发来的是上海墙钟，
     *  必须先转成 UTC 墙钟再入库；反过来，拼给用户看的文案要把 UTC 墙钟
     *  转回上海墙钟再格式化。不要改 JVM 时区或 Jackson 配置——那会
     *  打破 PG now() 生成的 created_at 等字段的既有换算。 */
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 上海墙钟 → UTC 墙钟（入库用）。 */
    public static LocalDateTime shanghaiToUtcWall(LocalDateTime shanghaiWall) {
        return shanghaiWall.atZone(SHANGHAI).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** UTC 墙钟 → 上海墙钟（展示用）。 */
    public static LocalDateTime utcWallToShanghai(LocalDateTime utcWall) {
        return utcWall.atZone(ZoneOffset.UTC).withZoneSameInstant(SHANGHAI).toLocalDateTime();
    }

    /** UTC 墙钟 → 上海墙钟 "yyyy-MM-dd HH:mm"（通知文案用，无 T、无纳秒）。 */
    public static String fmtShanghaiMinute(LocalDateTime utcWall) {
        return utcWallToShanghai(utcWall).format(MINUTE_FMT);
    }

    private static Timestamp ts(LocalDateTime t) { return Timestamp.valueOf(t); }

    /** 数据库时间列转 LocalDateTime：MySQL 驱动把 DATETIME/TIMESTAMP 列返回为
     *  LocalDateTime，PostgreSQL 驱动返回为 Timestamp；两者都归一化，避免
     *  (Timestamp) 强转在 MySQL 上抛 ClassCastException。
     *  同时统一 toString() 的输出格式（ISO），两库返回的 JSON 时间格式一致。 */
    private static LocalDateTime asLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        throw new IllegalStateException("无法识别的时间类型: " + v);
    }

    private void notify(Long userId, Long activityId, Long invitationId,
                        String type, String content, String eventKey) {
        String sql = mysql
                ? "INSERT IGNORE INTO notification(user_id, activity_id, invitation_id, type, content, event_key) " +
                        "VALUES (?,?,?,?,?,?)"
                : "INSERT INTO notification(user_id, activity_id, invitation_id, type, content, event_key) " +
                        "VALUES (?,?,?,?,?,?) ON CONFLICT (user_id, event_key) DO NOTHING";
        jdbc.update(sql, userId, activityId, invitationId, type, content, eventKey);
    }

    private int formalCount(long activityId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM participation WHERE activity_id=? AND status='FORMAL'",
                Integer.class, activityId);
    }

    private int reservedCount(long activityId, LocalDateTime now) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                "WHERE p.activity_id=? AND i.status='PENDING' AND i.expires_at > ?",
                Integer.class, activityId, ts(now));
    }

    private int waitingCount(long activityId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM participation WHERE activity_id=? AND status='WAITING'",
                Integer.class, activityId);
    }

    /** 在同一事务内：只要还有空位且有候补，就按入队时间依次发邀请。 */
    private void promote(long activityId, LocalDateTime now) {
        while (true) {
            // 先锁活动行：退出/报名/超时三条路都在同一把锁下核算容量，避免超时顺延与报名并发超员
            var act = jdbc.queryForMap("SELECT capacity, deadline, status FROM activity WHERE id=? FOR UPDATE", activityId);
            if (!"OPEN".equals(act.get("status"))) return;
            LocalDateTime deadline = asLocalDateTime(act.get("deadline"));
            if (!now.isBefore(deadline)) return;
            int vacancy = ((Number) act.get("capacity")).intValue()
                    - formalCount(activityId) - reservedCount(activityId, now);
            if (vacancy <= 0) return;
            var waiting = jdbc.queryForList(
                    "SELECT id, user_id FROM participation WHERE activity_id=? AND status='WAITING' " +
                    "ORDER BY queued_at, id LIMIT 1", activityId);
            if (waiting.isEmpty()) return;
            long partId = ((Number) waiting.get(0).get("id")).longValue();
            long userId = ((Number) waiting.get(0).get("user_id")).longValue();
            LocalDateTime expires = now.plusSeconds(inviteTtlSeconds);
            if (expires.isAfter(deadline)) expires = deadline;
            jdbc.update("UPDATE participation SET status='INVITED', updated_at=? WHERE id=?", ts(now), partId);
            String eventKey = "invite:" + partId + ":" + now;
            jdbc.update("INSERT INTO invitation(participation_id, status, issued_at, expires_at, event_key) " +
                            "VALUES (?,'PENDING',?,?,?)", partId, ts(now), ts(expires), eventKey);
            Long invId = jdbc.queryForObject(
                    "SELECT id FROM invitation WHERE event_key=?", Long.class, eventKey);
            String title = jdbc.queryForObject(
                    "SELECT title FROM activity WHERE id=?", String.class, activityId);
            notify(userId, activityId, invId, "邀请",
                    "活动「" + title + "」已有空缺，请在 " + fmtShanghaiMinute(expires) + " 前确认是否参加。",
                    "notify:" + eventKey);
        }
    }

    @Transactional
    public Map<String, Object> signup(long activityId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        // 锁定活动行：同一活动的并发报名在此串行
        var act = jdbc.queryForMap("SELECT * FROM activity WHERE id=? FOR UPDATE", activityId);
        if (!"OPEN".equals(act.get("status")))
            throw new IllegalStateException("活动不在报名期");
        if (!now.isBefore(asLocalDateTime(act.get("deadline"))))
            throw new IllegalStateException("已过报名截止时间");
        var existing = jdbc.queryForList(
                "SELECT * FROM participation WHERE activity_id=? AND user_id=?", activityId, userId);
        if (!existing.isEmpty()) {
            String st = (String) existing.get(0).get("status");
            if (st.equals("FORMAL") || st.equals("WAITING") || st.equals("INVITED")) {
                // 幂等：重试返回原结果，不产生第二条记录
                return detail(activityId, userId);
            }
            // 退出/过期后重新报名：复用同一条记录，候补时间重置（SRS 3.3.5.2）
        }
        int capacity = ((Number) act.get("capacity")).intValue();
        boolean hasQueue = waitingCount(activityId) > 0;
        int occupied = formalCount(activityId) + reservedCount(activityId, now);
        String status = (!hasQueue && occupied < capacity) ? "FORMAL" : "WAITING";
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO participation(activity_id, user_id, status, queued_at, joined_at, updated_at) " +
                            "VALUES (?,?,?, ?, ?, ?)",
                    activityId, userId, status,
                    status.equals("WAITING") ? ts(now) : null,
                    status.equals("FORMAL") ? ts(now) : null, ts(now));
        } else {
            jdbc.update("UPDATE participation SET status=?, queued_at=?, joined_at=?, updated_at=? " +
                            "WHERE activity_id=? AND user_id=?",
                    status, status.equals("WAITING") ? ts(now) : null,
                    status.equals("FORMAL") ? ts(now) : null, ts(now), activityId, userId);
        }
        String title = (String) act.get("title");
        if (status.equals("FORMAL")) {
            notify(userId, activityId, null, "报名", "你已成功报名活动「" + title + "」。",
                    "notify:formal:" + activityId + ":" + userId + ":" + now);
        } else {
            notify(userId, activityId, null, "候补", "活动「" + title + "」已满员，你已进入候补队列。",
                    "notify:waiting:" + activityId + ":" + userId + ":" + now);
        }
        return detail(activityId, userId);
    }

    @Transactional
    public Map<String, Object> withdraw(long participationId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        var part = jdbc.queryForMap(
                "SELECT * FROM participation WHERE id=? FOR UPDATE", participationId);
        if (!Objects.equals(((Number) part.get("user_id")).longValue(), userId))
            throw new IllegalStateException("只能操作自己的参与记录");
        String st = (String) part.get("status");
        long activityId = ((Number) part.get("activity_id")).longValue();
        // 锁定活动行，保证退出与递补在同一事务内完成，不出现“名额已释放、邀请未发出”的窗口
        jdbc.queryForMap("SELECT id FROM activity WHERE id=? FOR UPDATE", activityId);
        if (st.equals("FORMAL")) {
            jdbc.update("UPDATE participation SET status='WITHDRAWN', updated_at=? WHERE id=?", ts(now), participationId);
            promote(activityId, now);
        } else if (st.equals("WAITING")) {
            jdbc.update("UPDATE participation SET status='WITHDRAWN', updated_at=? WHERE id=?", ts(now), participationId);
        } else if (st.equals("INVITED")) {
            // 待确认者放弃 = 拒绝邀请，释放预留并顺延
            jdbc.update("UPDATE invitation SET status='REJECTED', responded_at=? " +
                            "WHERE participation_id=? AND status='PENDING'", ts(now), participationId);
            jdbc.update("UPDATE participation SET status='WITHDRAWN', updated_at=? WHERE id=?", ts(now), participationId);
            promote(activityId, now);
        } else {
            throw new IllegalStateException("当前状态不可退出：" + st);
        }
        // 任务 3-2：退出产生一条通知，否则退出前后两条「报名成功」无法分辨。
        // 类型用 '变更'（notification 表 CHECK 只允许 报名/候补/邀请/变更，不改表）。
        String title = jdbc.queryForObject("SELECT title FROM activity WHERE id=?", String.class, activityId);
        notify(userId, activityId, null, "变更", "你已退出活动「" + title + "」。",
                "notify:withdraw:" + participationId + ":" + now);
        return detail(activityId, userId);
    }

    @Transactional
    public Map<String, Object> respondInvitation(long invitationId, long userId, boolean accept) {
        LocalDateTime now = LocalDateTime.now();
        // 锁顺序必须全局一致：participation -> invitation -> activity。
        // 旧代码先锁 invitation 再更新 participation，与 withdraw（先锁 participation
        // 再更新 invitation）形成锁顺序倒置，并发接受 + 退出时 PostgreSQL 报死锁。
        // 这里先无锁读出 participation_id，再按 participation -> invitation 的顺序加锁，
        // 加锁后重新读 invitation，以加锁后的状态为准。
        var inv0 = jdbc.queryForMap("SELECT participation_id FROM invitation WHERE id=?", invitationId);
        long partId0 = ((Number) inv0.get("participation_id")).longValue();
        var part = jdbc.queryForMap("SELECT * FROM participation WHERE id=? FOR UPDATE", partId0);
        if (!Objects.equals(((Number) part.get("user_id")).longValue(), userId))
            throw new IllegalStateException("只能操作自己的邀请");
        var inv = jdbc.queryForMap("SELECT * FROM invitation WHERE id=? FOR UPDATE", invitationId);
        long activityId = ((Number) part.get("activity_id")).longValue();
        jdbc.queryForMap("SELECT id FROM activity WHERE id=? FOR UPDATE", activityId);
        if (!"PENDING".equals(inv.get("status"))) {
            return Map.of("invitation_status", inv.get("status"), "note", "该邀请已被处理，返回当前结果");
        }
        LocalDateTime expires = asLocalDateTime(inv.get("expires_at"));
        if (!now.isBefore(expires) && !now.isEqual(expires)) {
            // 确认接口先于扫描任务到达时，也必须按实际过期时刻拒绝（SRS 3.3.6.5）
            jdbc.update("UPDATE invitation SET status='EXPIRED' WHERE id=?", invitationId);
            jdbc.update("UPDATE participation SET status='EXPIRED', updated_at=? WHERE id=?",
                    ts(now), part.get("id"));
            promote(activityId, now);
            throw new IllegalStateException("邀请已过期，已顺延至下一位候补");
        }
        if (accept) {
            int changed = jdbc.update(
                    "UPDATE invitation SET status='ACCEPTED', responded_at=? WHERE id=? AND status='PENDING'",
                    ts(now), invitationId);
            if (changed != 1) throw new IllegalStateException("邀请已被另一方处理");
            jdbc.update("UPDATE participation SET status='FORMAL', joined_at=?, updated_at=? WHERE id=?",
                    ts(now), ts(now), part.get("id"));
        } else {
            jdbc.update("UPDATE invitation SET status='REJECTED', responded_at=? WHERE id=? AND status='PENDING'",
                    ts(now), invitationId);
            jdbc.update("UPDATE participation SET status='WITHDRAWN', updated_at=? WHERE id=?",
                    ts(now), part.get("id"));
            promote(activityId, now);
        }
        return detail(activityId, userId);
    }

    /** 超时顺延：仅成功抢占邀请状态的任务可处理一次（条件更新 + 事务）。 */
    @Transactional
    public boolean expireOne(long invitationId) {
        LocalDateTime now = LocalDateTime.now();
        // 与 respondInvitation / withdraw 保持同一锁顺序：先锁 participation，再条件更新 invitation。
        // 旧代码先更新 invitation 再更新 participation，与 withdraw（先锁 participation）
        // 在超时与退出并发时同样可能死锁。
        var inv0 = jdbc.queryForMap("SELECT participation_id FROM invitation WHERE id=?", invitationId);
        long partId = ((Number) inv0.get("participation_id")).longValue();
        var part = jdbc.queryForMap("SELECT activity_id, user_id FROM participation WHERE id=? FOR UPDATE", partId);
        long activityId = ((Number) part.get("activity_id")).longValue();
        long userId = ((Number) part.get("user_id")).longValue();
        int changed = jdbc.update(
                "UPDATE invitation SET status='EXPIRED' WHERE id=? AND status='PENDING' AND expires_at <= ?",
                invitationId, ts(now));
        if (changed != 1) return false;
        jdbc.update("UPDATE participation SET status='EXPIRED', updated_at=? WHERE id=?", ts(now), partId);
        // 任务 3-3：邀请过期要让被邀请人知道。原邀请通知标为已读（不再挂「未读」），
        // 再发一条过期通知。类型用 '邀请'（notification 表 CHECK 允许，不改表）。
        String title = jdbc.queryForObject("SELECT title FROM activity WHERE id=?", String.class, activityId);
        jdbc.update("UPDATE notification SET read_at=? WHERE invitation_id=? AND type='邀请' AND read_at IS NULL",
                ts(now), invitationId);
        notify(userId, activityId, invitationId, "邀请",
                "活动「" + title + "」的递补邀请已过期，名额已顺延至下一位候补。",
                "notify:expired:" + invitationId);
        promote(activityId, now);
        return true;
    }

    public Map<String, Object> detail(long activityId, long userId) {
        var act = jdbc.queryForMap("SELECT * FROM activity WHERE id=?", activityId);
        LocalDateTime now = LocalDateTime.now();
        var mine = jdbc.queryForList(
                "SELECT * FROM participation WHERE activity_id=? AND user_id=?", activityId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activity_id", activityId);
        out.put("title", act.get("title"));
        // 任务 1：报名接口返回补齐介绍与条件，否则前端用返回体覆盖详情时会变空白
        out.put("description", act.get("description"));
        out.put("conditions", act.get("conditions"));
        out.put("capacity", act.get("capacity"));
        out.put("formal", formalCount(activityId));
        out.put("reserved", reservedCount(activityId, now));
        out.put("waiting", waitingCount(activityId));
        out.put("status", act.get("status"));
        if (!mine.isEmpty()) {
            var m = mine.get(0);
            out.put("my_status", m.get("status"));
            out.put("participation_id", m.get("id"));
            out.put("my_queued_at", m.get("queued_at") == null ? null : utcWallToShanghai(asLocalDateTime(m.get("queued_at"))).toString());
            var inv = jdbc.queryForList(
                    "SELECT * FROM invitation WHERE participation_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1",
                    m.get("id"));
            if (!inv.isEmpty()) {
                out.put("invitation_id", inv.get(0).get("id"));
                // 邀请截止是 UTC 墙钟入库，转回上海墙钟再返回，否则详情页显示的
                // 邀请截止会比「我的活动」（Jackson 渲染）早 8 小时、两处打架。
                out.put("invitation_expires_at", utcWallToShanghai(asLocalDateTime(inv.get(0).get("expires_at"))).toString());
            }
            // 候补排名：排队中按 queued_at, id 排序
            if ("WAITING".equals(m.get("status"))) {
                var queue = jdbc.queryForList(
                        "SELECT id FROM participation WHERE activity_id=? AND status='WAITING' " +
                        "ORDER BY queued_at, id", activityId);
                for (int i = 0; i < queue.size(); i++) {
                    if (Objects.equals(queue.get(i).get("id"), m.get("id"))) { out.put("my_rank", i + 1); break; }
                }
            }
        }
        return out;
    }
}
