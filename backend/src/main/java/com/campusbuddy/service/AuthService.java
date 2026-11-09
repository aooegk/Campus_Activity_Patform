package com.campusbuddy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 密码散列（PBKDF2，禁止明文）+ 内存会话令牌。演示用，不替代生产会话方案。 */
@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public AuthService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
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

    public String login(String account, String password) {
        var rows = jdbc.queryForList(
                "SELECT id, password_hash, status FROM app_user WHERE account = ?", account);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        if (!"ACTIVE".equals(row.get("status"))) return null;
        if (!verify(password, (String) row.get("password_hash"))) return null;
        String token = UUID.randomUUID().toString();
        sessions.put(token, ((Number) row.get("id")).longValue());
        return token;
    }

    public Long userId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return sessions.get(authHeader.substring(7));
    }

    public Map<String, Object> me(Long userId) {
        return jdbc.queryForMap(
                "SELECT id, account, nickname, role, status FROM app_user WHERE id = ?", userId);
    }

    /** 任务 10（2026-11-09）：改密码。校验原密码、新密码符合 SDD 规则
     *  （8–64 位、含字母与数字）后更新散列，并撤销该用户全部旧会话 token
     *  （含当前 token，改完须重新登录；参考 SDD“凭据更新、旧会话撤销在同一事务提交”）。 */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || newPassword == null)
            throw new IllegalStateException("请填写原密码与新密码");
        var rows = jdbc.queryForList("SELECT password_hash FROM app_user WHERE id=?", userId);
        if (rows.isEmpty()) throw new IllegalStateException("账号不存在");
        if (!verify(oldPassword, (String) rows.get(0).get("password_hash")))
            throw new IllegalStateException("原密码不正确");
        if (newPassword.length() < 8 || newPassword.length() > 64
                || newPassword.chars().noneMatch(Character::isLetter)
                || newPassword.chars().noneMatch(Character::isDigit))
            throw new IllegalStateException("新密码须为 8–64 位，且含字母与数字");
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", hash(newPassword), userId);
        revokeSessionsForUser(userId);
    }

    /** 撤销某用户全部内存会话 token。 */
    public void revokeSessionsForUser(long userId) {
        sessions.entrySet().removeIf(e -> e.getValue().equals(userId));
    }

    /** 演示重置后必须清空，否则序列重启会让旧 token 指向新注册的同 id 用户。 */
    public void clearSessions() {
        sessions.clear();
    }
}
