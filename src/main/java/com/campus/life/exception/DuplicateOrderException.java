package com.campus.life.exception;

/** 防重表命中：该订单已经处理过。这不是故障，是幂等生效。 */
public class DuplicateOrderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long orderId;

    public DuplicateOrderException(Long orderId) {
        super("订单已存在：" + orderId);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}

