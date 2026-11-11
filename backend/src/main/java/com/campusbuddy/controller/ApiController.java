package com.campusbuddy.controller;

import com.campusbuddy.model.ActivityFields;
import com.campusbuddy.service.ActivityQueries;
import com.campusbuddy.service.AuthService;
import com.campusbuddy.service.BuddyService;
import com.campusbuddy.service.CommandReceipts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final BuddyService buddy;
    private final CommandReceipts receipts;
    private final ActivityQueries activityQueries;
    private final boolean demoEnabled;
    private final boolean secureSessionCookie;

    public ApiController(JdbcTemplate jdbc, AuthService auth, BuddyService buddy, CommandReceipts receipts,
                         ActivityQueries activityQueries,
                         @Value("${app.demo-enabled:false}") boolean demoEnabled,
                         @Value("${app.secure-session-cookie:false}") boolean secureSessionCookie) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.buddy = buddy;
        this.receipts = receipts;
        this.activityQueries = activityQueries;
        this.demoEnabled = demoEnabled;
        this.secureSessionCookie = secureSessionCookie;
    }

    private long require(Long actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或会话已失效");
        return actor;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("ok", false, "error", e.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("ok", false, "error", e.getMessage()));
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EmptyResultDataAccessException e) {
        // 查不到记录（不存在的活动 / 参与记录 / 邀请）是客户端错误，不应返回 500。
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("ok", false, "error", "记录不存在"));
    }

    // ---- 健康 / 配置 ----
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "server_time", LocalDateTime.now().toString(),
                "invite_ttl_seconds", buddy.inviteTtlSeconds(), "demo_enabled", demoEnabled,
                "users", jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class),
                "activities", jdbc.queryForObject("SELECT COUNT(*) FROM activity", Integer.class));
    }

    // ---- 账号 ----
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        AuthService.LoginSession session = auth.login(body.get("account"), body.get("password"));
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误，或账号暂时锁定");
        return withSessionCookies(request, session.token(), session.csrf(), AuthService.SESSION_LIFETIME)
                .body(Map.of("ok", true, "token", session.token(), "user", auth.me(session.userId())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request,
            @RequestAttribute(value = SessionFilter.TOKEN, required = false) String token) {
        auth.logout(token);
        return withSessionCookies(request, "", "", Duration.ZERO).body(Map.of("ok", true));
    }

    private ResponseEntity.BodyBuilder withSessionCookies(HttpServletRequest request, String token, String csrf,
                                                           Duration lifetime) {
        boolean secure = secureSessionCookie || request.isSecure();
        ResponseCookie sessionCookie = ResponseCookie.from(SessionFilter.SESSION_COOKIE, token)
                .path("/api").httpOnly(true).secure(secure).sameSite("Lax").maxAge(lifetime).build();
        ResponseCookie csrfCookie = ResponseCookie.from(SessionFilter.CSRF_COOKIE, csrf)
                .path("/").secure(secure).sameSite("Lax").maxAge(lifetime).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie.toString(), csrfCookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String account = body.get("account"), password = body.get("password");
        if (account == null || account.length() < 4 || account.length() > 32)
            throw new IllegalStateException("账号需为 4-32 字符");
        if (demoEnabled) {
            if (password == null || password.length() < 6 || password.length() > 64)
                throw new IllegalStateException("密码需为 6–64 位");
        } else {
            AuthService.validatePassword(password);
        }
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isBlank()) {
            int end = account.offsetByCodePoints(0, Math.min(30, account.codePointCount(0, account.length())));
            nickname = account.substring(0, end);
        }
        if (nickname.codePointCount(0, nickname.length()) > 30)
            throw new IllegalStateException("昵称需为 1-30 字");
        try {
            jdbc.update("INSERT INTO app_user(account, password_hash, nickname) VALUES (?,?,?)",
                    account, AuthService.hash(password), nickname);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("账号已存在");
        }
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return Map.of("ok", true, "user", auth.me(require(actor)));
    }

    // 任务 10（2026-11-09）：改密码。登录态，传原密码 + 新密码；校验原密码、
    // 新密码符合 SDD 规则（8–64 位、含字母与数字）后更新散列并撤销全部旧会话
    // token（含当前 token，改完须重新登录）。注册接口不动。
    @PostMapping("/me/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body,
            HttpServletRequest request,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        auth.changePassword(me, body.get("old_password"), body.get("new_password"));
        return withSessionCookies(request, "", "", Duration.ZERO).body(Map.of("ok", true));
    }

    // ---- 活动大厅 ----
    @GetMapping("/activities")
    public Object activities(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category, @RequestParam(required = false) String campus,
            @RequestParam(required = false) String period, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return activityQueries.lobby(actor, q, category, campus, period, page, size);
    }

    @GetMapping("/activities/{id}/roster")
    public Map<String, Object> roster(@PathVariable long id, @RequestParam(defaultValue = "FORMAL") String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return activityQueries.roster(id, require(actor), status, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/activities/{id}")
    public Map<String, Object> activityDetail(@PathVariable long id,
            @RequestParam(name = "include_roster", defaultValue = "true") boolean includeRoster,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        Long me = actor;
        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        var act = jdbc.queryForMap("SELECT a.*, u.nickname AS organizer_name FROM activity a " +
                "JOIN app_user u ON u.id=a.organizer_id WHERE a.id=?", id);
        boolean organizer = me != null && Objects.equals(((Number) act.get("organizer_id")).longValue(), me);
        boolean archived = "CANCELLED".equals(act.get("status")) || "ENDED".equals(act.get("status"))
                || !now.isBefore(activityTime(act.get("end_time")));
        boolean open = !archived && "OPEN".equals(act.get("status")) && now.isBefore(activityTime(act.get("deadline")));
        Map<String, Object> out = new LinkedHashMap<>(act);
        if (me != null) out.putAll(buddy.detail(id, me, now));
        else out.putAll(buddy.publicCounts(id, now, open));
        if (archived && !organizer && !out.containsKey("participation_id"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在或仅向历史参与者开放");

        out.put("is_organizer", organizer);
        if (organizer && includeRoster) {
            out.put("roster", jdbc.queryForList(
                    "SELECT p.*, u.nickname FROM participation p JOIN app_user u ON u.id=p.user_id " +
                    "WHERE p.activity_id=? AND (p.status='FORMAL' OR (? AND (p.status='WAITING' OR " +
                    "(p.status='INVITED' AND EXISTS (SELECT 1 FROM invitation i WHERE i.participation_id=p.id " +
                    "AND i.status='PENDING' AND i.expires_at > ?))))) " +
                    "ORDER BY CASE p.status WHEN 'FORMAL' THEN 0 WHEN 'INVITED' THEN 1 ELSE 2 END, p.queued_at, p.id",
                    id, open, timestamp));
            out.put("pending_invitations", open ? jdbc.queryForList(
                    "SELECT i.*, u.nickname FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                    "JOIN app_user u ON u.id=p.user_id WHERE p.activity_id=? AND i.status='PENDING' " +
                    "AND i.expires_at > ? ORDER BY i.expires_at, i.id", id, timestamp) : List.of());
        }
        if (organizer) out.put("messages", buddy.listMessages(id));
        return out;
    }

    private LocalDateTime activityTime(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    @PostMapping("/activities")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /activities", body, () -> ResponseEntity.ok(createActivity(me, body)));
    }

    private Map<String, Object> createActivity(long me, Map<String, Object> body) {
        ActivityFields fields = ActivityFields.from(body);
        fields.validateTime(LocalDateTime.now());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO activity(organizer_id, title, category, description, conditions, location, capacity, deadline, start_time, end_time) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, me);
            ps.setString(2, fields.title());
            ps.setString(3, fields.category());
            ps.setString(4, fields.description());
            ps.setString(5, fields.conditions());
            ps.setString(6, fields.location());
            ps.setInt(7, fields.capacity());
            ps.setTimestamp(8, Timestamp.valueOf(fields.deadline()));
            ps.setTimestamp(9, Timestamp.valueOf(fields.start()));
            ps.setTimestamp(10, Timestamp.valueOf(fields.end()));
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/activities/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /activities/" + id + "/cancel", body, () -> {
            if (body == null || !Boolean.TRUE.equals(body.get("confirmed")))
                throw new IllegalStateException("请确认取消活动");
            Integer affected = null;
            if (body.get("expected_affected") != null) {
                try {
                    affected = Integer.parseInt(String.valueOf(body.get("expected_affected")));
                    if (affected < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("受影响人数格式不正确");
                }
            }
            return commandResponse(buddy.cancelActivity(id, me, activityVersion(body), ActivityFields.reason(body), affected));
        });
    }

    @PutMapping("/activities/{id}")
    public ResponseEntity<Map<String, Object>> updateActivity(@PathVariable long id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "PUT /activities/" + id, body, () -> {
            return commandResponse(buddy.updateActivity(id, me, activityVersion(body),
                    ActivityFields.from(body), ActivityFields.reason(body)));
        });
    }

    private long activityVersion(Map<String, Object> body) {
        try {
            long version = Long.parseLong(String.valueOf(body.get("version")));
            if (version < 0) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("缺少有效活动版本，请刷新详情");
        }
    }

    private ResponseEntity<Map<String, Object>> commandResponse(Map<String, Object> result) {
        return Boolean.FALSE.equals(result.get("ok"))
                ? ResponseEntity.status(HttpStatus.CONFLICT).body(result)
                : ResponseEntity.ok(result);
    }

    @PostMapping("/activities/{id}/reschedule")
    public ResponseEntity<Map<String, Object>> reschedule(@PathVariable long id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /activities/" + id + "/reschedule", body,
                () -> ResponseEntity.ok(buddy.reschedule(id, me, ActivityFields.time(body, "deadline"),
                        ActivityFields.time(body, "start_time"), ActivityFields.time(body, "end_time"))));
    }

    // 任务 9（2026-11-09）：站内留言——详情页「给组织者留言」，登录用户报名前后均可。
    @PostMapping("/activities/{id}/messages")
    public Map<String, Object> postMessage(@PathVariable long id,
            @RequestBody Map<String, String> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return buddy.postMessage(id, require(actor), body.get("content"));
    }

    // 任务 17（2026-11-10）：留言查询接口——组织者看全部留言，本人只看自己发过的
    // （各附组织者回复）；他人留言不可见。详情页「我发过的留言」区用此接口。
    @GetMapping("/activities/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        var act = jdbc.queryForMap("SELECT organizer_id FROM activity WHERE id=?", id);
        if (Objects.equals(((Number) act.get("organizer_id")).longValue(), me)) {
            return buddy.listMessages(id);
        }
        return buddy.listMyMessages(id, me);
    }

    // 任务 9：组织者在名单视图回一条（每条留言只能回一条，回复走站内通知给留言人）。
    @PostMapping("/activities/{id}/messages/{mid}/reply")
    public Map<String, Object> replyMessage(@PathVariable long id, @PathVariable long mid,
            @RequestBody Map<String, String> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return buddy.replyMessage(id, require(actor), mid, body.get("content"));
    }

    // ---- 报名 / 退出 / 递补 ----
    @PostMapping("/activities/{id}/signup")
    public ResponseEntity<Map<String, Object>> signup(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /activities/" + id + "/signup", body, () -> {
            boolean confirmed = body != null && Boolean.TRUE.equals(body.get("conditions_confirmed"));
            Long version = body != null && body.get("version") != null ? activityVersion(body) : null;
            return commandResponse(buddy.signup(id, me, confirmed, version));
        });
    }

    @PostMapping("/participations/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /participations/" + id + "/withdraw", null,
                () -> ResponseEntity.ok(buddy.withdraw(id, me)));
    }

    @PostMapping("/invitations/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /invitations/" + id + "/accept", null,
                () -> invitationResponse(buddy.respondInvitation(id, me, true), "ACCEPTED"));
    }

    @PostMapping("/invitations/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(actor);
        return receipts.execute(me, key, "POST /invitations/" + id + "/reject", null,
                () -> invitationResponse(buddy.respondInvitation(id, me, false), "REJECTED"));
    }

    private ResponseEntity<Map<String, Object>> invitationResponse(Map<String, Object> result, String expected) {
        String status = (String) result.get("invitation_status");
        if (status == null || expected.equals(status)) return ResponseEntity.ok(result);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "ok", false, "invitation_status", status,
                "error", "EXPIRED".equals(status) ? "邀请已过期" : "该邀请已处理，无法再次操作"));
    }

    // ---- 我的活动 / 通知 ----
    @GetMapping("/my")
    public List<Map<String, Object>> my(@RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return jdbc.queryForList("""
                SELECT p.*, a.title, a.location, a.capacity, a.start_time, a.end_time, a.deadline,
                       a.status AS activity_status, i.id AS invitation_id, i.expires_at AS invitation_expires_at,
                       CASE
                           WHEN a.status IN ('CANCELLED','ENDED') OR a.end_time <= ? THEN 'history'
                           WHEN p.status='FORMAL' THEN 'signed'
                           WHEN a.deadline <= ? THEN 'history'
                           WHEN p.status='WAITING' OR (p.status='INVITED' AND i.id IS NOT NULL) THEN 'waiting'
                           ELSE 'history'
                       END AS my_view,
                       CASE WHEN p.status='WAITING' AND a.status='OPEN' AND a.deadline > ? THEN
                           (SELECT COUNT(*) + 1 FROM participation w
                            WHERE w.activity_id=p.activity_id AND w.status='WAITING'
                              AND (w.queued_at < p.queued_at OR (w.queued_at=p.queued_at AND w.id < p.id)))
                       END AS my_rank
                FROM participation p
                JOIN activity a ON a.id=p.activity_id
                LEFT JOIN invitation i ON i.id=(
                    SELECT j.id FROM invitation j WHERE j.participation_id=p.id
                      AND j.status='PENDING' AND j.expires_at > ? AND a.deadline > ? AND a.status='OPEN'
                    ORDER BY j.id DESC LIMIT 1
                )
                WHERE p.user_id=?
                ORDER BY p.updated_at DESC, p.id DESC
                """, now, now, now, now, now, me);
    }

    @GetMapping("/my/participations/{id}/history")
    public List<Map<String, Object>> participationHistory(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        int owned = jdbc.queryForObject("SELECT COUNT(*) FROM participation WHERE id=? AND user_id=?",
                Integer.class, id, me);
        if (owned == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "参与记录不存在");
        return jdbc.queryForList("SELECT id, from_status, to_status, change_kind, occurred_at FROM participation_event " +
                "WHERE participation_id=? ORDER BY occurred_at DESC, id DESC LIMIT 100", id);
    }

    // 任务 2：我组织的活动（带正式/候补/预留人数）。新接口，不改 /api/my 的返回形状
    // （测试与前端都按列表使用它），苏晴在前端另调此接口渲染「我组织的」一栏。
    @GetMapping("/my/organized")
    public Object myOrganized(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        return activityQueries.organized(require(actor), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        long me = require(actor);
        if (page < 1 || size < 1 || size > 50) throw new IllegalStateException("页码从 1 开始，每页 1–50 条");
        long offset = ((long) page - 1) * size;
        return Map.of("list", jdbc.queryForList(
                        "SELECT n.*, i.expires_at AS expires_at, i.status AS invitation_status FROM notification n " +
                        "LEFT JOIN invitation i ON i.id=n.invitation_id " +
                        "WHERE n.user_id=? ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?", me, size, offset),
                "unread", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL", Integer.class, me),
                "total", jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=?", Long.class, me),
                "page", page, "size", size);
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> readNotification(@PathVariable long id,
            @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        int found = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE id=? AND user_id=?",
                Integer.class, id, me);
        if (found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        jdbc.update("UPDATE notification SET read_at=? WHERE id=? AND user_id=? AND read_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), id, me);
        return Map.of("ok", true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAll(@RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        long me = require(actor);
        jdbc.update("UPDATE notification SET read_at=? WHERE user_id=? AND read_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), me);
        return Map.of("ok", true);
    }

    // 只在显式开启的演示环境提供数据重置。
    @Transactional
    @PostMapping("/demo/reset")
    public ResponseEntity<Map<String, Object>> reset(HttpServletRequest request, @RequestAttribute(value = SessionFilter.ACTOR, required = false) Long actor) {
        if (!demoEnabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "ok", false, "error", "演示重置未启用"));
        require(actor);
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM operation_receipt");
        jdbc.update("DELETE FROM feedback");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM participation_event");
        jdbc.update("DELETE FROM participation");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM app_user");
        new com.campusbuddy.service.SeedRunner(jdbc).run();
        return withSessionCookies(request, "", "", Duration.ZERO).body(Map.of("ok", true));
    }
}
