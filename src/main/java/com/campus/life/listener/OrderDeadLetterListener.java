package com.campus.life.listener;

import com.campus.life.config.RabbitMQConfig;
import com.campus.life.entity.OrderRetry;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderRetryMapper;
import com.campus.life.service.ISeckillVoucherService;
import com.campus.life.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class OrderDeadLetterListener {

    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private OrderRetryMapper orderRetryMapper;

    @RabbitListener(id = "orderDeadLetterListener", bindings = @QueueBinding(
            value = @Queue(name = "order.dead.queue", durable = "true"),
            exchange = @Exchange(name = RabbitMQConfig.DLX_EXCHANGE, type = ExchangeTypes.DIRECT),
            key = RabbitMQConfig.DEAD_ROUTING_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void inspectOrder(VoucherOrder voucherOrder) {
        log.info("死信队列收到超时检查任务: {}", voucherOrder);

        Long orderId = voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();

        // 有重试记录说明这条订单的入库还在由 OrderRetryTask 负责，
        // 此时既不能判"入库失败"，也不能回滚 Redis —— 否则会和重试任务重复回滚。
        OrderRetry retryRecord = orderRetryMapper.selectById(orderId);
        if (retryRecord != null) {
            log.info("订单 {} 存在入库重试记录（status={}），跳过超时审计，交由重试任务处理",
                    orderId, retryRecord.getStatus());
            return;
        }

        // 1. 去数据库查真实状态
        VoucherOrder dbOrder = voucherOrderService.getById(orderId);

        // ============ 场景 A：入库彻底失败兜底 ============
        if (dbOrder == null) {
            log.error("【系统纠错】查无订单 {}！入库队列可能发生严重故障。紧急回滚 Redis 库存！", orderId);
            voucherOrderService.compensateRedisStock(voucherId, voucherOrder.getUserId());
            return;
        }

        // ============ 场景 B：正常完成交易 ============
        if (dbOrder.getStatus() != 1) {
            log.info("【审计通过】订单 {} 状态为 {} (已支付/已取消)，流程正常结束。", orderId, dbOrder.getStatus());
            return;
        }

        // ============ 场景 C：超时未支付关单 ============
        log.warn("【超时关单】订单 {} 未支付，执行闭环回滚程序...", orderId);

        boolean updateStatus = voucherOrderService.update()
                .set("status", 4).eq("id", orderId).eq("status", 1).update();

        if (updateStatus) {
            seckillVoucherService.update().setSql("stock = stock + 1")
                    .eq("voucher_id", voucherId).update();
            voucherOrderService.compensateRedisStock(voucherId, dbOrder.getUserId());
            log.info("【超时关单】订单 {} 资源释放完毕！", orderId);
        }
    }
}

