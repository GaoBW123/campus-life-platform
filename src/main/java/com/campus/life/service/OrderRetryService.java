package com.campus.life.service;

import cn.hutool.json.JSONUtil;
import com.campus.life.entity.OrderRetry;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderRetryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 消费失败时登记一条重试记录，代替"把异常抛回容器触发 requeue"。 */
@Slf4j
@Service
public class OrderRetryService {

    private final OrderRetryMapper orderRetryMapper;

    @Value("${seckill.order.backoff-seconds:10}")
    private long backoffSeconds;

    public OrderRetryService(OrderRetryMapper orderRetryMapper) {
        this.orderRetryMapper = orderRetryMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void schedule(VoucherOrder order, Throwable error) {
        String message = error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage();
        orderRetryMapper.upsert(order.getId(), JSONUtil.toJsonStr(order),
                LocalDateTime.now().plusSeconds(backoffSeconds), LocalMessageService.truncate(message));
        log.warn("订单入库失败，已登记重试，orderId={}, reason={}", order.getId(), message);
    }

    public OrderRetry find(Long orderId) {
        return orderRetryMapper.selectById(orderId);
    }
}

