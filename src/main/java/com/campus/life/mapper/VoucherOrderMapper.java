package com.campus.life.mapper;

import com.campus.life.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Mapper 接口 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {
    @Select("SELECT * FROM tb_voucher_order WHERE id=#{id} FOR UPDATE")
    VoucherOrder lockById(@Param("id") Long id);

    @Update("UPDATE tb_voucher_order SET status=4 WHERE id=#{id} AND status=1")
    int closeUnpaid(@Param("id") Long id);
}
