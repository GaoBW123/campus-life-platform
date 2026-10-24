package com.campus.life.service.impl;

import com.campus.life.entity.SeckillVoucher;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.SeckillVoucherMapper;
import com.campus.life.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 秒杀优惠券表，与优惠券是一对一关系 服务实现类 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
    @Override
    public boolean deductStock(Long voucherId) {
        // 专门将底层的数据库扣减逻辑封装在这里
        return this.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
    }
}
