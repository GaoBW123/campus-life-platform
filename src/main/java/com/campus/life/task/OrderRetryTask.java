package com.campus.life.task;

import cn.hutool.json.JSONUtil;
import com.campus.life.entity.OrderRetry;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderRetryMapper;
import com.campus.life.service.IVoucherOrderService;
import com.campus.life.service.OrderReservationService;
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
    private final OrderReservationService reservations;

    @Value("${seckill.order.max-retry:3}")
    private int maxRetry;

    @Value("${seckill.order.backoff-seconds:10}")
    private long backoffSeconds;

    public OrderRetryTask(OrderRetryMapper orderRetryMapper, IVoucherOrderService voucherOrderService,
                          OrderReservationService reservations) {
        this.orderRetryMapper = orderRetryMapper;
        this.voucherOrderService = voucherOrderService;
        this.reservations = reservations;
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
                if (voucherOrderService.createVoucherOrder(order)) {
                    orderRetryMapper.markSuccess(order.getId());
                    log.info("订单补入库成功，orderId={}", order.getId());
                } else {
                    orderRetryMapper.markGiveUp(order.getId(), "订单已取消，禁止迟到重试建单");
                }
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
     * 与超时检查共用取消状态；已经建单的订单不能因旧重试任务而被额外回补。
     */
    private void giveUp(OrderRetry record, String reason) {
        reservations.giveUp(record.getOrderId(), truncate(reason));
        orderRetryMapper.markGiveUp(record.getOrderId(), truncate(reason));
        log.warn("订单重试已终止，库存释放由订单状态决定，orderId={}, reason={}", record.getOrderId(), reason);
    }

    private String truncate(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }
}

