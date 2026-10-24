package com.campus.life.config;

import com.campus.life.entity.MqErrorLog;
import com.campus.life.entity.OrderCorrelationData;
import com.campus.life.mapper.MqErrorLogMapper;
import com.campus.life.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class RabbitMqConfirmCallback implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnCallback {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MqErrorLogMapper mqErrorLogMapper;

    @Autowired
    private LocalMessageService localMessageService;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    /**
     * Confirm 回调：Broker 是否收下了消息。
     * 收下 -> 把本地消息表置为已确认；没收下 -> 置为待重发，由补发任务重投。
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        String messageId = correlationData == null ? null : correlationData.getId();
        if (ack) {
            if (messageId != null) {
                localMessageService.markConfirmed(messageId);
            }
            log.debug("消息发送成功，消息ID：{}", messageId);
            return;
        }

        log.error("消息发送失败，messageId={}，原因：{}", messageId, cause);
        if (messageId != null) {
            localMessageService.markRetry(messageId, cause);
        }
        if (correlationData instanceof OrderCorrelationData) {
            saveErrorLog((OrderCorrelationData) correlationData, cause);
        }
    }

    @Override
    public void returnedMessage(Message message, int replyCode, String replyText, String exchange, String routingKey) {
        log.error("消息被退回！交换机：{}, 路由键：{}, 回退原因：{}", exchange, routingKey, replyText);
    }

    private void saveErrorLog(OrderCorrelationData data, String cause) {
        MqErrorLog errorLog = new MqErrorLog();
        errorLog.setMessageId(data.getId());
        errorLog.setExchange(data.getExchange());
        errorLog.setRoutingKey(data.getRoutingKey());
        errorLog.setMessageContent(data.getMessageContent());
        errorLog.setErrorReason(cause);
        mqErrorLogMapper.insert(errorLog);
    }
}

