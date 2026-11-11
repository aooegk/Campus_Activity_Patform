package com.campusbuddy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Supplier;

@Service
public class CommandReceipts {
    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectWriter canonicalJson;
    private final String reserveReceipt;

    public CommandReceipts(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalJson = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        boolean mysql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
        String insert = "INSERT INTO operation_receipt(user_id, key_hash, request_hash) VALUES (?,?,?) ";
        this.reserveReceipt = insert + (mysql
                ? "ON DUPLICATE KEY UPDATE key_hash=key_hash"
                : "ON CONFLICT (user_id, key_hash) DO NOTHING");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResponseEntity<Map<String, Object>> execute(long userId, String key, String command, Object body,
                                                        Supplier<ResponseEntity<Map<String, Object>>> action) {
        if (key == null) return action.get();
        if (!key.matches("[!-~]{1,128}"))
            throw new IllegalStateException("Idempotency-Key 需为 1-128 个可见 ASCII 字符");

        try {
            String keyHash = AuthService.digest(key);
            String requestHash = AuthService.digest(command + "\n" + canonicalJson.writeValueAsString(body));
            jdbc.update(reserveReceipt, userId, keyHash, requestHash);
            var receipt = jdbc.queryForMap(
                    "SELECT request_hash, response_status, response_body FROM operation_receipt " +
                    "WHERE user_id=? AND key_hash=? FOR UPDATE", userId, keyHash);
            if (!requestHash.equals(receipt.get("request_hash"))) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "ok", false, "code", "IDEMPOTENCY_KEY_CONFLICT",
                        "error", "同一个请求键已用于其他操作或不同内容"));
            }
            if (receipt.get("response_status") instanceof Number status) {
                return ResponseEntity.status(status.intValue())
                        .body(mapper.readValue((String) receipt.get("response_body"), RESPONSE_TYPE));
            }

            var result = action.get();
            jdbc.update("UPDATE operation_receipt SET response_status=?, response_body=? WHERE user_id=? AND key_hash=?",
                    result.getStatusCode().value(), mapper.writeValueAsString(result.getBody()), userId, keyHash);
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法处理请求回执", e);
        }
    }

}
