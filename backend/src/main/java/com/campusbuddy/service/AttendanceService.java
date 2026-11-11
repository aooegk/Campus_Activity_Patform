package com.campusbuddy.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AttendanceService {
    private static final long ROTATION_SECONDS = 300;
    private static final ZoneOffset CAMPUS_OFFSET = ZoneOffset.ofHours(8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> DECISIONS = Set.of("PRESENT", "ABSENT", "EXCUSED", "UNVERIFIED");
    private final JdbcTemplate jdbc;
    private final BuddyService buddy;

    private record Activity(long id, long organizer, String title, boolean cancelled,
                            LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        LocalDateTime checkInEnd() {
            var limit = start.plusMinutes(30);
            return end.isBefore(limit) ? end : limit;
        }
        boolean checkInOpen() {
            return !cancelled && !now.isBefore(start.minusMinutes(15)) && now.isBefore(checkInEnd());
        }
        boolean reviewOpen() {
            return !cancelled && !now.isBefore(end) && now.isBefore(end.plusHours(24));
        }
        long slot() { return now.toEpochSecond(CAMPUS_OFFSET) / ROTATION_SECONDS; }
    }

    public AttendanceService(JdbcTemplate jdbc, BuddyService buddy) {
        this.jdbc = jdbc;
        this.buddy = buddy;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void capture(long activityId) {
        captureRoster(lock(activityId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> view(long activityId, long actor) {
        var activity = lock(activityId);
        boolean organizer = activity.organizer() == actor;
        captureRoster(activity);
        var own = record(activityId, actor);
        boolean eligible = eligible(activity, actor, own);
        if (!organizer && !eligible && (own.isEmpty() || own.get("checked_in_at") == null))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅本活动正式成员可查看出勤");
        var out = new LinkedHashMap<String, Object>();
        out.put("check_in_open", activity.checkInOpen());
        out.put("review_open", organizer && activity.reviewOpen());
        out.put("eligible", eligible);
        out.put("own", own.isEmpty() ? Map.of() : Map.of(
                "checked_in", own.get("checked_in_at") != null,
                "checked_in_at", own.get("checked_in_at") == null ? "" : own.get("checked_in_at"),
                "review_status", own.get("review_status") == null ? "" : own.get("review_status"),
                "review_reason", own.get("review_reason") == null ? "" : own.get("review_reason")));
        if (organizer) {
            out.put("rows", jdbc.queryForList(
                    "SELECT r.user_id, u.nickname, r.checked_in_at, r.review_status, r.review_reason, r.version " +
                    "FROM attendance_record r JOIN app_user u ON u.id=r.user_id " +
                    "WHERE r.activity_id=? AND r.eligible=TRUE ORDER BY r.user_id", activityId));
        }
        return out;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> code(long activityId, long actor) {
        var activity = lock(activityId);
        requireOrganizer(activity, actor);
        if (!activity.checkInOpen()) throw new IllegalStateException("当前不在签到时间内");
        captureRoster(activity);
        var meta = ensureMeta(activityId);
        String seed = (String) meta.get("code_seed");
        if (seed == null) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            seed = HexFormat.of().formatHex(random);
            jdbc.update("UPDATE attendance_activity SET code_seed=? WHERE activity_id=?", seed, activityId);
        }
        var expiry = LocalDateTime.ofEpochSecond((activity.slot() + 1) * ROTATION_SECONDS, 0, CAMPUS_OFFSET);
        if (activity.checkInEnd().isBefore(expiry)) expiry = activity.checkInEnd();
        return Map.of("code", currentCode(seed, activity.slot()), "expires_at", expiry.toString());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> checkIn(long activityId, long actor, String submitted) {
        var activity = lock(activityId);
        captureRoster(activity);
        var record = record(activityId, actor);
        if (record.get("checked_in_at") != null)
            return Map.of("ok", true, "checked_in_at", record.get("checked_in_at"));
        if (!activity.checkInOpen()) throw new IllegalStateException("当前不在签到时间内");
        if (!eligible(activity, actor, record))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅活动正式成员可签到");
        var meta = ensureMeta(activityId);
        String seed = (String) meta.get("code_seed");
        if (seed == null) throw new IllegalStateException("组织者尚未获取签到码");
        if (record.isEmpty()) {
            jdbc.update("INSERT INTO attendance_record(activity_id,user_id) VALUES (?,?)", activityId, actor);
            record = record(activityId, actor);
        }
        Object storedLimit = record.get("failed_until");
        LocalDateTime failedUntil = storedLimit == null ? null : BuddyService.asLocalDateTime(storedLimit);
        boolean activeLimit = failedUntil != null && activity.now().isBefore(failedUntil);
        int attempts = activeLimit ? ((Number) record.get("failed_count")).intValue() : 0;
        if (attempts >= 5) return Map.of("ok", false, "code", "CHECKIN_RATE_LIMIT", "error", "错误次数过多，请稍后再试");

        String expected = currentCode(seed, activity.slot());
        if (submitted == null || !submitted.matches("[0-9]{6}") || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), submitted.getBytes(StandardCharsets.US_ASCII))) {
            LocalDateTime until = activeLimit ? failedUntil : activity.now().plusMinutes(5);
            jdbc.update("UPDATE attendance_record SET failed_count=?, failed_until=? WHERE activity_id=? AND user_id=?",
                    attempts + 1, Timestamp.valueOf(until), activityId, actor);
            return Map.of("ok", false, "code", "CHECKIN_WRONG_CODE", "error", "签到码不正确或已轮换");
        }
        Timestamp checkedInAt = Timestamp.valueOf(activity.now());
        jdbc.update("UPDATE attendance_record SET checked_in_at=?, failed_count=0, failed_until=NULL " +
                "WHERE activity_id=? AND user_id=?", checkedInAt, activityId, actor);
        return Map.of("ok", true, "checked_in_at", record(activityId, actor).get("checked_in_at"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> review(long activityId, long actor, long userId, long expectedVersion,
                                      String status, String reason) {
        var activity = lock(activityId);
        requireOrganizer(activity, actor);
        if (!activity.reviewOpen()) throw new IllegalStateException("只能在活动结束后 24 小时内核对出勤");
        if (status == null || !DECISIONS.contains(status)) throw new IllegalStateException("出勤状态不合法");
        captureRoster(activity);
        var current = record(activityId, userId);
        if (!inSnapshot(current))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "出勤资格记录不存在");
        long version = ((Number) current.get("version")).longValue();
        if (version != expectedVersion) throw new ResponseStatusException(HttpStatus.CONFLICT, "出勤记录已更新");
        boolean checkedIn = current.get("checked_in_at") != null;
        boolean unchangedFact = current.get("review_status") == null
                && ("PRESENT".equals(status) && checkedIn || "UNVERIFIED".equals(status) && !checkedIn);
        if (actor == userId && (!unchangedFact || !checkedIn))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织者本人的补签或改判需由管理员处理");
        int length = reason.codePointCount(0, reason.length());
        if (length > 500 || (!unchangedFact && length < 10))
            throw new IllegalStateException("补正、核实缺席或豁免需填写 10–500 字依据");
        version++;
        jdbc.update("UPDATE attendance_record SET review_status=?, review_reason=?, version=? " +
                "WHERE activity_id=? AND user_id=?", status, reason, version, activityId, userId);
        jdbc.update("INSERT INTO attendance_review_event(activity_id,user_id,version,reviewer_id,status,reason,decided_at) " +
                        "VALUES (?,?,?,?,?,?,?)", activityId, userId, version, actor, status, reason, Timestamp.valueOf(activity.now()));
        buddy.notify(userId, activityId, null, "变更", "活动「" + activity.title() + "」出勤结果已更新，请查看活动详情。",
                "notify:attendance:" + activityId + ":" + userId + ":" + version);
        return Map.of("ok", true, "version", version, "review_status", status);
    }

    private Activity lock(long activityId) {
        var row = buddy.lockActivity(activityId);
        return new Activity(activityId, ((Number) row.get("organizer_id")).longValue(), (String) row.get("title"),
                "CANCELLED".equals(row.get("status")), BuddyService.asLocalDateTime(row.get("start_time")),
                BuddyService.asLocalDateTime(row.get("end_time")), LocalDateTime.now());
    }

    private void requireOrganizer(Activity activity, long actor) {
        if (activity.organizer() != actor)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅组织者可操作出勤");
    }

    private Map<String, Object> ensureMeta(long activityId) {
        var rows = jdbc.queryForList("SELECT code_seed,snapshot_at FROM attendance_activity WHERE activity_id=?", activityId);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO attendance_activity(activity_id) VALUES (?)", activityId);
            return Map.of();
        }
        return rows.get(0);
    }

    private Map<String, Object> record(long activityId, long userId) {
        var rows = jdbc.queryForList("SELECT * FROM attendance_record WHERE activity_id=? AND user_id=?", activityId, userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean eligible(Activity activity, long userId, Map<String, Object> record) {
        if (!activity.now().isBefore(activity.start())) return inSnapshot(record);
        return jdbc.queryForObject("SELECT COUNT(*) FROM participation WHERE activity_id=? AND user_id=? AND status='FORMAL'",
                Integer.class, activity.id(), userId) > 0;
    }

    private boolean inSnapshot(Map<String, Object> record) {
        Object value = record.get("eligible");
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private void captureRoster(Activity activity) {
        if (activity.cancelled() || activity.now().isBefore(activity.start())) return;
        if (ensureMeta(activity.id()).get("snapshot_at") != null) return;
        Timestamp start = Timestamp.valueOf(activity.start());
        List<Long> eligible = jdbc.queryForList("""
                SELECT p.user_id FROM participation p WHERE p.activity_id=? AND p.joined_at<=?
                  AND COALESCE(
                    (SELECT e.to_status FROM participation_event e WHERE e.participation_id=p.id AND e.occurred_at<=?
                     ORDER BY e.occurred_at DESC,e.id DESC LIMIT 1),
                    (SELECT e.from_status FROM participation_event e WHERE e.participation_id=p.id AND e.occurred_at>?
                     ORDER BY e.occurred_at,e.id LIMIT 1), p.status)='FORMAL'
                """, Long.class, activity.id(), start, start, start);
        for (long userId : eligible) {
            if (record(activity.id(), userId).isEmpty()) {
                jdbc.update("INSERT INTO attendance_record(activity_id,user_id,eligible) VALUES (?,?,TRUE)",
                        activity.id(), userId);
            } else {
                jdbc.update("UPDATE attendance_record SET eligible=TRUE WHERE activity_id=? AND user_id=?",
                        activity.id(), userId);
            }
        }
        jdbc.update("UPDATE attendance_activity SET snapshot_at=? WHERE activity_id=?",
                Timestamp.valueOf(activity.now()), activity.id());
    }

    private String currentCode(String seed, long slot) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(seed), "HmacSHA256"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(slot).array());
            int value = ByteBuffer.wrap(hash).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成签到码", e);
        }
    }
}
