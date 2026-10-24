package com.campus.life.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("mq_error_log")
public class MqErrorLog {
    private static final long serialVersionUID = 1L;
    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;
    private String exchange;
    private String routingKey;
    private String messageContent;
    private String errorReason;
    private Integer retryCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
