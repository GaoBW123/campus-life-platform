package com.campus.life.task;

import com.campus.life.entity.OrderReservation;
import com.campus.life.mapper.OrderReservationMapper;
import com.campus.life.service.OrderReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderReservationTask {
    private final OrderReservationMapper reservations;
    private final OrderReservationService service;

    public OrderReservationTask(OrderReservationMapper reservations, OrderReservationService service) {
        this.reservations = reservations;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${seckill.order.sweep-interval-ms:5000}")
    public void recover() {
        for (OrderReservation order : reservations.findExpired(100)) {
            try { service.expire(order.getOrderId()); }
            catch (RuntimeException failure) { log.warn("超时订单处理等待重试：{}", order.getOrderId(), failure); }
        }
        for (OrderReservation order : reservations.findPaymentExpired(100)) {
            try { service.expirePayment(order.getOrderId()); }
            catch (RuntimeException failure) { log.warn("支付超时关单等待重试：{}", order.getOrderId(), failure); }
        }
        for (OrderReservation order : reservations.findUnreleased(100)) {
            try { service.releaseCancelled(order.getOrderId()); }
            catch (RuntimeException failure) { log.warn("Redis 库存释放等待重试：{}", order.getOrderId(), failure); }
        }
    }
}
