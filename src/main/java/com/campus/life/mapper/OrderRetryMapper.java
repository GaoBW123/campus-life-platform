package com.campus.life.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.life.entity.OrderRetry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRetryMapper extends BaseMapper<OrderRetry> {

    /** 同一订单的重复失败合并成一条记录，只累加重试次数。 */
    @Insert("INSERT INTO tb_order_retry(order_id, payload, retry_count, status, next_retry_time, last_error) "
            + "VALUES(#{orderId}, #{payload}, 0, 0, #{nextRetryTime}, #{lastError}) "
            + "ON DUPLICATE KEY UPDATE retry_count = retry_count + 1, status = 0, "
            + "next_retry_time = VALUES(next_retry_time), last_error = VALUES(last_error)")
    int upsert(@Param("orderId") Long orderId,
               @Param("payload") String payload,
               @Param("nextRetryTime") LocalDateTime nextRetryTime,
               @Param("lastError") String lastError);

    @Select("SELECT * FROM tb_order_retry WHERE status = 0 AND next_retry_time <= NOW() "
            + "ORDER BY next_retry_time LIMIT #{limit}")
    List<OrderRetry> findDue(@Param("limit") int limit);

    @Update("UPDATE tb_order_retry SET status = 1 WHERE order_id = #{orderId}")
    int markSuccess(@Param("orderId") Long orderId);

    @Update("UPDATE tb_order_retry SET retry_count = #{retryCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError} WHERE order_id = #{orderId}")
    int reschedule(@Param("orderId") Long orderId,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);

    @Update("UPDATE tb_order_retry SET status = 2, last_error = #{lastError} WHERE order_id = #{orderId}")
    int markGiveUp(@Param("orderId") Long orderId, @Param("lastError") String lastError);
}

