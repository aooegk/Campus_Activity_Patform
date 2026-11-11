package com.campusbuddy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/** 账号凭据、登录锁定和持久化会话。 */
@Service
public class AuthService {
    public static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration LOGIN_LOCK = Duration.ofMinutes(15);
    private static final int MAX_FAILED_LOGINS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public record LoginSession(long userId, String token, String csrf) {}
    public record Principal(long userId, String csrfHash) {}
    private record LoginAttempt(int failures, LocalDateTime lockedUntil) {}
    private record StoredSession(long userId, String csrfHash, LocalDateTime expiresAt, LocalDateTime idleUntil) {}

    public AuthService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 100000, 256)).getEncoded();
            return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(dk);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            byte[] salt = HexFormat.of().parseHex(parts[0]);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 100000, 256)).getEncoded();
            // 恒定时间比较，避免用 String.equals 因提前返回泄露前缀信息。
            return java.security.MessageDigest.isEqual(dk, HexFormat.of().parseHex(parts[1]));
        } catch (Exception e) { return false; }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginSession login(String account, String password) {
        if (account == null || password == null || password.length() > 128) return null;
        var rows = jdbc.queryForList(
                "SELECT id, password_hash, status FROM app_user WHERE account=? FOR UPDATE", account);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status"))) return null;
        var row = rows.get(0);
        long userId = ((Number) row.get("id")).longValue();
        LocalDateTime now = LocalDateTime.now();
        var attempts = jdbc.query("SELECT failed_count, locked_until FROM login_attempt WHERE user_id=?",
                (rs, index) -> {
                    Timestamp lockedUntil = rs.getTimestamp("locked_until");
                    return new LoginAttempt(rs.getInt("failed_count"),
                            lockedUntil == null ? null : lockedUntil.toLocalDateTime());
                }, userId);
        LoginAttempt attempt = attempts.isEmpty() ? null : attempts.get(0);
        if (attempt != null && attempt.lockedUntil() != null && now.isBefore(attempt.lockedUntil())) return null;
        if (!verify(password, (String) row.get("password_hash"))) {
            int count = attempt == null || attempt.lockedUntil() != null ? 1 : attempt.failures() + 1;
            Timestamp lockedUntil = count >= MAX_FAILED_LOGINS ? Timestamp.valueOf(now.plus(LOGIN_LOCK)) : null;
            if (attempt == null) {
                jdbc.update("INSERT INTO login_attempt(user_id, failed_count, locked_until) VALUES (?,?,?)",
                        userId, count, lockedUntil);
            } else {
                jdbc.update("UPDATE login_attempt SET failed_count=?, locked_until=? WHERE user_id=?",
                        count, lockedUntil, userId);
            }
            return null;
        }
        jdbc.update("DELETE FROM login_attempt WHERE user_id=?", userId);
        String token = newSecret();
        String csrf = newSecret();
        jdbc.update("INSERT INTO auth_session(token_hash, user_id, csrf_hash, expires_at, idle_until) VALUES (?,?,?,?,?)",
                digest(token), userId, digest(csrf), Timestamp.valueOf(now.plus(SESSION_LIFETIME)),
                Timestamp.valueOf(now.plus(IDLE_TIMEOUT)));
        return new LoginSession(userId, token, csrf);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Principal authenticate(String token) {
        if (token == null || !token.matches("[0-9a-f]{64}")) return null;
        String hash = digest(token);
        var sessions = jdbc.query("SELECT user_id, csrf_hash, expires_at, idle_until FROM auth_session " +
                        "WHERE token_hash=? FOR UPDATE",
                (rs, index) -> new StoredSession(rs.getLong("user_id"), rs.getString("csrf_hash"),
                        rs.getTimestamp("expires_at").toLocalDateTime(), rs.getTimestamp("idle_until").toLocalDateTime()),
                hash);
        if (sessions.isEmpty()) return null;
        var session = sessions.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(session.expiresAt()) || !now.isBefore(session.idleUntil())) {
            jdbc.update("DELETE FROM auth_session WHERE token_hash=?", hash);
            return null;
        }
        long userId = session.userId();
        String status = jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, userId);
        if (!"ACTIVE".equals(status)) {
            jdbc.update("DELETE FROM auth_session WHERE token_hash=?", hash);
            return null;
        }
        jdbc.update("UPDATE auth_session SET idle_until=? WHERE token_hash=?",
                Timestamp.valueOf(now.plus(IDLE_TIMEOUT)), hash);
        return new Principal(userId, session.csrfHash());
    }

    @Scheduled(fixedDelay = 900_000)
    public void removeExpiredSessions() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("DELETE FROM auth_session WHERE expires_at <= ? OR idle_until <= ?", now, now);
    }

    public boolean csrfMatches(Principal principal, String csrf) {
        return csrf != null && csrf.matches("[0-9a-f]{64}") && MessageDigest.isEqual(
                digest(csrf).getBytes(StandardCharsets.US_ASCII), principal.csrfHash().getBytes(StandardCharsets.US_ASCII));
    }

    public void logout(String token) {
        if (token != null && token.matches("[0-9a-f]{64}"))
            jdbc.update("DELETE FROM auth_session WHERE token_hash=?", digest(token));
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法初始化请求摘要", e);
        }
    }

    public Map<String, Object> me(Long userId) {
        return jdbc.queryForMap(
                "SELECT id, account, nickname, role, status FROM app_user WHERE id = ?", userId);
    }

    /** 任务 10（2026-11-09）：改密码。校验原密码、新密码符合 SDD 规则
     *  （8–64 位、含字母与数字）后更新散列，并撤销该用户全部旧会话 token
     *  （含当前 token，改完须重新登录；参考 SDD“凭据更新、旧会话撤销在同一事务提交”）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changePassword(long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || newPassword == null)
            throw new IllegalStateException("请填写原密码与新密码");
        var rows = jdbc.queryForList("SELECT password_hash FROM app_user WHERE id=? FOR UPDATE", userId);
        if (rows.isEmpty()) throw new IllegalStateException("账号不存在");
        if (!verify(oldPassword, (String) rows.get(0).get("password_hash")))
            throw new IllegalStateException("原密码不正确");
        validatePassword(newPassword);
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", hash(newPassword), userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id=?", userId);
        jdbc.update("DELETE FROM login_attempt WHERE user_id=?", userId);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || password.codePoints().noneMatch(Character::isLetter)
                || password.codePoints().noneMatch(Character::isDigit))
            throw new IllegalStateException("密码须为 8–64 位，且含字母与数字");
    }
}
