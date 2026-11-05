package com.campusbuddy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 定时任务：活动生命周期 + 候补超时扫描。重复执行幂等，重启后补扫。 */
@Service
public class SweepService {
    private final JdbcTemplate jdbc;
    private final BuddyService buddy;

    public SweepService(JdbcTemplate jdbc, BuddyService buddy) {
        this.jdbc = jdbc;
        this.buddy = buddy;
    }

    @Scheduled(fixedDelayString = "${app.sweep-seconds:10}000")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // 活动生命周期（不碰已取消）
        jdbc.update("UPDATE activity SET status='ENDED', updated_at=? WHERE status IN ('OPEN','READY','RUNNING') AND end_time <= ?",
                java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now));
        jdbc.update("UPDATE activity SET status='RUNNING', updated_at=? WHERE status IN ('OPEN','READY') AND start_time <= ? AND end_time > ?",
                java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now));
        jdbc.update("UPDATE activity SET status='READY', updated_at=? WHERE status='OPEN' AND deadline <= ? AND start_time > ?",
                java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now));
        // 超时邀请：逐条在事务内抢占处理，失败可重试
        List<Long> expired = jdbc.queryForList(
                "SELECT id FROM invitation WHERE status='PENDING' AND expires_at <= ? ORDER BY expires_at, id",
                Long.class, java.sql.Timestamp.valueOf(now));
        for (Long id : expired) {
            try { buddy.expireOne(id); } catch (Exception e) { /* 下一轮重试 */ }
        }
    }
}
