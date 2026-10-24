package com.campus.life.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

@Configuration
public class RabbitMQConfig {

    /**
     * 生产端和监听端共用 JSON 转换器，使 VoucherOrder 对象按明确类型序列化/反序列化。
     * 没有它时默认转换器只能得到 byte[]/String，无法绑定到监听方法的 VoucherOrder 参数。
     */
    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // 1. 秒杀主交换机 (负责同时把消息派发给“入库队列”和“延迟队列”)
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    // 2. 延迟队列 (定时炸弹，千万不要给它写消费者！)
    public static final String DELAY_QUEUE = "order.delay.queue";

    // 死信交换机和路由键的名字 (供延迟队列到期后踢出消息使用)
    public static final String DLX_EXCHANGE = "order.dlx.exchange";
    public static final String DEAD_ROUTING_KEY = "order.dead.routing.key";

    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                // 【测试时设为 15000 (15秒)，上线改为 900000 (15分钟)】
                .ttl(15000)
                // 绑定死信交换机
                .deadLetterExchange(DLX_EXCHANGE)
                // 绑定死信路由键
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    // 3. 将延迟队列绑定到秒杀主交换机上
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(seckillExchange()).with(SECKILL_ROUTING_KEY);
    }
}
