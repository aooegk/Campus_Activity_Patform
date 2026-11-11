package com.campusbuddy.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

public record ActivityFields(String title, String category, String description, String conditions,
                             String location, int capacity, LocalDateTime deadline,
                             LocalDateTime start, LocalDateTime end) {
    public static ActivityFields from(Map<String, Object> body) {
        String title = text(body, "title", "").strip();
        String location = text(body, "location", "").strip();
        String category = text(body, "category", "其他");
        String description = text(body, "description", "");
        String conditions = text(body, "conditions", "");
        if (title.isEmpty() || length(title) > 50) throw new IllegalStateException("标题需为 1-50 字");
        if (location.isEmpty() || length(location) > 100) throw new IllegalStateException("地点需为 1-100 字");
        if (!Set.of("羽毛球", "桌游", "学习组", "其他").contains(category))
            throw new IllegalStateException("类别不合法");
        if (length(description) > 2000) throw new IllegalStateException("介绍不能超过 2000 字");
        if (length(conditions) > 500) throw new IllegalStateException("参与条件不能超过 500 字");
        int capacity;
        try {
            capacity = Integer.parseInt(String.valueOf(body.get("capacity")).strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("人数上限需为 1-100 的整数");
        }
        if (capacity < 1 || capacity > 100) throw new IllegalStateException("人数上限需为 1-100 的整数");
        return new ActivityFields(title, category, description, conditions, location, capacity,
                time(body, "deadline"), time(body, "start_time"), time(body, "end_time"));
    }

    public static LocalDateTime time(Map<String, Object> body, String key) {
        try {
            return LocalDateTime.parse(text(body, key, "")).withNano(0);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("时间格式不正确");
        }
    }

    public static String reason(Map<String, Object> body) {
        String reason = text(body, "reason", "").strip();
        if (length(reason) < 10 || length(reason) > 500)
            throw new IllegalStateException("修改原因需为 10-500 字");
        return reason;
    }

    public void validateTime(LocalDateTime now) {
        if (!(now.isBefore(deadline) && !deadline.isAfter(start) && start.isBefore(end)))
            throw new IllegalStateException("时间需满足：现在 < 报名截止 ≤ 开始 < 结束");
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String text(Map<String, Object> body, String field, String defaultValue) {
        Object value = body.get(field);
        if (value == null) return defaultValue;
        if (value instanceof String text) return text;
        throw new IllegalStateException("字段 " + field + " 必须是文本");
    }
}
