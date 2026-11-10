package com.campusbuddy.controller;

import com.campusbuddy.service.AuthService;
import com.campusbuddy.service.BuddyService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
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
    /** 启动时从 DataSource 取数据库产品名判断一次并缓存：与 BuddyService 同一做法，
     *  仅用于 /demo/reset 的自增序列重置分支。 */
    private final boolean mysql;

    public ApiController(JdbcTemplate jdbc, AuthService auth, BuddyService buddy) {
        this.jdbc = jdbc; this.auth = auth; this.buddy = buddy;
        this.mysql = detectMysql(jdbc);
    }

    private static boolean detectMysql(JdbcTemplate jdbc) {
        try (var con = jdbc.getDataSource().getConnection()) {
            return con.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        } catch (Exception e) {
            throw new IllegalStateException("无法识别数据库产品", e);
        }
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
                "invite_ttl_seconds", buddy.inviteTtlSeconds(),
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
        // 任务 9（2026-11-10）：大厅默认不列已取消活动；/my 与 /my/organized 不动。
        String sql = "SELECT a.*, u.nickname AS organizer_name, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='FORMAL') AS formal_count, " +
                "(SELECT COUNT(*) FROM participation p WHERE p.activity_id=a.id AND p.status='WAITING') AS waiting_count, " +
                "(SELECT COUNT(*) FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                " WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > now()) AS reserved_count " +
                "FROM activity a JOIN app_user u ON u.id=a.organizer_id WHERE 1=1 AND a.status <> 'CANCELLED' ";
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) { sql += "AND a.title LIKE ? "; args.add("%" + q.trim() + "%"); }
        if (category != null && !category.isBlank() && !"全部".equals(category)) {
            sql += "AND a.category = ? "; args.add(category);
        }
        sql += "ORDER BY a.start_time, a.id";
        var rows = jdbc.queryForList(sql, args.toArray());
        if (me != null) {
            for (var row : rows) {
                var mine = jdbc.queryForList(
                        "SELECT status FROM participation WHERE activity_id=? AND user_id=?",
                        row.get("id"), me);
                row.put("my_status", mine.isEmpty() ? null : mine.get(0).get("status"));
            }
        }
        return rows;
    }

    @GetMapping("/activities/{id}")
    public Map<String, Object> activityDetail(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        Long me = auth.userId(h);
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
                    "JOIN app_user u ON u.id=p.user_id WHERE p.activity_id=? AND i.status='PENDING' ORDER BY i.expires_at", id));
            // 任务 9（2026-11-09）：站内留言只在组织者视角返回，不上公开详情页。
            out.put("messages", buddy.listMessages(id));
        }
        return out;
    }

    @PostMapping("/activities")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        // 旧代码对空标题、缺地点、非法类别、缺容量一律不校验，
        // 走到数据库约束或空指针时直接返回 500；且用 SELECT MAX(id) 取新活动 id，
        // 并发创建时多个请求会拿到同一个（别人的）id。全部在此处校验 + 用
        // 可移植的 RETURN_GENERATED_KEYS 取新活动 id（PostgreSQL 与 MySQL 驱动都支持）。
        String title = body.get("title") instanceof String ? (String) body.get("title") : null;
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 50)
            throw new IllegalStateException("标题需为 1-50 字");
        String location = body.get("location") instanceof String ? (String) body.get("location") : null;
        if (location == null || location.isBlank() || location.codePointCount(0, location.length()) > 100)
            throw new IllegalStateException("地点需为 1-100 字");
        Integer capacity = null;
        Object capRaw = body.get("capacity");
        if (capRaw instanceof Number) capacity = ((Number) capRaw).intValue();
        else if (capRaw instanceof String) {
            try { capacity = Integer.parseInt(((String) capRaw).trim()); } catch (NumberFormatException ignored) { }
        }
        if (capacity == null || capacity < 1 || capacity > 100)
            throw new IllegalStateException("人数上限需为 1-100");
        String category = body.get("category") instanceof String ? (String) body.get("category") : "其他";
        if (!Set.of("羽毛球", "桌游", "学习组", "其他").contains(category))
            throw new IllegalStateException("类别不合法");
        String description = body.get("description") instanceof String ? (String) body.get("description") : "";
        String conditions = body.get("conditions") instanceof String ? (String) body.get("conditions") : "";
        if (description.codePointCount(0, description.length()) > 2000)
            throw new IllegalStateException("介绍不能超过 2000 字");
        if (conditions.codePointCount(0, conditions.length()) > 500)
            throw new IllegalStateException("参与条件不能超过 500 字");
        LocalDateTime deadline, start, end;
        try {
            // 前端 datetime-local 发来东八区墙钟，库里直接存东八区墙钟，
            // 不做任何时区转换（2026-11-06 时区统一改造，旧 shanghaiToUtcWall 已删除）。
            deadline = LocalDateTime.parse((String) body.get("deadline"));
            start = LocalDateTime.parse((String) body.get("start_time"));
            end = LocalDateTime.parse((String) body.get("end_time"));
        } catch (Exception e) {
            throw new IllegalStateException("时间格式不正确");
        }
        if (!(LocalDateTime.now().isBefore(deadline) && !deadline.isAfter(start) && start.isBefore(end)))
            throw new IllegalStateException("时间需满足：现在 < 报名截止 ≤ 开始 < 结束");
        // lambda 内只能捕获 final / effectively-final 变量，先复制一份。
        // 取新活动 id 必须用列名数组 new String[]{"id"} 而不是 Statement.RETURN_GENERATED_KEYS：
        // PostgreSQL 驱动后者会把整行都当作生成键返回，getKey() 会因多键报错。
        final int capFinal = capacity;
        final LocalDateTime deadlineFinal = deadline, startFinal = start, endFinal = end;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO activity(organizer_id, title, category, description, conditions, location, capacity, deadline, start_time, end_time) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, me);
            ps.setString(2, title);
            ps.setString(3, category);
            ps.setString(4, description);
            ps.setString(5, conditions);
            ps.setString(6, location);
            ps.setInt(7, capFinal);
            ps.setTimestamp(8, Timestamp.valueOf(deadlineFinal));
            ps.setTimestamp(9, Timestamp.valueOf(startFinal));
            ps.setTimestamp(10, Timestamp.valueOf(endFinal));
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();
        return Map.of("ok", true, "id", id);
    }

    @Transactional
    @PostMapping("/activities/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        // 任务 1（2026-11-09）：取消逻辑与取消通知都在 BuddyService.cancelActivity
        // 内完成（SDD §6.8：取消通知所有受影响成员）；二次取消幂等，不重发通知。
        return buddy.cancelActivity(id, require(h));
    }

    // 任务 2（2026-11-09）：组织者报名截止前改活动时间（开始 / 结束 / 截止三项）。
    // 只改时间，不扩容量与活动信息；R5 校验（截止前、时间顺序、待确认邀请保护）
    // 与变更通知都在 BuddyService.reschedule 内。前端入口由苏晴在详情页组织者视角加。
    @PostMapping("/activities/{id}/reschedule")
    public Map<String, Object> reschedule(@PathVariable long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        LocalDateTime deadline, start, end;
        try {
            deadline = LocalDateTime.parse((String) body.get("deadline"));
            start = LocalDateTime.parse((String) body.get("start_time"));
            end = LocalDateTime.parse((String) body.get("end_time"));
        } catch (Exception e) {
            throw new IllegalStateException("时间格式不正确");
        }
        return buddy.reschedule(id, me, deadline, start, end);
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
    public Map<String, Object> signup(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.signup(id, require(h));
    }

    @PostMapping("/participations/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.withdraw(id, require(h));
    }

    @PostMapping("/invitations/{id}/accept")
    public Map<String, Object> accept(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.respondInvitation(id, require(h), true);
    }

    @PostMapping("/invitations/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String h) {
        return buddy.respondInvitation(id, require(h), false);
    }

    // ---- 我的活动 / 通知 ----
    @GetMapping("/my")
    public List<Map<String, Object>> my(@RequestHeader(value = "Authorization", required = false) String h) {
        long me = require(h);
        return jdbc.queryForList(
                "SELECT p.*, a.title, a.location, a.capacity, a.start_time, a.deadline, " +
                "(SELECT i.id FROM invitation i WHERE i.participation_id=p.id AND i.status='PENDING' ORDER BY i.id DESC LIMIT 1) AS invitation_id, " +
                "(SELECT i.expires_at FROM invitation i WHERE i.participation_id=p.id AND i.status='PENDING' ORDER BY i.id DESC LIMIT 1) AS invitation_expires_at " +
                "FROM participation p JOIN activity a ON a.id=p.activity_id WHERE p.user_id=? " +
                "ORDER BY p.updated_at DESC", me);
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
                " WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at > now()) AS reserved_count " +
                "FROM activity a WHERE a.organizer_id=? ORDER BY a.start_time, a.id", me);
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

    // ---- 演示重置（仅演示用）：清空业务数据并恢复种子 ----
    // 修复前：无需任何登录即可调用，且不清空内存会话。序列重启后新注册用户
    // 会复用旧用户的 id，旧 token 不经任何校验就能冒用新用户（已复现）。
    // 修复后：必须登录才能重置；种子写入成功后清空全部会话；删除 + 种子在同一事务内。
    @Transactional
    @PostMapping("/demo/reset")
    public Map<String, Object> reset(@RequestHeader(value = "Authorization", required = false) String h) {
        require(h);
        jdbc.update("DELETE FROM feedback");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM participation");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM app_user");
        // 重置自增序列，避免演示时编号无限增长
        // MySQL 没有 SEQUENCE：表已清空，AUTO_INCREMENT = 1 即从 1 开始；
        // PostgreSQL 保持原 ALTER SEQUENCE。
        if (mysql) {
            jdbc.execute("ALTER TABLE app_user AUTO_INCREMENT = 1");
            jdbc.execute("ALTER TABLE activity AUTO_INCREMENT = 1");
            jdbc.execute("ALTER TABLE participation AUTO_INCREMENT = 1");
            jdbc.execute("ALTER TABLE invitation AUTO_INCREMENT = 1");
            jdbc.execute("ALTER TABLE notification AUTO_INCREMENT = 1");
        } else {
            jdbc.execute("ALTER SEQUENCE app_user_id_seq RESTART WITH 1");
            jdbc.execute("ALTER SEQUENCE activity_id_seq RESTART WITH 1");
            jdbc.execute("ALTER SEQUENCE participation_id_seq RESTART WITH 1");
            jdbc.execute("ALTER SEQUENCE invitation_id_seq RESTART WITH 1");
            jdbc.execute("ALTER SEQUENCE notification_id_seq RESTART WITH 1");
        }
        // 种子逻辑与 SeedRunner 相同，这里直接复用其 run()（空库时写入）
        new com.campusbuddy.service.SeedRunner(jdbc).run();
        auth.clearSessions();
        return Map.of("ok", true);
    }
}
