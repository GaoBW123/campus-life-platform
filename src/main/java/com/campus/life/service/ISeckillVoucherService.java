package com.campus.life.service;

import com.campus.life.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.life.entity.VoucherOrder;

/** 秒杀优惠券表，与优惠券是一对一关系 服务类 */
public interface ISeckillVoucherService extends IService<SeckillVoucher> {
    public boolean deductStock(Long voucherId);
}
