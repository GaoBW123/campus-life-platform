package com.campus.life.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 建单、关单和重试共用的持久化状态；取消记录保留，拦住迟到的消费。 */
@Data
@TableName("tb_order_reservation")
public class OrderReservation {
    public static final int PENDING = 0;
    public static final int CREATED = 1;
    public static final int CANCELLED = 2;
    public static final int COMPLETED = 3;

    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Integer status;
    private LocalDateTime expiresAt;
    private LocalDateTime paymentExpiresAt;
    private Boolean redisReleased;
    private String cancelReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
