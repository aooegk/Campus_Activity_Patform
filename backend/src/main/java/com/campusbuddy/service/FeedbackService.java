package com.campusbuddy.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class FeedbackService {
    private static final String ELIGIBLE = """
            FROM feedback f
            JOIN participation p ON p.id=f.participation_id
            JOIN attendance_record r ON r.activity_id=p.activity_id AND r.user_id=p.user_id
            WHERE p.activity_id=? AND r.eligible=TRUE AND r.review_status='PRESENT'
            """;
    private final JdbcTemplate jdbc;
    private final BuddyService buddy;

    public FeedbackService(JdbcTemplate jdbc, BuddyService buddy) {
        this.jdbc = jdbc;
        this.buddy = buddy;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(long activityId, Long actor) {
        var activity = jdbc.queryForMap("SELECT organizer_id, status, end_time FROM activity WHERE id=?", activityId);
        var now = LocalDateTime.now();
        var end = BuddyService.asLocalDateTime(activity.get("end_time"));
        if ("CANCELLED".equals(activity.get("status")) || now.isBefore(end))
            return Map.of("total", 0, "average", 0, "editable", false);

        var result = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(AVG(f.score), 0) AS average " + ELIGIBLE, activityId));
        result.put("editable", actor != null && now.isBefore(end.plusDays(7)) && eligible(activityId, actor));
        if (actor != null) {
            var own = jdbc.queryForList("""
                    SELECT f.score, f.content, f.created_at FROM feedback f
                    JOIN participation p ON p.id=f.participation_id WHERE p.activity_id=? AND p.user_id=?
                    """, activityId, actor);
            if (!own.isEmpty()) result.put("own", own.get(0));
            if (((Number) activity.get("organizer_id")).longValue() == actor) {
                result.put("entries", jdbc.queryForList("SELECT f.id, f.score, f.content, f.created_at " +
                        ELIGIBLE + "ORDER BY f.created_at DESC, f.id DESC", activityId));
            }
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> save(long activityId, long actor, int score, String content) {
        if (score < 1 || score > 5) throw new IllegalStateException("评分须为 1–5 的整数");
        if (content.codePointCount(0, content.length()) > 500)
            throw new IllegalStateException("评价正文最多 500 字");
        var activity = buddy.lockActivity(activityId);
        var now = LocalDateTime.now();
        var end = BuddyService.asLocalDateTime(activity.get("end_time"));
        if ("CANCELLED".equals(activity.get("status")) || now.isBefore(end) || !now.isBefore(end.plusDays(7)))
            throw new IllegalStateException("活动结束后 7 日内可提交或修改评价");
        if (!eligible(activityId, actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "出勤核对为到场后才可评价");
        long partId = jdbc.queryForObject("SELECT id FROM participation WHERE activity_id=? AND user_id=?",
                Long.class, activityId, actor);
        int existing = jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE participation_id=?",
                Integer.class, partId);
        if (existing == 0) {
            jdbc.update("INSERT INTO feedback(participation_id, score, content) VALUES (?,?,?)",
                    partId, score, content);
        } else {
            jdbc.update("UPDATE feedback SET score=?, content=? WHERE participation_id=?", score, content, partId);
        }
        return Map.of("ok", true, "score", score, "content", content);
    }

    private boolean eligible(long activityId, long actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE activity_id=? AND user_id=? " +
                        "AND eligible=TRUE AND review_status='PRESENT'", Integer.class, activityId, actor) > 0;
    }
}
