package com.campus.life.diagnostics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 保存最近的消费结果，专供本地诊断实验读取。
 * 使用固定容量避免日志事件无限占用内存；生产环境应接入正式日志平台。
 */
@Component
public class SeckillDiagnosticEventBuffer {
    private static final int CAPACITY = 200;
    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();

    public void add(Long orderId, String result, long durationMs, String errorType) {
        add(orderId, result, durationMs, errorType, 1, false);
    }

    public void add(Long orderId, String result, long durationMs, String errorType,
                    int deliveryAttempt, boolean simulated) {
        events.addFirst(new Event(Instant.now().toString(), orderId, result, durationMs,
                errorType, deliveryAttempt, simulated));
        while (events.size() > CAPACITY) events.pollLast();
    }

    /** 安全模拟同一消息的多次失败投递，不让 RabbitMQ 进入无限 requeue 循环。 */
    public void addSimulatedRedeliveries(Long orderId) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            add(orderId, "REDELIVERED", 5, "SyntheticConsumerFailure", attempt, true);
        }
    }

    public List<Event> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (result.size() >= safeLimit) break;
            result.add(event);
        }
        return result;
    }

    /** 清空上一轮本地故障实验的事件，防止评测案例之间串数据。 */
    public void clear() {
        events.clear();
    }

    public static class Event {
        private final String observedAt;
        private final Long orderId;
        private final String result;
        private final long durationMs;
        private final String errorType;
        private final int deliveryAttempt;
        private final boolean simulated;

        Event(String observedAt, Long orderId, String result, long durationMs, String errorType,
              int deliveryAttempt, boolean simulated) {
            this.observedAt = observedAt;
            this.orderId = orderId;
            this.result = result;
            this.durationMs = durationMs;
            this.errorType = errorType;
            this.deliveryAttempt = deliveryAttempt;
            this.simulated = simulated;
        }
        public String getObservedAt() { return observedAt; }
        public Long getOrderId() { return orderId; }
        public String getResult() { return result; }
        public long getDurationMs() { return durationMs; }
        public String getErrorType() { return errorType; }
        public int getDeliveryAttempt() { return deliveryAttempt; }
        public boolean isSimulated() { return simulated; }
    }
}
