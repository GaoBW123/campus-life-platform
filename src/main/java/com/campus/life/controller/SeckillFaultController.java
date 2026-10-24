package com.campus.life.controller;

import com.campus.life.diagnostics.SeckillFaultSettings;
import com.campus.life.diagnostics.SeckillDiagnosticEventBuffer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.campus.life.entity.VoucherOrder;

/**
 * 仅在 diagnosis.experiment.enabled=true 时创建，用于可重复地制造实验故障。
 * 该接口绝不能在生产环境开启。
 */
@RestController
@RequestMapping("/internal/experiments/seckill")
@ConditionalOnProperty(name = "diagnosis.experiment.enabled", havingValue = "true")
@CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
public class SeckillFaultController {
    private final RabbitListenerEndpointRegistry registry;
    private final SeckillFaultSettings settings;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final SeckillDiagnosticEventBuffer diagnosticEvents;
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1000);

    public SeckillFaultController(RabbitListenerEndpointRegistry registry,
                                  SeckillFaultSettings settings,
                                  RabbitTemplate rabbitTemplate,
                                  MeterRegistry meterRegistry,
                                  SeckillDiagnosticEventBuffer diagnosticEvents) {
        this.registry = registry;
        this.settings = settings;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.diagnosticEvents = diagnosticEvents;
    }

    @GetMapping("/state")
    public Map<String, Object> currentState() { return state(); }

    @PostMapping("/consumer")
    public Map<String, Object> consumer(@RequestParam String action) {
        MessageListenerContainer container = registry.getListenerContainer("seckillOrderListener");
        if (container == null) throw new IllegalStateException("找不到 seckillOrderListener");
        MessageListenerContainer audit = registry.getListenerContainer("orderDeadLetterListener");
        if ("stop".equalsIgnoreCase(action)) {
            // 先停超时审计，避免实验消息在主消费者恢复前被当作入库失败回滚。
            if (audit != null) audit.stop();
            container.stop();
        }
        else if ("start".equalsIgnoreCase(action)) container.start();
        else throw new IllegalArgumentException("action 只能是 start 或 stop");
        Map<String, Object> result = new HashMap<>();
        result.put("running", container.isRunning());
        result.put("auditRunning", audit != null && audit.isRunning());
        return result;
    }

    @PostMapping("/delay")
    public Map<String, Object> delay(@RequestParam(defaultValue = "0") long processingMs,
                                    @RequestParam(defaultValue = "0") long databaseMs) {
        settings.setDelays(processingMs, databaseMs);
        return state();
    }

    /**
     * 仅改变诊断健康接口的实验返回值，不会真的关闭 MySQL、Redis 或业务端口。
     * 这样可以安全、可重复地验证 Agent 对依赖异常的判断。
     */
    @PostMapping("/dependency")
    public Map<String, Object> dependency(@RequestParam String target,
                                          @RequestParam(defaultValue = "true") boolean enabled) {
        settings.setDependencyFault(target, enabled);
        return state();
    }

    /** 注入连接池饱和、锁等待或消息重投的安全实验状态。 */
    @PostMapping("/scenario")
    public Map<String, Object> scenario(@RequestParam String target,
                                        @RequestParam(defaultValue = "true") boolean enabled) {
        settings.setScenarioFault(target, enabled);
        return state();
    }

    @PostMapping("/messages")
    public Map<String, Object> publishSyntheticMessages(@RequestParam(defaultValue = "20") int count) {
        if (count < 1 || count > 200) throw new IllegalArgumentException("消息数量必须是 1 到 200");
        for (int i = 0; i < count; i++) {
            VoucherOrder order = new VoucherOrder();
            order.setId(sequence.incrementAndGet());
            order.setUserId(-1L);
            order.setVoucherId(-1L);
            order.setStatus(1);
            order.setDiagnosticMessage(true);
            // 直接投递主队列，避免实验消息进入订单超时队列。
            rabbitTemplate.convertAndSend("", "seckill.queue", order);
        }
        Map<String, Object> result = state();
        result.put("published", count);
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        settings.reset();
        MessageListenerContainer container = registry.getListenerContainer("seckillOrderListener");
        // 先完整停掉消费者，再清理实验队列，避免 start/stop 交错时上一案例的消息被下一案例消费。
        if (container != null && container.isRunning()) container.stop();
        rabbitTemplate.execute(channel -> {
            channel.queuePurge("seckill.queue");
            return null;
        });
        diagnosticEvents.clear();
        // Timer 是进程级累计指标。实验切换时清空这两个 Meter，避免上一轮的慢处理
        // 混入下一轮窗口；真实业务指标和订单数据不会受到影响。
        removeMeters("campus.seckill.consume.duration");
        removeMeters("campus.seckill.database.duration");
        if (container != null && !container.isRunning()) container.start();
        MessageListenerContainer audit = registry.getListenerContainer("orderDeadLetterListener");
        if (audit != null && !audit.isRunning()) audit.start();
        return state();
    }

    private void removeMeters(String name) {
        for (Meter meter : meterRegistry.find(name).meters()) {
            meterRegistry.remove(meter);
        }
    }

    private Map<String, Object> state() {
        Map<String, Object> result = new HashMap<>();
        result.put("processingDelayMs", settings.getProcessingDelayMs());
        result.put("databaseDelayMs", settings.getDatabaseDelayMs());
        result.put("dependencyFault", settings.getDependencyFault());
        result.put("scenarioFault", settings.getScenarioFault());
        MessageListenerContainer consumer = registry.getListenerContainer("seckillOrderListener");
        MessageListenerContainer audit = registry.getListenerContainer("orderDeadLetterListener");
        result.put("consumerRunning", consumer != null && consumer.isRunning());
        result.put("auditRunning", audit != null && audit.isRunning());
        return result;
    }
}
