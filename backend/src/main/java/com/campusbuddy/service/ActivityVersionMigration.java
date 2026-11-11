package com.campusbuddy.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;

@Component
@DependsOnDatabaseInitialization
public class ActivityVersionMigration {
    private final JdbcTemplate jdbc;

    public ActivityVersionMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void migrate() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                if (hasVersion(statement)) return null;
                try {
                    statement.executeUpdate("ALTER TABLE activity ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
                } catch (SQLException e) {
                    // MySQL 1060 和 PostgreSQL 42701 表示其他实例已添加同一列。
                    if (e.getErrorCode() != 1060 && !"42701".equals(e.getSQLState())) throw e;
                }
            }
            return null;
        });
    }

    private boolean hasVersion(Statement statement) throws SQLException {
        try (var result = statement.executeQuery("SELECT * FROM activity WHERE 1=0")) {
            var columns = result.getMetaData();
            for (int i = 1; i <= columns.getColumnCount(); i++) {
                if ("version".equalsIgnoreCase(columns.getColumnName(i))) return true;
            }
        }
        return false;
    }
}
