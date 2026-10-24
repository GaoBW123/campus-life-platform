package com.campus.life.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.life.entity.LocalMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /** 到期待投递的消息：未确认、未放弃，且已过下次重试时间。 */
    @Select("SELECT * FROM tb_local_message "
            + "WHERE status IN (0, 2) AND retry_count < #{maxRetry} "
            + "AND (next_retry_time IS NULL OR next_retry_time <= NOW()) "
            + "ORDER BY id LIMIT #{limit}")
    List<LocalMessage> findDue(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Update("UPDATE tb_local_message SET status = 1, last_error = NULL "
            + "WHERE message_id = #{messageId} AND status IN (0, 2)")
    int markConfirmed(@Param("messageId") String messageId);

    @Update("UPDATE tb_local_message SET status = 2, retry_count = retry_count + 1, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError} "
            + "WHERE message_id = #{messageId} AND status IN (0, 2)")
    int markRetry(@Param("messageId") String messageId,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError);

    /** 投递过但一直等不到 Confirm，先把下次重试时间往后推，避免每秒重发。 */
    @Update("UPDATE tb_local_message SET status = 2, retry_count = retry_count + 1, "
            + "next_retry_time = #{nextRetryTime} WHERE id = #{id} AND status IN (0, 2)")
    int scheduleNext(@Param("id") Long id,
                     @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("UPDATE tb_local_message SET status = 3, last_error = #{lastError} WHERE id = #{id}")
    int markDead(@Param("id") Long id, @Param("lastError") String lastError);
}

