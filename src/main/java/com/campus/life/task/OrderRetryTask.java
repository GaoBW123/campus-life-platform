package com.campus.life.task;

import cn.hutool.json.JSONUtil;
import com.campus.life.entity.OrderRetry;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderRetryMapper;
import com.campus.life.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单入库重试任务。
 * 每次重试重新走一遍 createVoucherOrder，防重表保证即使重复执行也只建一单。
 */
@Slf4j
@Component
public class OrderRetryTask {

    private static final int BATCH_SIZE = 50;

    private final OrderRetryMapper orderRetryMapper;

    private final IVoucherOrderService voucherOrderService;

    @Value("${seckill.order.max-retry:3}")
    private int maxRetry;

    @Value("${seckill.order.backoff-seconds:10}")
    private long backoffSeconds;

    public OrderRetryTask(OrderRetryMapper orderRetryMapper, IVoucherOrderService voucherOrderService) {
        this.orderRetryMapper = orderRetryMapper;
        this.voucherOrderService = voucherOrderService;
    }

    @Scheduled(fixedDelayString = "${seckill.order.retry-interval-ms:5000}")
    public void retryFailedOrders() {
        List<OrderRetry> due = orderRetryMapper.findDue(BATCH_SIZE);
        for (OrderRetry record : due) {
            VoucherOrder order = JSONUtil.toBean(record.getPayload(), VoucherOrder.class);
            if (record.getRetryCount() >= maxRetry) {
                giveUp(record, "重试次数已达上限");
                continue;
            }
            try {
                voucherOrderService.createVoucherOrder(order);
                orderRetryMapper.markSuccess(order.getId());
                log.info("订单补入库成功，orderId={}", order.getId());
            } catch (RuntimeException e) {
                int next = record.getRetryCount() + 1;
                String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (next >= maxRetry) {
                    giveUp(record, reason);
                } else {
                    orderRetryMapper.reschedule(order.getId(), next,
                            LocalDateTime.now().plusSeconds(backoffSeconds * next), truncate(reason));
                }
            }
        }
    }

    /**
     * 放弃重试：把预扣的 Redis 库存还回去，并清掉"一人一单"标记，用户可以重新抢。
     * 超时死信监听器看到重试记录会跳过，所以回滚只在这里执行一次。
     */
    private void giveUp(OrderRetry record, String reason) {
        VoucherOrder order = JSONUtil.toBean(record.getPayload(), VoucherOrder.class);
        orderRetryMapper.markGiveUp(record.getOrderId(), truncate(reason));
        voucherOrderService.compensateRedisStock(order.getVoucherId(), order.getUserId());
        log.error("订单重试耗尽，已回滚 Redis 预扣，orderId={}, reason={}", record.getOrderId(), reason);
    }

    private String truncate(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }
}

