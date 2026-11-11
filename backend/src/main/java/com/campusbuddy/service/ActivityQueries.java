package com.campusbuddy.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ActivityQueries {
    private static final Map<String, Integer> PERIODS = Map.of("凌晨", 0, "上午", 6, "下午", 12, "晚上", 18);
    private static final Set<String> CAMPUSES = Set.of("望江", "华西", "江安", "其他");
    private static final String COUNTS = """
            (SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='FORMAL') AS formal_count,
            CASE WHEN a.status='OPEN' AND a.deadline > ? THEN
                (SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='WAITING')
                ELSE 0 END AS waiting_count,
            CASE WHEN a.status='OPEN' AND a.deadline > ? THEN
                (SELECT COUNT(*) FROM invitation i JOIN participation p ON p.id=i.participation_id
                 WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > ?)
                ELSE 0 END AS reserved_count
            """;
    private final JdbcTemplate jdbc;

    public ActivityQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object lobby(Long actor, String query, String category, String campus, String period, Integer page, Integer size) {
        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        StringBuilder where = new StringBuilder(" WHERE a.status NOT IN ('CANCELLED','ENDED') AND a.end_time > ? ");
        List<Object> filters = new ArrayList<>();
        filters.add(timestamp);

        String term = query == null ? "" : query.strip();
        if (term.codePointCount(0, term.length()) > 50) throw new IllegalStateException("搜索词最多 50 字");
        if (!term.isEmpty()) {
            where.append("""
                    AND (LOWER(a.title) LIKE ? ESCAPE '!' OR LOWER(a.description) LIKE ? ESCAPE '!'
                         OR LOWER(a.conditions) LIKE ? ESCAPE '!' OR LOWER(a.location) LIKE ? ESCAPE '!'
                    """);
            String pattern = "%" + term.toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filters.addAll(List.of(pattern, pattern, pattern, pattern));
            Integer start = PERIODS.get(term);
            if (start != null) {
                where.append("OR EXTRACT(HOUR FROM a.start_time) BETWEEN ? AND ? ");
                filters.addAll(List.of(start, start + 5));
            }
            where.append(") ");
        }
        if (category != null && !category.isBlank() && !"全部".equals(category)) {
            where.append("AND a.category=? ");
            filters.add(category);
        }
        if (campus != null && !campus.isBlank()) {
            if (!CAMPUSES.contains(campus)) throw new IllegalStateException("校区筛选不合法");
            if ("其他".equals(campus)) {
                where.append("AND a.location NOT LIKE '%望江%' AND a.location NOT LIKE '%华西%' AND a.location NOT LIKE '%江安%' ");
            } else {
                where.append("AND a.location LIKE ? ");
                filters.add("%" + campus + "%");
            }
        }
        if (period != null && !period.isBlank()) {
            Integer start = PERIODS.get(period);
            if (start == null) throw new IllegalStateException("时段筛选不合法");
            where.append("AND EXTRACT(HOUR FROM a.start_time) BETWEEN ? AND ? ");
            filters.addAll(List.of(start, start + 5));
        }

        String from = " FROM activity a JOIN app_user u ON u.id=a.organizer_id ";
        String columns = "SELECT a.*, u.nickname AS organizer_name, " + COUNTS;
        var arguments = new ArrayList<Object>(List.of(timestamp, timestamp, timestamp));
        String join = "";
        if (actor != null) {
            columns += ", mine.status AS my_status";
            join = "LEFT JOIN participation mine ON mine.activity_id=a.id AND mine.user_id=? ";
            arguments.add(actor);
        }
        arguments.addAll(filters);
        String sql = columns + from + join + where + " ORDER BY a.created_at DESC, a.id DESC";
        if (page == null && size == null) return jdbc.queryForList(sql, arguments.toArray());
        return paginate(sql, arguments, from + where, filters, page, size, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object organized(long actor, Integer page, Integer size) {
        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        String where = " FROM activity a WHERE a.organizer_id=?";
        String base = "SELECT a.*, " + COUNTS + where;
        var arguments = new ArrayList<Object>(List.of(timestamp, timestamp, timestamp, actor));
        if (page == null && size == null)
            return jdbc.queryForList(base + " ORDER BY a.start_time, a.id", arguments.toArray());
        return paginate(base + " ORDER BY a.created_at DESC, a.id DESC", arguments,
                where, List.of(actor), page, size, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> roster(long activityId, long actor, String status, Integer page, Integer size) {
        if (!Set.of("FORMAL", "INVITED", "WAITING").contains(status))
            throw new IllegalStateException("名单状态需为 FORMAL、INVITED 或 WAITING");
        var activity = jdbc.queryForList("SELECT organizer_id FROM activity WHERE id=?", Long.class, activityId);
        if (activity.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
        if (activity.get(0) != actor) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅组织者可查看名单");

        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        String columns = "SELECT p.id, p.status, p.queued_at, p.joined_at, u.nickname";
        String from = " FROM participation p JOIN activity a ON a.id=p.activity_id JOIN app_user u ON u.id=p.user_id ";
        var arguments = new ArrayList<Object>();
        String order = "p.joined_at, p.id";
        if ("INVITED".equals(status)) {
            columns += ", i.id AS invitation_id, i.expires_at";
            from += """
                    JOIN invitation i ON i.id=(
                        SELECT j.id FROM invitation j WHERE j.participation_id=p.id
                          AND j.status='PENDING' AND j.expires_at > ?
                        ORDER BY j.id DESC LIMIT 1
                    )
                    """;
            arguments.add(timestamp);
            order = "i.expires_at, i.id";
        } else if ("WAITING".equals(status)) {
            order = "p.queued_at, p.id";
        }
        from += " WHERE p.activity_id=? AND p.status=? ";
        arguments.addAll(List.of(activityId, status));
        if (!"FORMAL".equals(status)) {
            from += "AND a.status='OPEN' AND a.deadline > ? ";
            arguments.add(timestamp);
        }
        return paginate(columns + from + "ORDER BY " + order, arguments, from, arguments, page, size, now);
    }

    private Map<String, Object> paginate(String select, List<Object> arguments, String from, List<Object> filters,
                                         Integer requestedPage, Integer requestedSize, LocalDateTime now) {
        int page = requestedPage == null ? 1 : requestedPage;
        int size = requestedSize == null ? 10 : requestedSize;
        if (page < 1 || size < 1 || size > 50) throw new IllegalStateException("页码从 1 开始，每页 1–50 条");
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, filters.toArray());
        var pagedArguments = new ArrayList<>(arguments);
        pagedArguments.addAll(List.of(size, ((long) page - 1) * size));
        var items = jdbc.queryForList(select + " LIMIT ? OFFSET ?", pagedArguments.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size, "server_time", now.toString());
    }
}
