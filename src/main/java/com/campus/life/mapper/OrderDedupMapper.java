package com.campus.life.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.life.entity.OrderDedup;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface OrderDedupMapper extends BaseMapper<OrderDedup> {

    /**
     * 订单号或 (user_id, voucher_id) 冲突时影响行数为 0。
     * 调用方据此判断"这条消息已经处理过"，实现幂等消费。
     */
    @Insert("INSERT IGNORE INTO tb_order_dedup(order_id, user_id, voucher_id, status) "
            + "VALUES(#{orderId}, #{userId}, #{voucherId}, 0)")
    int insertIgnore(@Param("orderId") Long orderId,
                     @Param("userId") Long userId,
                     @Param("voucherId") Long voucherId);

    @Update("UPDATE tb_order_dedup SET status = 1 WHERE order_id = #{orderId}")
    int markDone(@Param("orderId") Long orderId);
}

