package com.campus.life.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.life.entity.OrderReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderReservationMapper extends BaseMapper<OrderReservation> {
    @Insert("INSERT IGNORE INTO tb_order_reservation(order_id,user_id,voucher_id,status,expires_at) "
            + "VALUES(#{id},#{user},#{voucher},0,#{expires})")
    int insertPending(@Param("id") Long id, @Param("user") Long user,
                      @Param("voucher") Long voucher, @Param("expires") LocalDateTime expires);

    @Select("SELECT * FROM tb_order_reservation WHERE order_id=#{id} FOR UPDATE")
    OrderReservation lock(@Param("id") Long id);

    /** 兼容升级前已发送的消息，优先从已提交的 outbox 恢复原始截止时间。 */
    @Insert("INSERT IGNORE INTO tb_order_reservation(order_id,user_id,voucher_id,status,expires_at,payment_expires_at) "
            + "SELECT CAST(m.message_id AS UNSIGNED),JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.userId')),"
            + "JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.voucherId')),"
            + "CASE WHEN o.id IS NULL THEN 0 WHEN o.status=1 THEN 1 WHEN o.status=4 THEN 2 ELSE 3 END,"
            + "TIMESTAMPADD(SECOND,#{timeout},m.create_time),"
            + "TIMESTAMPADD(SECOND,#{paymentTimeout},o.create_time) FROM tb_local_message m "
            + "LEFT JOIN tb_voucher_order o ON o.id=#{id} WHERE m.message_id=CAST(#{id} AS CHAR)")
    int adoptOutbox(@Param("id") Long id, @Param("timeout") long timeout,
                    @Param("paymentTimeout") long paymentTimeout);

    @Insert("INSERT IGNORE INTO tb_order_reservation(order_id,user_id,voucher_id,status,expires_at,payment_expires_at) "
            + "SELECT id,user_id,voucher_id,CASE WHEN status=1 THEN 1 WHEN status=4 THEN 2 ELSE 3 END,"
            + "TIMESTAMPADD(SECOND,#{timeout},create_time),TIMESTAMPADD(SECOND,#{paymentTimeout},create_time) "
            + "FROM tb_voucher_order WHERE id=#{id}")
    int adoptExisting(@Param("id") Long id, @Param("timeout") long timeout,
                      @Param("paymentTimeout") long paymentTimeout);

    @Update("UPDATE tb_order_reservation SET status=1,payment_expires_at=TIMESTAMPADD(SECOND,#{timeout},NOW()) "
            + "WHERE order_id=#{id} AND status=0")
    int markCreated(@Param("id") Long id, @Param("timeout") long paymentTimeout);

    @Update("UPDATE tb_order_reservation SET status=2,cancel_reason=#{reason} "
            + "WHERE order_id=#{id} AND status IN (0,1)")
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE tb_order_reservation SET status=3 WHERE order_id=#{id} AND status IN (0,1)")
    int markCompleted(@Param("id") Long id);

    @Update("UPDATE tb_order_reservation SET redis_released=1 WHERE order_id=#{id} AND status=2")
    int markReleased(@Param("id") Long id);

    @Select("SELECT * FROM tb_order_reservation WHERE status=0 AND expires_at<=NOW() "
            + "ORDER BY expires_at LIMIT #{limit}")
    List<OrderReservation> findExpired(@Param("limit") int limit);

    @Select("SELECT * FROM tb_order_reservation WHERE status=1 AND payment_expires_at<=NOW() "
            + "ORDER BY payment_expires_at LIMIT #{limit}")
    List<OrderReservation> findPaymentExpired(@Param("limit") int limit);

    @Select("SELECT * FROM tb_order_reservation WHERE status=2 AND redis_released=0 "
            + "ORDER BY update_time LIMIT #{limit}")
    List<OrderReservation> findUnreleased(@Param("limit") int limit);
}
