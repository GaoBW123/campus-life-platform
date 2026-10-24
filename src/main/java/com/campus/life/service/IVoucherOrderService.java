package com.campus.life.service;

import com.campus.life.dto.Result;
import com.campus.life.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/** 服务类 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    public Result seckillVoucher(Long voucher);

    /** 幂等 + 分布式锁 + 本地事务落库。 */
    public void createVoucherOrder(VoucherOrder voucherOrder);

    /** 回滚 Redis 预扣（库存 +1，并移除一人一单标记）。 */
    public void compensateRedisStock(Long voucherId, Long userId);

    /** 执行受控的数据库阶段延迟与计时，不写入业务表。 */
    public void simulateDiagnosticDatabaseStage();

}

