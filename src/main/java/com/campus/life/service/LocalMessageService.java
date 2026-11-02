package com.campus.life.service;

import com.campus.life.entity.LocalMessage;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.LocalMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 本地消息表写入与状态流转。
 * savePending 是独立事务：方法返回时消息已经提交，之后才允许向 MQ 投递。
 */
@Slf4j
@Service
public class LocalMessageService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final LocalMessageMapper localMessageMapper;
    private final OrderReservationService reservations;

    @Value("${seckill.outbox.initial-delay-seconds:30}")
    private long initialDelaySeconds;

    @Value("${seckill.outbox.retry-delay-seconds:60}")
    private long retryDelaySeconds;

    public LocalMessageService(LocalMessageMapper localMessageMapper, OrderReservationService reservations) {
        this.localMessageMapper = localMessageMapper;
        this.reservations = reservations;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean savePending(VoucherOrder order, String exchange, String routingKey, String payload) {
        String messageId = String.valueOf(order.getId());
        reservations.register(order);
        LocalMessage message = new LocalMessage()
                .setMessageId(messageId)
                .setExchange(exchange)
                .setRoutingKey(routingKey)
                .setPayload(payload)
                .setStatus(0)
                .setRetryCount(0)
                .setNextRetryTime(LocalDateTime.now().plusSeconds(initialDelaySeconds));
        try {
            if (localMessageMapper.insert(message) != 1) throw new IllegalStateException("本地消息没有成功写入");
            return true;
        } catch (DuplicateKeyException e) {
            // 同一订单号重复写入，说明已经有一条待投递消息，直接视为成功
            log.info("本地消息已存在，跳过写入，messageId={}", messageId);
            return true;
        }
    }

    public void markConfirmed(String messageId) {
        localMessageMapper.markConfirmed(messageId);
    }

    public void markRetry(String messageId, String error) {
        localMessageMapper.markRetry(messageId,
                LocalDateTime.now().plusSeconds(retryDelaySeconds), truncate(error));
    }

    public static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}

