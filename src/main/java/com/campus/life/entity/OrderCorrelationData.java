package com.campus.life.entity;


import org.springframework.amqp.rabbit.connection.CorrelationData;

public class OrderCorrelationData extends CorrelationData {
    // 把你要发送的原始订单消息存起来
    private String messageContent;
    private String exchange;
    private String routingKey;

    public OrderCorrelationData(String id, String messageContent, String exchange, String routingKey) {
        super(id);
        this.messageContent = messageContent;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }
}
