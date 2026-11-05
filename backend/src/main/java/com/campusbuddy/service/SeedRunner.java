package com.campusbuddy.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 空库时写入演示数据；已有数据时不动。演示账号密码均为 123456。 */
@Component
public class SeedRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public SeedRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private long createActivity(long org, String title, String cat, String loc, int cap, LocalDateTime now) {
        // 旧代码用 SELECT MAX(id) 取刚插入的活动，与 ApiController.create 是同一个并发错位问题；
        // 启动时虽单线程，仍改用可移植的列名数组取生成键，避免任何并发插入时串位。
        // 必须用 new String[]{"id"} 而不是 Statement.RETURN_GENERATED_KEYS：
        // PostgreSQL 驱动后者会把整行都当作生成键返回，getKey() 会因多键报错。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO activity(organizer_id, title, category, description, conditions, location, capacity, deadline, start_time, end_time) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, org);
            ps.setString(2, title);
            ps.setString(3, cat);
            ps.setString(4, "演示活动");
            ps.setString(5, "请准时参加");
            ps.setString(6, loc);
            ps.setInt(7, cap);
            ps.setTimestamp(8, Timestamp.valueOf(now.plusDays(2)));
            ps.setTimestamp(9, Timestamp.valueOf(now.plusDays(3)));
            ps.setTimestamp(10, Timestamp.valueOf(now.plusDays(3).plusHours(2)));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按账号查 id，不再假设 demo01=2、demo02=3……。
     * 旧代码按 demo 序号推算 id（demo01=2、demo02=3……），只有序列从 1 开始时才成立；一旦序列被推进过
     * （例如表被清空但序列未重启），种子参与记录就会指向不存在或错误的用户，
     * 轻则外键失败导致启动失败，重则串到别人身上。 */
    private long userId(String account) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE account=?", Long.class, account);
    }

    @Override
    public void run(String... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        if (n != null && n > 0) return;
        String hash = AuthService.hash("123456");
        jdbc.update("INSERT INTO app_user(account, password_hash, nickname) VALUES ('organizer', ?, '组织者小陈')", hash);
        for (int i = 1; i <= 20; i++) {
            jdbc.update("INSERT INTO app_user(account, password_hash, nickname) VALUES (?, ?, ?)",
                    "demo" + String.format("%02d", i), hash, "同学" + i);
        }
        long org = jdbc.queryForObject("SELECT id FROM app_user WHERE account='organizer'", Long.class);
        LocalDateTime now = LocalDateTime.now();
        long a = createActivity(org, "周六羽毛球双打", "羽毛球", "体育馆三楼", 4, now);
        long b = createActivity(org, "桌游之夜", "桌游", "活动中心201", 4, now);
        long c = createActivity(org, "周末学习组", "学习组", "图书馆三楼", 10, now);
        // A: 3 正式；B: 4 正式 + 2 候补；C: 2 正式
        for (int i = 1; i <= 3; i++)
            jdbc.update("INSERT INTO participation(activity_id, user_id, status, joined_at, updated_at) VALUES (?,?, 'FORMAL', ?, ?)",
                    a, userId("demo" + String.format("%02d", i)), Timestamp.valueOf(now), Timestamp.valueOf(now));
        for (int i = 1; i <= 4; i++)
            jdbc.update("INSERT INTO participation(activity_id, user_id, status, joined_at, updated_at) VALUES (?,?, 'FORMAL', ?, ?)",
                    b, userId("demo" + String.format("%02d", i)), Timestamp.valueOf(now), Timestamp.valueOf(now));
        for (int i = 5; i <= 6; i++)
            jdbc.update("INSERT INTO participation(activity_id, user_id, status, queued_at, updated_at) VALUES (?,?, 'WAITING', ?, ?)",
                    b, userId("demo" + String.format("%02d", i)), Timestamp.valueOf(now.plusMinutes(i)), Timestamp.valueOf(now));
        for (int i = 1; i <= 2; i++)
            jdbc.update("INSERT INTO participation(activity_id, user_id, status, joined_at, updated_at) VALUES (?,?, 'FORMAL', ?, ?)",
                    c, userId("demo" + String.format("%02d", i)), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }
}
