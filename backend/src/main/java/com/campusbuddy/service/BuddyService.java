package com.campusbuddy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
    /** 任务 8（2026-11-08 补丁）：递补邀请只在白天时段发出，默认 8:00–22:00（服务器时间 Asia/Shanghai），
     *  可配：app.invite-day-start-hour / app.invite-day-end-hour。 */
    private final int dayStartHour;
    private final int dayEndHour;
    /** 启动时从 DataSource 取数据库产品名判断一次并缓存：MySQL 用 INSERT IGNORE，
     *  PostgreSQL 保持 ON CONFLICT DO NOTHING。两分支语义相同：event_key 冲突时静默跳过。 */
    private final boolean mysql;

    public BuddyService(JdbcTemplate jdbc, @Value("${app.invite-ttl-seconds:120}") int inviteTtlSeconds,
                        @Value("${app.invite-day-start-hour:8}") int dayStartHour,
                        @Value("${app.invite-day-end-hour:22}") int dayEndHour) {
        this.jdbc = jdbc;
        this.inviteTtlSeconds = inviteTtlSeconds;
        this.dayStartHour = dayStartHour;
        this.dayEndHour = dayEndHour;
        this.mysql = detectMysql(jdbc);
    }

    /** 任务 8：白天时段判断。时段为 [start, end)，默认 8:00–22:00；22:00 整算夜间。 */
    public boolean isDaytime(LocalDateTime now) {
        int h = now.getHour();
        return h >= dayStartHour && h < dayEndHour;
    }

    private static boolean detectMysql(JdbcTemplate jdbc) {
        try (var con = jdbc.getDataSource().getConnection()) {
            return con.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        } catch (Exception e) {
            throw new IllegalStateException("无法识别数据库产品", e);
        }
    }

    public int inviteTtlSeconds() { return inviteTtlSeconds; }

    /** 全站时间约定（2026-11-06 时区统一改造：全系统东八区直通）：
     *  所有时间字段均为东八区（Asia/Shanghai）墙钟，直接存取，不做任何时区转换。
     *  JVM 以 -Duser.timezone=Asia/Shanghai 启动，LocalDateTime.now() 即东八区墙钟；
     *  数据库服务器时区同样为 Asia/Shanghai，SQL now()/NOW() 默认值与 Java 写入一致。
     *  Timestamp.valueOf / asLocalDateTime 只按 JVM 默认时区在墙钟与毫秒之间换算，
     *  不改变时刻值。Jackson 仍配 spring.jackson.time-zone=Asia/Shanghai：
     *  PostgreSQL 驱动把 TIMESTAMP 列返回为 Timestamp（Date 子类），Jackson 按该
     *  时区序列化才是墙钟原值；MySQL 驱动返回 LocalDateTime，按原值序列化。 */
    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 东八区墙钟 → "yyyy-MM-dd HH:mm"（通知文案用，无 T、无纳秒）。
     *  直通格式化：入参已是东八区墙钟，不做任何时区转换（2026-11-06 时区统一改造，
     *  旧实现经 utcWallToShanghai 换算已删除）。 */
    public static String fmtShanghaiMinute(LocalDateTime wall) {
        return wall.format(MINUTE_FMT);
    }

    /** 任务 6 + 19（2026-11-09）：通知时间全格式 "yyyy-MM-dd 周X 时段 HH:mm"，
     *  时段词统一四段（凌晨 0–6 / 上午 6–12 / 下午 12–18 / 晚上 18–24，与
     *  大厅时段筛选口径一致；不再用「中午」）。 */
    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private static String periodOf(int hour) {
        if (hour < 6) return "凌晨";
        if (hour < 12) return "上午";
        if (hour < 18) return "下午";
        return "晚上";
    }

    public static String fmtFullMinute(LocalDateTime t) {
        return String.format("%04d-%02d-%02d %s %s %02d:%02d",
                t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                WEEK[t.getDayOfWeek().getValue() % 7], periodOf(t.getHour()),
                t.getHour(), t.getMinute());
    }

    /** 起止全格式：同日只写一次日期与星期，时段词相同时结束只写时间，
     *  如 "2026-11-10 周二 上午 10:41–下午 12:41"；跨日两边写全。 */
    public static String fmtFullRange(LocalDateTime start, LocalDateTime end) {
        if (start.toLocalDate().equals(end.toLocalDate())) {
            String p1 = periodOf(start.getHour()), p2 = periodOf(end.getHour());
            String endPart = p1.equals(p2)
                    ? String.format("%02d:%02d", end.getHour(), end.getMinute())
                    : p2 + " " + String.format("%02d:%02d", end.getHour(), end.getMinute());
            return fmtFullMinute(start) + "–" + endPart;
        }
        return fmtFullMinute(start) + "–" + fmtFullMinute(end);
    }

    private static Timestamp ts(LocalDateTime t) { return Timestamp.valueOf(t); }

    /** 数据库时间列转 LocalDateTime：MySQL 驱动把 DATETIME/TIMESTAMP 列返回为
     *  LocalDateTime，PostgreSQL 驱动返回为 Timestamp；两者都归一化，避免
     *  (Timestamp) 强转在 MySQL 上抛 ClassCastException。
     *  同时统一 toString() 的输出格式（ISO），两库返回的 JSON 时间格式一致。
     *  只做类型归一化，不做时区转换：Timestamp 按 JVM 默认时区（东八区）解读，
     *  得到的即库里存的东八区墙钟原值。 */
    private static LocalDateTime asLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        throw new IllegalStateException("无法识别的时间类型: " + v);
    }

    private void notify(Long userId, Long activityId, Long invitationId,
                        String type, String content, String eventKey) {
        notifyWithSender(userId, activityId, invitationId, type, content, eventKey, null);
    }

    /** 带发送人的通知写入（任务 9 留言/回复用 sender_id；其余通知 sender 为空）。
     *  sender_id 列由 2026-11-09 迁移 001 加入，双库 schema 已同步。 */
    private void notifyWithSender(Long userId, Long activityId, Long invitationId,
                        String type, String content, String eventKey, Long senderId) {
        String sql = mysql
                ? "INSERT IGNORE INTO notification(user_id, activity_id, invitation_id, type, content, event_key, sender_id) " +
                        "VALUES (?,?,?,?,?,?,?)"
                : "INSERT INTO notification(user_id, activity_id, invitation_id, type, content, event_key, sender_id) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT (user_id, event_key) DO NOTHING";
        jdbc.update(sql, userId, activityId, invitationId, type, content, eventKey, senderId);
    }

    /** 组织者通知（任务 2，2026-11-07）：报名 / 进候补 / 退出 / 递补确认时通知组织者。
     *  SDD §6.8 矩阵：正式报名、退出、递补确认通知本人和组织者；DECISIONS.md 决定 1
     *  另定进候补也通知组织者。组织者本人操作时不重复发（报名人与组织者同一人时
     *  只保留本人那条通知）。内容含报名人昵称 + 活动名 + 时间地点（REQUIREMENTS 硬约束 4）。 */
    private void notifyOrganizer(long organizerId, long actorUserId, long activityId,
                                 String type, String content, String eventKey) {
        if (organizerId == actorUserId) return;
        notify(organizerId, activityId, null, type, content, eventKey);
    }

    private String nickname(long userId) {
        return jdbc.queryForObject("SELECT nickname FROM app_user WHERE id=?", String.class, userId);
    }

    /** 活动时间地点后缀（任务 6，2026-11-09）：时间写全
     *  "时间 2026-11-10 周二 上午 10:41–下午 12:41，地点 xxx"（开始 + 结束、
     *  星期、时段词）。调用方查活动时须同时取 start_time 与 end_time。 */
    private String activityWhenWhere(Map<String, Object> act) {
        return "时间 " + fmtFullRange(asLocalDateTime(act.get("start_time")), asLocalDateTime(act.get("end_time")))
                + "，地点 " + act.get("location");
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
            String title = jdbc.queryForObject(
                    "SELECT title FROM activity WHERE id=?", String.class, activityId);
            if (!isDaytime(now)) {
                // 任务 8（补丁，文档不改）：夜间不发正式邀请，只给候补第 1 名发一条提示；
                // 邀请等白天由 SweepService.backfillDaytimeInvites 补发。event_key 按
                // (活动, 参与记录) 去重：同一空缺反复触发只提示一次；候补第 1 名换人时
                // （partId 不同）会给新第 1 名再提示一次。窗口、期限、顺延规则都不变。
                notify(userId, activityId, null, "邀请",
                        "活动「" + title + "」已有空缺，将于白天 " + dayStartHour + ":00 后发出邀请，请留意通知。",
                        "notify:night-hint:" + activityId + ":" + partId);
                return;
            }
            LocalDateTime expires = now.plusSeconds(inviteTtlSeconds);
            if (expires.isAfter(deadline)) expires = deadline;
            jdbc.update("UPDATE participation SET status='INVITED', updated_at=? WHERE id=?", ts(now), partId);
            String eventKey = "invite:" + partId + ":" + now;
            jdbc.update("INSERT INTO invitation(participation_id, status, issued_at, expires_at, event_key) " +
                            "VALUES (?,'PENDING',?,?,?)", partId, ts(now), ts(expires), eventKey);
            Long invId = jdbc.queryForObject(
                    "SELECT id FROM invitation WHERE event_key=?", Long.class, eventKey);
            notify(userId, activityId, invId, "邀请",
                    "活动「" + title + "」已有空缺，请在 " + fmtFullMinute(expires) + " 前确认是否参加。",
                    "notify:" + eventKey);
        }
    }

    /** 任务 8：白天补发夜间暂缓的递补邀请。由 SweepService 每轮调用；夜间直接返回。
     *  只扫有候补的 OPEN 活动，实际发邀请仍走 promote()（容量 / 截止 / 白天三重门都在里面），
     *  所以不会多发、不会在截止后发。 */
    @Transactional
    public void backfillDaytimeInvites(LocalDateTime now) {
        if (!isDaytime(now)) return;
        var ids = jdbc.queryForList(
                "SELECT DISTINCT a.id FROM activity a JOIN participation p ON p.activity_id=a.id AND p.status='WAITING' " +
                "WHERE a.status='OPEN' AND a.deadline > ? ORDER BY a.id",
                Long.class, ts(now));
        for (Long id : ids) {
            try { promote(id, now); } catch (Exception e) { /* 下一轮重试 */ }
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
        long organizerId = ((Number) act.get("organizer_id")).longValue();
        String whenWhere = activityWhenWhere(act);
        if (status.equals("FORMAL")) {
            // 报名成功通知内容含活动名称及时间地点（REQUIREMENTS 硬约束 4）。
            notify(userId, activityId, null, "报名", "你已成功报名活动「" + title + "」，" + whenWhere + "。",
                    "notify:formal:" + activityId + ":" + userId + ":" + now);
            notifyOrganizer(organizerId, userId, activityId, "报名",
                    "「" + nickname(userId) + "」已报名活动「" + title + "」，" + whenWhere + "。",
                    "notify:org:formal:" + activityId + ":" + userId + ":" + now);
        } else {
            notify(userId, activityId, null, "候补", "活动「" + title + "」已满员，你已进入候补队列，" + whenWhere + "。",
                    "notify:waiting:" + activityId + ":" + userId + ":" + now);
            notifyOrganizer(organizerId, userId, activityId, "候补",
                    "「" + nickname(userId) + "」已进入活动「" + title + "」的候补队列，" + whenWhere + "。",
                    "notify:org:waiting:" + activityId + ":" + userId + ":" + now);
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
        // 类型存 '退出'（2026-11-08 任务 3：notification 表 CHECK 已放开 '退出'，
        // 见 runs/2026-11-08/migrations/001-*；历史 '变更' 退出通知由迁移一并改掉，
        // 前端按 '变更'→'退出' 的映射可保留作兼容，也可去掉）。
        var actW = jdbc.queryForMap(
                "SELECT organizer_id, title, location, start_time, end_time FROM activity WHERE id=?", activityId);
        String title = (String) actW.get("title");
        notify(userId, activityId, null, "退出", "你已退出活动「" + title + "」。",
                "notify:withdraw:" + participationId + ":" + now);
        // 任务 2：退出也通知组织者（SDD §6.8 矩阵；之前只通知本人是漏发）。
        notifyOrganizer(((Number) actW.get("organizer_id")).longValue(), userId, activityId, "退出",
                "「" + nickname(userId) + "」已退出活动「" + title + "」，" + activityWhenWhere(actW) + "。",
                "notify:org:withdraw:" + participationId + ":" + now);
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
            // 任务 11（2026-11-10）：与 expireOne 同口径，按当时有无下一位候补区分文案。
            boolean hasNext = waitingCount(activityId) > 0;
            promote(activityId, now);
            throw new IllegalStateException(hasNext ? "邀请已过期，已顺延至下一位候补" : "邀请已过期，名额已空出，可重新报名");
        }
        if (accept) {
            int changed = jdbc.update(
                    "UPDATE invitation SET status='ACCEPTED', responded_at=? WHERE id=? AND status='PENDING'",
                    ts(now), invitationId);
            if (changed != 1) throw new IllegalStateException("邀请已被另一方处理");
            jdbc.update("UPDATE participation SET status='FORMAL', joined_at=?, updated_at=? WHERE id=?",
                    ts(now), ts(now), part.get("id"));
            // 任务 4：接受递补邀请后，原邀请通知标已读（与过期情形 expireOne 一致）。
            jdbc.update("UPDATE notification SET read_at=? WHERE invitation_id=? AND type='邀请' AND read_at IS NULL",
                    ts(now), invitationId);
            // 任务 2：递补确认通知组织者（SDD §6.8 矩阵）。
            var actA = jdbc.queryForMap(
                    "SELECT organizer_id, title, location, start_time, end_time FROM activity WHERE id=?", activityId);
            notifyOrganizer(((Number) actA.get("organizer_id")).longValue(), userId, activityId, "报名",
                    "「" + nickname(userId) + "」已确认参加活动「" + actA.get("title") + "」，" + activityWhenWhere(actA) + "。",
                    "notify:org:accept:" + invitationId);
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
        // 任务 11（2026-11-10）：按当时有无下一位有效候补区分文案——有下一位才写顺延，
        // 无人写「名额已空出，可重新报名」（参与记录已置 EXPIRED，不计入 waitingCount）。
        boolean hasNext = waitingCount(activityId) > 0;
        notify(userId, activityId, invitationId, "邀请",
                hasNext
                        ? "活动「" + title + "」的递补邀请已过期，名额已顺延至下一位候补。"
                        : "活动「" + title + "」的递补邀请已过期，名额已空出，可重新报名。",
                "notify:expired:" + invitationId);
        promote(activityId, now);
        return true;
    }

    /** 任务 1（2026-11-09，P0）：组织者取消活动时给全部受影响成员发取消通知。
     *  SDD §6.8 通知矩阵：取消通知所有受影响成员。收件人 = 取消前状态为
     *  FORMAL / WAITING / INVITED 的全部参与者（含组织者本人作为参与者时，
     *  考研党复现用例就是组织者本人既是发布者又是参与者；不含已退出/已过期者）。
     *  内容写清原定时间（起止 + 星期 + 时段词，与任务 6 同口径）。
     *  event_key 按 (活动, 用户) 去重；已取消活动二次取消时收件人为空，
     *  不重发、仍返回 ok（幂等，任务 20 后端口径）。 */
    @Transactional
    public Map<String, Object> cancelActivity(long activityId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        var act = jdbc.queryForMap(
                "SELECT organizer_id, title, location, start_time, end_time FROM activity WHERE id=?", activityId);
        if (!Objects.equals(((Number) act.get("organizer_id")).longValue(), userId))
            throw new IllegalStateException("只有组织者可以取消活动");
        var recipients = jdbc.queryForList(
                "SELECT user_id FROM participation WHERE activity_id=? AND status IN ('FORMAL','WAITING','INVITED')",
                activityId);
        // 与 respondInvitation / withdraw 保持同一锁顺序：先锁参与记录，再改邀请。
        jdbc.queryForList("SELECT id FROM participation WHERE activity_id=? " +
                "AND status IN ('FORMAL','WAITING','INVITED') FOR UPDATE", activityId);
        jdbc.update("UPDATE activity SET status='CANCELLED', updated_at=? WHERE id=?",
                ts(now), activityId);
        jdbc.update("UPDATE invitation SET status='INVALID' WHERE participation_id IN " +
                "(SELECT id FROM participation WHERE activity_id=?) AND status='PENDING'", activityId);
        jdbc.update("UPDATE participation SET status='CANCELLED' WHERE activity_id=? AND status IN ('FORMAL','WAITING','INVITED')", activityId);
        String range = fmtFullRange(asLocalDateTime(act.get("start_time")), asLocalDateTime(act.get("end_time")));
        String title = (String) act.get("title");
        for (var p : recipients) {
            long uid = ((Number) p.get("user_id")).longValue();
            notify(uid, activityId, null, "取消",
                    "活动「" + title + "」已取消，原定时间 " + range + "，地点 " + act.get("location") + "。",
                    "notify:cancel:" + activityId + ":" + uid);
        }
        return Map.of("ok", true);
    }

    /** 任务 2（2026-11-09，P0）：组织者报名截止前改活动时间（开始 / 结束 / 截止）。
     *  SRS 表 3.3-1 R5：活动信息、容量、时间仅在报名截止前可改；修改后仍须满足
     *  “发出时间早于过期时间，且过期时间不晚于报名截止”，否则拒绝修改。
     *  本次只做时间，不扩容量与活动信息；不产生新活动、不迁移报名（原 id 与原
     *  报名记录不变）。改后给全部正式成员与候补成员（含待确认邀请者、含组织者
     *  本人作为参与者时）发「变更」通知，内容写清旧时间 → 新时间。
     *  R5 细则（SRS §3.3.3.3）：新报名截止早于已有待确认邀请的过期时间时
     *  拒绝修改并提示；延后截止不延长已有邀请的过期时间（本方法不动 invitation）。 */
    @Transactional
    public Map<String, Object> reschedule(long activityId, long organizerId,
                                          LocalDateTime deadline, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now();
        var act = jdbc.queryForMap("SELECT * FROM activity WHERE id=? FOR UPDATE", activityId);
        if (!Objects.equals(((Number) act.get("organizer_id")).longValue(), organizerId))
            throw new IllegalStateException("只有组织者可以修改活动时间");
        if (!"OPEN".equals(act.get("status")))
            throw new IllegalStateException("活动不在报名期，不可改时间");
        if (!now.isBefore(asLocalDateTime(act.get("deadline"))))
            throw new IllegalStateException("报名已截止，不可改时间");
        if (!(now.isBefore(deadline) && !deadline.isAfter(start) && start.isBefore(end)))
            throw new IllegalStateException("时间需满足：现在 < 报名截止 ≤ 开始 < 结束");
        var pending = jdbc.queryForList(
                "SELECT i.expires_at FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                "WHERE p.activity_id=? AND i.status='PENDING'", activityId);
        for (var row : pending) {
            if (deadline.isBefore(asLocalDateTime(row.get("expires_at"))))
                throw new IllegalStateException("新报名截止早于已有待确认邀请的过期时间，无法修改");
        }
        String oldRange = fmtFullRange(asLocalDateTime(act.get("start_time")), asLocalDateTime(act.get("end_time")));
        var recipients = jdbc.queryForList(
                "SELECT user_id FROM participation WHERE activity_id=? AND status IN ('FORMAL','WAITING','INVITED')",
                activityId);
        jdbc.update("UPDATE activity SET deadline=?, start_time=?, end_time=?, updated_at=? WHERE id=?",
                ts(deadline), ts(start), ts(end), ts(now), activityId);
        String newRange = fmtFullRange(start, end);
        String title = (String) act.get("title");
        for (var p : recipients) {
            long uid = ((Number) p.get("user_id")).longValue();
            notify(uid, activityId, null, "变更",
                    "活动「" + title + "」时间已变更：原定 " + oldRange + "，现为 " + newRange + "，地点 " + act.get("location") + "。",
                    "notify:reschedule:" + activityId + ":" + uid + ":" + now);
        }
        return Map.of("ok", true, "activity_id", activityId,
                "deadline", deadline.toString(), "start_time", start.toString(), "end_time", end.toString());
    }

    /** 任务 9（2026-11-09，P1）：站内留言——详情页「给组织者留言」。
     *  存储复用 notification 表（type='留言'，sender_id=留言人，不新增表；
     *  type 值与 sender_id 列由当日迁移 001 加入）。登录用户报名前后均可留言，
     *  纯文本一句话，上限 200 字（超长拒绝并提示，不截断）。已取消活动不可留言。
     *  留言行 user_id=组织者（组织者在通知列表里也能看到），仅组织者可见：
     *  公开详情不返回 messages，只有组织者调详情时 listMessages() 才返回。 */
    @Transactional
    public Map<String, Object> postMessage(long activityId, long userId, String content) {
        var act = jdbc.queryForMap("SELECT organizer_id, title, status FROM activity WHERE id=?", activityId);
        if ("CANCELLED".equals(act.get("status")))
            throw new IllegalStateException("活动已取消，无法留言");
        if (content == null || content.isBlank())
            throw new IllegalStateException("留言内容不能为空");
        if (content.codePointCount(0, content.length()) > 200)
            throw new IllegalStateException("留言最多 200 字");
        long organizerId = ((Number) act.get("organizer_id")).longValue();
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        // 任务 5（2026-11-10）：留言通知带活动标题，如「留言（羽毛球双打）：……」，
        // 组织者名下多个活动的留言通知能凭标题区分；listMessages 展示时再剥掉此前缀。
        final String text = "留言（" + act.get("title") + "）：" + content.trim();
        final String eventKey = "msg:" + activityId + ":" + userId + ":" + now;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO notification(user_id, activity_id, type, content, event_key, sender_id) " +
                    "VALUES (?,?,?,?,?,?)", new String[]{"id"});
            ps.setLong(1, organizerId);
            ps.setLong(2, activityId);
            ps.setString(3, "留言");
            ps.setString(4, text);
            ps.setString(5, eventKey);
            ps.setLong(6, userId);
            return ps;
        }, keyHolder);
        return Map.of("ok", true, "id", keyHolder.getKey().longValue());
    }

    /** 任务 9：组织者名单视图的留言列表（带留言人昵称与时间，每条附其唯一回复）。
     *  回复行本身也是 type='留言' 的 notification 行（event_key 以 msg-reply: 开头），
     *  在此排除，只作为 reply 附在原留言下。 */
    public List<Map<String, Object>> listMessages(long activityId) {
        return queryMessages(activityId, null);
    }

    /** 任务 17（2026-11-10，P2）：留言者本人查自己发过的留言及组织者回复。
     *  只返回 sender_id = 本人的留言行，他人留言不可见；组织者走 listMessages() 看全部。 */
    public List<Map<String, Object>> listMyMessages(long activityId, long userId) {
        return queryMessages(activityId, userId);
    }

    /** 任务 9 / 17 公共查询：留言行（排除回复行本身，每条附唯一回复）。
     *  senderId 为 null 时返回全部留言（组织者视角），否则只返回该 sender 的留言。
     *  展示时剥掉任务 5（2026-11-10）写入通知的「留言（<标题>）：」前缀——
     *  名单视图 / 本人留言区已在当活动语境下，标题是多余的；通知列表仍显示带标题的原文。 */
    private List<Map<String, Object>> queryMessages(long activityId, Long senderId) {
        String title = jdbc.queryForObject("SELECT title FROM activity WHERE id=?", String.class, activityId);
        String prefix = "留言（" + title + "）：";
        String sql = "SELECT n.*, u.nickname AS sender_nickname FROM notification n JOIN app_user u ON u.id=n.sender_id " +
                "WHERE n.activity_id=? AND n.type='留言' AND n.event_key NOT LIKE 'msg-reply:%' ";
        var rows = senderId == null
                ? jdbc.queryForList(sql + "ORDER BY n.created_at, n.id", activityId)
                : jdbc.queryForList(sql + "AND n.sender_id=? ORDER BY n.created_at, n.id", activityId, senderId);
        for (var row : rows) {
            String content = (String) row.get("content");
            if (content != null && content.startsWith(prefix)) {
                row.put("content", content.substring(prefix.length()));
            }
            long mid = ((Number) row.get("id")).longValue();
            var replies = jdbc.queryForList(
                    "SELECT content, created_at FROM notification WHERE event_key LIKE ? ORDER BY id LIMIT 1",
                    "msg-reply:" + mid + ":%");
            row.put("reply", replies.isEmpty() ? null : replies.get(0));
        }
        return rows;
    }

    /** 任务 9：组织者在名单视图回一条（走站内通知，留言人收到回复通知）。
     *  每条留言只能回一条（防聊起来变聊天串）；回复内容同样 ≤200 字；
     *  只有组织者可回，回复对象是原留言人。 */
    @Transactional
    public Map<String, Object> replyMessage(long activityId, long organizerId, long messageId, String content) {
        var act = jdbc.queryForMap("SELECT organizer_id FROM activity WHERE id=?", activityId);
        if (!Objects.equals(((Number) act.get("organizer_id")).longValue(), organizerId))
            throw new IllegalStateException("只有组织者可以回复留言");
        var msg = jdbc.queryForMap(
                "SELECT * FROM notification WHERE id=? AND activity_id=? AND type='留言'", messageId, activityId);
        if (((String) msg.get("event_key")).startsWith("msg-reply:"))
            throw new IllegalStateException("不能回复一条回复");
        if (msg.get("sender_id") == null)
            throw new IllegalStateException("留言记录异常，无留言人");
        long senderId = ((Number) msg.get("sender_id")).longValue();
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE event_key LIKE ?", Integer.class,
                "msg-reply:" + messageId + ":%");
        if (n != null && n > 0)
            throw new IllegalStateException("该留言已回复过");
        if (content == null || content.isBlank())
            throw new IllegalStateException("回复内容不能为空");
        if (content.codePointCount(0, content.length()) > 200)
            throw new IllegalStateException("回复最多 200 字");
        notifyWithSender(senderId, activityId, null, "留言", content.trim(),
                "msg-reply:" + messageId + ":" + LocalDateTime.now(), organizerId);
        return Map.of("ok", true);
    }

    /** 任务 3（2026-11-10，P1）：详情人数公开计数——未登录也能看 formal/reserved/waiting
     *  （SRS §3.3.2.2 详情字段清单本就含正式/候补人数）；不含名单、联系方式与本人状态。 */
    public Map<String, Object> publicCounts(long activityId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formal", formalCount(activityId));
        out.put("reserved", reservedCount(activityId, now));
        out.put("waiting", waitingCount(activityId));
        return out;
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
            out.put("my_queued_at", m.get("queued_at") == null ? null : asLocalDateTime(m.get("queued_at")).toString());
            var inv = jdbc.queryForList(
                    "SELECT * FROM invitation WHERE participation_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1",
                    m.get("id"));
            if (!inv.isEmpty()) {
                out.put("invitation_id", inv.get(0).get("id"));
                // 库里存的就是东八区墙钟，直接返回，不再换算。
                out.put("invitation_expires_at", asLocalDateTime(inv.get(0).get("expires_at")).toString());
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
