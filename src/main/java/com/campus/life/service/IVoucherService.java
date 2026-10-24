package com.campus.life.service;

import com.campus.life.dto.Result;
import com.campus.life.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/** 服务类 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
