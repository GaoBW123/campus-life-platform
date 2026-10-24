package com.campus.life.task;

import cn.hutool.json.JSONUtil;
import com.campus.life.entity.LocalMessage;
import com.campus.life.entity.OrderCorrelationData;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.LocalMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表补发任务。
 * 注意投递的是 VoucherOrder 对象而不是 JSON 字符串：原来的 MqRetryTask 用 String 重发，
 * 消费端按 VoucherOrder 反序列化必然失败，那条补发路径其实是无效的。
 */
@Slf4j
@Component
public class LocalMessageRelayTask {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ERROR_LENGTH = 500;

    private final LocalMessageMapper localMessageMapper;

    private final RabbitTemplate rabbitTemplate;

    @Value("${seckill.outbox.max-retry:8}")
    private int maxRetry;

    @Value("${seckill.outbox.retry-delay-seconds:60}")
    private long retryDelaySeconds;

    public LocalMessageRelayTask(LocalMessageMapper localMessageMapper, RabbitTemplate rabbitTemplate) {
        this.localMessageMapper = localMessageMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${seckill.outbox.relay-interval-ms:5000}")
    public void relay() {
        List<LocalMessage> due = localMessageMapper.findDue(maxRetry, BATCH_SIZE);
        if (due.isEmpty()) {
            return;
        }
        for (LocalMessage message : due) {
            try {
                VoucherOrder order = JSONUtil.toBean(message.getPayload(), VoucherOrder.class);
                rabbitTemplate.convertAndSend(message.getExchange(), message.getRoutingKey(), order,
                        new OrderCorrelationData(message.getMessageId(), message.getPayload(),
                                message.getExchange(), message.getRoutingKey()));
                // 投出去后把下次重试时间推后，等 Confirm 回调把它置为已确认
                localMessageMapper.scheduleNext(message.getId(),
                        LocalDateTime.now().plusSeconds(retryDelaySeconds));
                log.info("本地消息补发完成，messageId={}", message.getMessageId());
            } catch (RuntimeException e) {
                log.error("本地消息补发失败，messageId={}", message.getMessageId(), e);
                localMessageMapper.markRetry(message.getMessageId(),
                        LocalDateTime.now().plusSeconds(retryDelaySeconds), truncate(e));
            }
        }
    }

    private String truncate(Throwable e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}

