package com.campus.life.listener;

import com.campus.life.diagnostics.SeckillDiagnosticEventBuffer;
import com.campus.life.diagnostics.SeckillFaultSettings;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.service.IVoucherOrderService;
import com.campus.life.service.OrderRetryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SeckillListener {

    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private SeckillDiagnosticEventBuffer diagnosticEvents;
    @Autowired
    private SeckillFaultSettings faultSettings;
    @Autowired
    private OrderRetryService orderRetryService;

    @RabbitListener(id = "seckillOrderListener", bindings = @QueueBinding(
            value = @Queue(value = "seckill.queue", autoDelete = "false"),
            exchange = @Exchange(value = "seckill.exchange", type = ExchangeTypes.DIRECT),
            key = "seckill.order"))
    public void receiveSeckill(VoucherOrder voucherOrder) {
        log.info("收到秒杀订单消息，订单ID：{}", voucherOrder.getId());
        long startedAt = System.nanoTime();
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean cancelled = false;
        try {
            if (Boolean.TRUE.equals(voucherOrder.getDiagnosticMessage())
                    && faultSettings.isScenarioFault("REDELIVERY")) {
                diagnosticEvents.addSimulatedRedeliveries(voucherOrder.getId());
                return;
            }
            faultSettings.delayProcessingIfConfigured();
            if (Boolean.TRUE.equals(voucherOrder.getDiagnosticMessage())) {
                // 合成消息经过相同队列、消费者和指标，但不污染订单与库存数据。
                voucherOrderService.simulateDiagnosticDatabaseStage();
            } else {
                cancelled = !voucherOrderService.createVoucherOrder(voucherOrder);
            }
            diagnosticEvents.add(voucherOrder.getId(),
                    Boolean.TRUE.equals(voucherOrder.getDiagnosticMessage()) ? "SYNTHETIC_SUCCESS"
                            : cancelled ? "CANCELLED" : "SUCCESS",
                    elapsedMillis(startedAt), null);
        } catch (RuntimeException e) {
            diagnosticEvents.add(voucherOrder.getId(), "FAILED",
                    elapsedMillis(startedAt), e.getClass().getSimpleName());
            log.error("处理秒杀订单失败，转入入库重试，orderId={}", voucherOrder.getId(), e);
            // 关键改动：不再把异常抛回容器。
            // 抛回去会让 RabbitMQ 无限 requeue，同一条坏消息可以打满所有消费线程。
            // 改成落一条重试记录，由 OrderRetryTask 退避重试，失败到上限再回滚 Redis。
            orderRetryService.schedule(voucherOrder, e);
        } finally {
            sample.stop(Timer.builder("campus.seckill.consume.duration")
                    .description("秒杀订单消费者端到端处理耗时")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

