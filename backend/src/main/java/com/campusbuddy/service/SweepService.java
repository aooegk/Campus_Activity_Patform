package com.campusbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** 定时任务：活动生命周期 + 候补超时扫描。重复执行幂等，重启后补扫。 */
@Service
public class SweepService {
    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    private final JdbcTemplate jdbc;
    private final BuddyService buddy;

    public SweepService(JdbcTemplate jdbc, BuddyService buddy) {
        this.jdbc = jdbc;
        this.buddy = buddy;
    }

    @Scheduled(fixedDelayString = "${app.sweep-seconds:10}000")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // 任务 1（2026-11-08 P0）：READY→OPEN 回流。截止未到且未开始的活动回到报名期。
        // 根因：sweep 只有 OPEN→READY 的单向流转；种子时间平移 / 恢复后截止回到未来，
        // 状态却留在 READY，报名（要求 OPEN）与递补（promote 第一行要求 OPEN）双死。
        // 回流与 OPEN→READY 互斥（截止 </> now），先执行回流再执行正向流转，不会互相打架；
        // READY 且已开始的仍由下面的 RUNNING 流转接走，截止已过的仍留在 READY。
        jdbc.update("UPDATE activity SET status='OPEN', updated_at=? WHERE status='READY' AND deadline > ? AND start_time > ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        // 活动生命周期（不碰已取消）
        jdbc.update("UPDATE activity SET status='ENDED', updated_at=? WHERE status IN ('OPEN','READY','RUNNING') AND end_time <= ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbc.update("UPDATE activity SET status='RUNNING', updated_at=? WHERE status IN ('OPEN','READY') AND start_time <= ? AND end_time > ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbc.update("UPDATE activity SET status='READY', updated_at=? WHERE status='OPEN' AND deadline <= ? AND start_time > ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        Timestamp cutoff = Timestamp.valueOf(now);
        boolean daytime = buddy.isDaytime(now);
        List<Long> activities = jdbc.queryForList(
                "SELECT a.id FROM activity a WHERE EXISTS (" +
                "SELECT 1 FROM invitation i JOIN participation p ON p.id=i.participation_id " +
                "WHERE p.activity_id=a.id AND i.status='PENDING' AND i.expires_at <= ?) " +
                "OR (a.status <> 'CANCELLED' AND (a.deadline <= ? OR (? AND a.status='OPEN')) AND EXISTS (" +
                "SELECT 1 FROM participation p WHERE p.activity_id=a.id AND p.status='WAITING')) ORDER BY a.id",
                Long.class, cutoff, cutoff, daytime);
        for (Long id : activities) {
            try {
                buddy.refreshInvitations(id);
            } catch (Exception e) {
                log.warn("活动 {} 的递补扫描失败，将在下一轮重试", id, e);
            }
        }
    }
}
