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
 * 订单防重表。主键是订单号，唯一键是 (user_id, voucher_id)：
 * 主键用来挡住"同一条消息被重复投递"，唯一键用来兜住"一人一单"。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_order_dedup")
public class OrderDedup implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;

    private Long userId;

    private Long voucherId;

    /** 0=处理中 1=已建单 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

