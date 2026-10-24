package com.campus.life.controller;

import com.campus.life.diagnostics.SeckillDiagnosticEventBuffer;
import com.campus.life.diagnostics.SeckillFaultSettings;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** 本地实验用只读接口，仅返回固定容量的结构化消费事件。 */
@RestController
@RequestMapping("/internal/diagnostics/seckill")
public class SeckillDiagnosticsController {
    private final SeckillDiagnosticEventBuffer buffer;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SeckillFaultSettings faultSettings;
    private final DataSource dataSource;

    public SeckillDiagnosticsController(SeckillDiagnosticEventBuffer buffer,
                                        JdbcTemplate jdbcTemplate,
                                        StringRedisTemplate redisTemplate,
                                        SeckillFaultSettings faultSettings,
                                        DataSource dataSource) {
        this.buffer = buffer;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.faultSettings = faultSettings;
        this.dataSource = dataSource;
    }

    /**
     * V2 诊断入口：只做极轻量的依赖探活，不读取业务数据，也不修改 Redis/MySQL。
     * Agent 将该接口作为 MySQL、Redis 和 HTTP 三类证据的统一来源。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("checkedAt", System.currentTimeMillis());
        result.put("service", "campus-life-platform");
        Map<String, Object> http = new HashMap<>();
        if (faultSettings.isDependencyFault("HTTP")) {
            http.put("status", "DOWN");
            http.put("error", "SimulatedDependencyFailure");
            http.put("simulated", true);
        } else {
            http.put("status", "UP");
        }
        result.put("http", http);
        result.put("mysql", checkMysql());
        result.put("redis", checkRedis());
        result.put("connectionPool", checkConnectionPool());
        result.put("lockWaits", checkLockWaits());
        return result;
    }

    private Map<String, Object> checkConnectionPool() {
        Map<String, Object> result = new HashMap<>();
        if (faultSettings.isScenarioFault("POOL")) {
            result.put("status", "SATURATED");
            result.put("active", 10);
            result.put("idle", 0);
            result.put("max", 10);
            result.put("pending", 6);
            result.put("simulated", true);
            return result;
        }
        if (!(dataSource instanceof HikariDataSource)) {
            result.put("status", "UNAVAILABLE");
            result.put("limitation", "当前 DataSource 不是 HikariDataSource");
            return result;
        }
        HikariDataSource hikari = (HikariDataSource) dataSource;
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            result.put("status", "UNAVAILABLE");
            result.put("limitation", "Hikari 连接池尚未初始化");
            return result;
        }
        int active = pool.getActiveConnections();
        int max = hikari.getMaximumPoolSize();
        int pending = pool.getThreadsAwaitingConnection();
        result.put("active", active);
        result.put("idle", pool.getIdleConnections());
        result.put("total", pool.getTotalConnections());
        result.put("max", max);
        result.put("pending", pending);
        result.put("status", pending > 0 && active >= max ? "SATURATED" : "UP");
        return result;
    }

    private Map<String, Object> checkLockWaits() {
        Map<String, Object> result = new HashMap<>();
        if (faultSettings.isScenarioFault("LOCK")) {
            result.put("status", "WAITING");
            result.put("waitingCount", 1);
            result.put("simulated", true);
            List<Map<String, Object>> waits = new ArrayList<>();
            Map<String, Object> wait = new HashMap<>();
            wait.put("requestingTransaction", "diagnosis-requesting-trx");
            wait.put("blockingTransaction", "diagnosis-blocking-trx");
            wait.put("lockedObject", "tb_seckill_voucher");
            waits.add(wait);
            result.put("waits", waits);
            return result;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class);
            result.put("waitingCount", count == null ? 0 : count);
            result.put("status", count != null && count > 0 ? "WAITING" : "UP");
            if (count != null && count > 0) {
                List<Map<String, Object>> waits = jdbcTemplate.query(
                        "SELECT REQUESTING_ENGINE_TRANSACTION_ID, BLOCKING_ENGINE_TRANSACTION_ID, "
                                + "REQUESTING_ENGINE_LOCK_ID, BLOCKING_ENGINE_LOCK_ID "
                                + "FROM performance_schema.data_lock_waits LIMIT 20",
                        (rs, rowNum) -> {
                            Map<String, Object> wait = new HashMap<>();
                            wait.put("requestingTransaction", rs.getString(1));
                            wait.put("blockingTransaction", rs.getString(2));
                            wait.put("requestingLock", rs.getString(3));
                            wait.put("blockingLock", rs.getString(4));
                            return wait;
                        });
                result.put("waits", waits);
            }
        } catch (RuntimeException ex) {
            result.put("status", "UNAVAILABLE");
            result.put("waitingCount", 0);
            result.put("limitation", ex.getClass().getSimpleName());
        }
        return result;
    }

    private Map<String, Object> checkMysql() {
        long started = System.nanoTime();
        Map<String, Object> result = new HashMap<>();
        if (faultSettings.isDependencyFault("MYSQL")) {
            result.put("status", "DOWN");
            result.put("error", "SimulatedDependencyFailure");
            result.put("simulated", true);
            result.put("latencyMs", (System.nanoTime() - started) / 1_000_000.0);
            return result;
        }
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("status", value != null && value == 1 ? "UP" : "DEGRADED");
        } catch (RuntimeException ex) {
            result.put("status", "DOWN");
            result.put("error", ex.getClass().getSimpleName());
        }
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000.0);
        return result;
    }

    private Map<String, Object> checkRedis() {
        long started = System.nanoTime();
        Map<String, Object> result = new HashMap<>();
        if (faultSettings.isDependencyFault("REDIS")) {
            result.put("status", "DOWN");
            result.put("error", "SimulatedDependencyFailure");
            result.put("simulated", true);
            result.put("latencyMs", (System.nanoTime() - started) / 1_000_000.0);
            return result;
        }
        RedisConnection connection = null;
        try {
            connection = redisTemplate.getConnectionFactory().getConnection();
            String pong = connection.ping();
            result.put("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DEGRADED");
        } catch (RuntimeException ex) {
            result.put("status", "DOWN");
            result.put("error", ex.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.close();
        }
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000.0);
        return result;
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> result = new HashMap<>();
        List<SeckillDiagnosticEventBuffer.Event> events = buffer.recent(limit);
        result.put("events", events);
        result.put("limit", Math.max(1, Math.min(limit, 50)));
        Map<Long, Integer> deliveries = new HashMap<>();
        int redeliveredEvents = 0;
        for (SeckillDiagnosticEventBuffer.Event event : events) {
            deliveries.put(event.getOrderId(), deliveries.getOrDefault(event.getOrderId(), 0) + 1);
            if ("REDELIVERED".equals(event.getResult())) redeliveredEvents++;
        }
        int repeatedMessages = 0;
        for (Integer count : deliveries.values()) if (count >= 3) repeatedMessages++;
        Map<String, Object> summary = new HashMap<>();
        summary.put("redeliveredEvents", redeliveredEvents);
        summary.put("repeatedMessages", repeatedMessages);
        summary.put("redeliveryLoopSuspected", redeliveredEvents >= 3 && repeatedMessages > 0);
        result.put("summary", summary);
        return result;
    }
}
