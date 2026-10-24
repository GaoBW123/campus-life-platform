package com.campus.life.diagnostics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 本地实验故障参数；默认都是 0，不影响正常业务。 */
@Component
public class SeckillFaultSettings {
    private final AtomicLong processingDelayMs = new AtomicLong();
    private final AtomicLong databaseDelayMs = new AtomicLong();
    private final AtomicReference<String> dependencyFault = new AtomicReference<>("NONE");
    private final AtomicReference<String> scenarioFault = new AtomicReference<>("NONE");

    public void setDelays(long processingMs, long databaseMs) {
        processingDelayMs.set(validDelay(processingMs));
        databaseDelayMs.set(validDelay(databaseMs));
    }
    public long getProcessingDelayMs() { return processingDelayMs.get(); }
    public long getDatabaseDelayMs() { return databaseDelayMs.get(); }
    public String getDependencyFault() { return dependencyFault.get(); }
    public String getScenarioFault() { return scenarioFault.get(); }
    public boolean isDependencyFault(String target) {
        return target != null && target.equalsIgnoreCase(dependencyFault.get());
    }
    public void setDependencyFault(String target, boolean enabled) {
        if (!enabled) {
            dependencyFault.set("NONE");
            return;
        }
        String normalized = target == null ? "" : target.trim().toUpperCase();
        if (!"MYSQL".equals(normalized) && !"REDIS".equals(normalized)
                && !"HTTP".equals(normalized)) {
            throw new IllegalArgumentException("依赖目标只能是 mysql、redis 或 http");
        }
        dependencyFault.set(normalized);
    }
    public void setScenarioFault(String target, boolean enabled) {
        if (!enabled) {
            scenarioFault.set("NONE");
            return;
        }
        String normalized = target == null ? "" : target.trim().toUpperCase();
        if (!"POOL".equals(normalized) && !"LOCK".equals(normalized)
                && !"REDELIVERY".equals(normalized)) {
            throw new IllegalArgumentException("场景目标只能是 pool、lock 或 redelivery");
        }
        scenarioFault.set(normalized);
    }
    public boolean isScenarioFault(String target) {
        return target != null && target.equalsIgnoreCase(scenarioFault.get());
    }
    public void reset() {
        setDelays(0, 0);
        dependencyFault.set("NONE");
        scenarioFault.set("NONE");
    }
    public void delayProcessingIfConfigured() { sleep(processingDelayMs.get()); }
    public void delayDatabaseIfConfigured() { sleep(databaseDelayMs.get()); }

    private long validDelay(long value) {
        if (value < 0 || value > 30_000) throw new IllegalArgumentException("延迟范围必须是 0 到 30000ms");
        return value;
    }
    private void sleep(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("故障注入延迟被中断", e);
        }
    }
}
