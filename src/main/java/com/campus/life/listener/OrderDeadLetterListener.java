package com.campus.life.listener;

import com.campus.life.config.RabbitMQConfig;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.service.OrderReservationService;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderDeadLetterListener {
    private final OrderReservationService reservations;

    public OrderDeadLetterListener(OrderReservationService reservations) {
        this.reservations = reservations;
    }

    @RabbitListener(id = "orderDeadLetterListener", bindings = @QueueBinding(
            value = @Queue(name = "order.dead.queue", durable = "true"),
            exchange = @Exchange(name = RabbitMQConfig.DLX_EXCHANGE, type = ExchangeTypes.DIRECT),
            key = RabbitMQConfig.DEAD_ROUTING_KEY))
    public void inspectOrder(VoucherOrder order) {
        // 仅检查待建单超时；已建单的支付期限由数据库扫描处理。
        reservations.expire(order.getId());
    }
}
