package com.campus.life.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表（事务性 outbox）。
 * 先落库、再投递：进程在"预扣成功、发送之前"挂掉时，消息仍然在库里，由定时任务补发。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_local_message")
public class LocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务消息 ID，这里就是订单号 */
    private String messageId;

    private String exchange;

    private String routingKey;

    private String payload;

    /** 0=待投递 1=Broker 已确认 2=待重发 3=放弃 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

