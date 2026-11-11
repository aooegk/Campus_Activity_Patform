package com.campusbuddy.controller;

import com.campusbuddy.model.ActivityFields;
import com.campusbuddy.service.AuthService;
import com.campusbuddy.service.BuddyService;
import com.campusbuddy.service.CommandReceipts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final BuddyService buddy;
    private final CommandReceipts receipts;
    private final boolean demoEnabled;

    public ApiController(JdbcTemplate jdbc, AuthService auth, BuddyService buddy, CommandReceipts receipts,
                         @Value("${app.demo-enabled:false}") boolean demoEnabled) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.buddy = buddy;
        this.receipts = receipts;
        this.demoEnabled = demoEnabled;
    }

    private Long require(String authHeader) {
        Long id = auth.userId(authHeader);
        if (id == null) throw new IllegalStateException("未登录");
        // 停用账号的旧 token 不能再操作：登录时拦停用只是第一道关，
        // token 发出后被停用的账号必须在每次鉴权时再查一次状态。
        var user = auth.me(id);
        if (!"ACTIVE".equals(user.get("status"))) throw new IllegalStateException("账号已停用");
        return id;
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
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String token = auth.login(body.get("account"), body.get("password"));
        if (token == null) throw new IllegalStateException("账号或密码错误");
        Long id = auth.userId("Bearer " + token);
        return Map.of("ok", true, "token", token, "user", auth.me(id));
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String account = body.get("account"), password = body.get("password");
        if (account == null || account.length() < 4 || account.length() > 32)
            throw new IllegalStateException("账号需为 4-32 字符");
        if (password == null || password.length() < 6)
            throw new IllegalStateException("密码至少 6 位");
        // 旧代码不校验昵称长度，超长时数据库 CHECK 报错被 catch 全吞，
        // 统一误报为“账号已存在”。先校验，再按异常原因区分报错。
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isBlank()) nickname = account;
        if (nickname.codePointCount(0, nickname.length()) < 1 || nickname.codePointCount(0, nickname.length()) > 30)
            throw new IllegalStateException("昵称需为 1-30 字");
        try {
            jdbc.update("INSERT INTO app_user(account, password_hash, nickname) VALUES (?,?,?)",
                    account, AuthService.hash(password), nickname);
        } catch (Exception e) {
            // 只有唯一约束冲突才是“账号已存在”，其他数据库错误不再冒充它。
            Throwable t = e;
            while (t != null) {
                String m = t.getMessage();
                // PostgreSQL 唯一冲突文案是 "duplicate key"，MySQL 是 "Duplicate entry ..."，
                // 统一按小写匹配，两库重复注册都报“账号已存在”。
                if (m != null) {
                    String lower = m.toLowerCase();
                    if (lower.contains("duplicate key") || lower.contains("duplicate entry"))
                        throw new IllegalStateException("账号已存在");
                }
                t = t.getCause();
            }
            throw new IllegalStateException("注册失败，请重试");
        }
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String h) {
        return Map.of("ok", true, "user", auth.me(require(h)));
    }

    // 任务 10（2026-11-09）：改密码。登录态，传原密码 + 新密码；校验原密码、
    // 新密码符合 SDD 规则（8–64 位、含字母与数字）后更新散列并撤销全部旧会话
    // token（含当前 token，改完须重新登录）。注册接口不动。
    @PostMapping("/me/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        auth.changePassword(me, body.get("old_password"), body.get("new_password"));
        return Map.of("ok", true);
    }

    // ---- 活动大厅 ----
    @GetMapping("/activities")
    public List<Map<String, Object>> activities(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestHeader(value = "Authorization", required = false) String h) {
        Long me = auth.userId(h);
        // 停用账号只读大厅时也不再带上本人状态，避免旧 token 在只读路径上仍被当作有效身份。
        if (me != null) {
            try {
                if (!"ACTIVE".equals(auth.me(me).get("status"))) me = null;
            } catch (Exception e) { me = null; }
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String sql = "SELECT a.*, u.nickname AS organizer_name, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='FORMAL') AS formal_count, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='WAITING') AS waiting_count, " +
                "(SELECT COUNT(*) FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                "WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > ?) AS reserved_count";
        List<Object> args = new ArrayList<>();
        args.add(now);
        if (me != null) sql += ", mine.status AS my_status";
        sql += " FROM activity a JOIN app_user u ON u.id=a.organizer_id ";
        if (me != null) {
            sql += "LEFT JOIN participation mine ON mine.activity_id=a.id AND mine.user_id=? ";
            args.add(me);
        }
        sql += "WHERE a.status NOT IN ('CANCELLED','ENDED') AND a.end_time > ? ";
        args.add(now);
        if (q != null && !q.isBlank()) {
            sql += "AND a.title LIKE ? ";
            args.add("%" + q.trim() + "%");
        }
        if (category != null && !category.isBlank() && !"全部".equals(category)) {
            sql += "AND a.category = ? ";
            args.add(category);
        }
        sql += "ORDER BY a.created_at DESC, a.id DESC";
        return jdbc.queryForList(sql, args.toArray());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/activities/{id}")
    public Map<String, Object> activityDetail(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        Long me = auth.userId(h);
        if (me != null && !"ACTIVE".equals(auth.me(me).get("status"))) me = null;
        var act = jdbc.queryForMap("SELECT a.*, u.nickname AS organizer_name FROM activity a " +
                "JOIN app_user u ON u.id=a.organizer_id WHERE a.id=?", id);
        Map<String, Object> out = new LinkedHashMap<>(act);
        // 任务 3（2026-11-10）：未登录也返回人数（公开计数）；登录照旧返回完整详情。
        if (me != null) out.putAll(buddy.detail(id, me));
        else out.putAll(buddy.publicCounts(id));
        // 组织者可见名单与候补队列；其他人只能看到人数
        if (me != null && Objects.equals(((Number) act.get("organizer_id")).longValue(), me)) {
            out.put("roster", jdbc.queryForList(
                    "SELECT p.*, u.nickname FROM participation p JOIN app_user u ON u.id=p.user_id " +
                    "WHERE p.activity_id=? AND p.status IN ('FORMAL','WAITING','INVITED') " +
                    "ORDER BY CASE p.status WHEN 'FORMAL' THEN 0 WHEN 'INVITED' THEN 1 ELSE 2 END, p.queued_at, p.id", id));
            out.put("pending_invitations", jdbc.queryForList(
                    "SELECT i.*, u.nickname FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                    "JOIN app_user u ON u.id=p.user_id WHERE p.activity_id=? AND i.status='PENDING' AND i.expires_at > ? ORDER BY i.expires_at",
                    id, Timestamp.valueOf(LocalDateTime.now())));
            // 任务 9（2026-11-09）：站内留言只在组织者视角返回，不上公开详情页。
            out.put("messages", buddy.listMessages(id));
        }
        return out;
    }

    @PostMapping("/activities")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
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
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "POST /activities/" + id + "/cancel", null,
                () -> ResponseEntity.ok(buddy.cancelActivity(id, me)));
    }

    @PutMapping("/activities/{id}")
    public ResponseEntity<Map<String, Object>> updateActivity(@PathVariable long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "PUT /activities/" + id, body, () -> {
            long version;
            try {
                version = Long.parseLong(String.valueOf(body.get("version")));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("缺少有效活动版本，请刷新详情");
            }
            var result = buddy.updateActivity(id, me, version, ActivityFields.from(body), ActivityFields.reason(body));
            return Boolean.FALSE.equals(result.get("ok"))
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(result)
                    : ResponseEntity.ok(result);
        });
    }

    @PostMapping("/activities/{id}/reschedule")
    public ResponseEntity<Map<String, Object>> reschedule(@PathVariable long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "POST /activities/" + id + "/reschedule", body,
                () -> ResponseEntity.ok(buddy.reschedule(id, me, ActivityFields.time(body, "deadline"),
                        ActivityFields.time(body, "start_time"), ActivityFields.time(body, "end_time"))));
    }

    // 任务 9（2026-11-09）：站内留言——详情页「给组织者留言」，登录用户报名前后均可。
    @PostMapping("/activities/{id}/messages")
    public Map<String, Object> postMessage(@PathVariable long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.postMessage(id, require(h), body.get("content"));
    }

    // 任务 17（2026-11-10）：留言查询接口——组织者看全部留言，本人只看自己发过的
    // （各附组织者回复）；他人留言不可见。详情页「我发过的留言」区用此接口。
    @GetMapping("/activities/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
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
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.replyMessage(id, require(h), mid, body.get("content"));
    }

    // ---- 报名 / 退出 / 递补 ----
    @PostMapping("/activities/{id}/signup")
    public ResponseEntity<Map<String, Object>> signup(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "POST /activities/" + id + "/signup", null,
                () -> ResponseEntity.ok(buddy.signup(id, me)));
    }

    @PostMapping("/participations/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "POST /participations/" + id + "/withdraw", null,
                () -> ResponseEntity.ok(buddy.withdraw(id, me)));
    }

    @PostMapping("/invitations/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
        return receipts.execute(me, key, "POST /invitations/" + id + "/accept", null,
                () -> invitationResponse(buddy.respondInvitation(id, me, true), "ACCEPTED"));
    }

    @PostMapping("/invitations/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long me = require(h);
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
    public List<Map<String, Object>> my(@RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return jdbc.queryForList(
                "SELECT p.*, a.title, a.location, a.capacity, a.start_time, a.deadline, " +
                "(SELECT i.id FROM invitation i WHERE i.participation_id=p.id AND i.status='PENDING' AND i.expires_at > ? ORDER BY i.id DESC LIMIT 1) AS invitation_id, " +
                "(SELECT i.expires_at FROM invitation i WHERE i.participation_id=p.id AND i.status='PENDING' AND i.expires_at > ? ORDER BY i.id DESC LIMIT 1) AS invitation_expires_at " +
                "FROM participation p JOIN activity a ON a.id=p.activity_id WHERE p.user_id=? " +
                "ORDER BY p.updated_at DESC", now, now, me);
    }

    // 任务 2：我组织的活动（带正式/候补/预留人数）。新接口，不改 /api/my 的返回形状
    // （测试与前端都按列表使用它），苏晴在前端另调此接口渲染「我组织的」一栏。
    @GetMapping("/my/organized")
    public List<Map<String, Object>> myOrganized(@RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        return jdbc.queryForList(
                "SELECT a.*, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='FORMAL') AS formal_count, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='WAITING') AS waiting_count, " +
                "(SELECT COUNT(*) FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                " WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > ?) AS reserved_count " +
                "FROM activity a WHERE a.organizer_id=? ORDER BY a.start_time, a.id",
                Timestamp.valueOf(LocalDateTime.now()), me);
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        // 任务 4 配合（2026-11-10）：邀请通知附加 expires_at（LEFT JOIN invitation），
        // 前端倒计时优先用该字段，解析通知文案为兜底；非邀请类通知该字段为 null。
        // invitation 表无 created_at 列，ORDER BY created_at 无歧义。
        return Map.of("list", jdbc.queryForList(
                        "SELECT n.*, i.expires_at AS expires_at FROM notification n " +
                        "LEFT JOIN invitation i ON i.id=n.invitation_id " +
                        "WHERE n.user_id=? ORDER BY n.created_at DESC LIMIT 50", me),
                "unread", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NULL", Integer.class, me));
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAll(@RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        jdbc.update("UPDATE notification SET read_at=? WHERE user_id=? AND read_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), me);
        return Map.of("ok", true);
    }

    // 只在显式开启的演示环境提供数据重置。
    @Transactional
    @PostMapping("/demo/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestHeader(value = "Authorization", required = false) String h) {
        if (!demoEnabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "ok", false, "error", "演示重置未启用"));
        require(h);
        jdbc.update("DELETE FROM operation_receipt");
        jdbc.update("DELETE FROM feedback");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM participation");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM app_user");
        new com.campusbuddy.service.SeedRunner(jdbc).run();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                auth.clearSessions();
            }
        });
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
