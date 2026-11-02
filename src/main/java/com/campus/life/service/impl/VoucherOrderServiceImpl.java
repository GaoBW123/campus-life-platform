package com.campus.life.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.life.config.RabbitMQConfig;
import com.campus.life.diagnostics.SeckillFaultSettings;
import com.campus.life.dto.Result;
import com.campus.life.entity.OrderCorrelationData;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderDedupMapper;
import com.campus.life.mapper.VoucherOrderMapper;
import com.campus.life.service.ISeckillVoucherService;
import com.campus.life.service.IVoucherOrderService;
import com.campus.life.service.LocalMessageService;
import com.campus.life.service.OrderReservationService;
import com.campus.life.service.RedisReservationService;
import com.campus.life.utils.UserHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/** 服务实现类 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 锁粒度是用户：同一用户的并发下单被串行化，不同用户互不阻塞。 */
    private static final String ORDER_LOCK_PREFIX = "lock:order:";

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisReservationService redisReservations;

    @Autowired
    private OrderReservationService reservations;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SeckillFaultSettings faultSettings;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderDedupMapper orderDedupMapper;

    @Autowired
    private LocalMessageService localMessageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${seckill.lock.lease-seconds:10}")
    private long lockLeaseSeconds;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    public void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 预占绑定订单号，补偿时只能释放该订单自己的库存。
        long orderId = IdUtil.getSnowflakeNextId();
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setStatus(1);
        Long result = redisReservations.reserve(voucherOrder);
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : r == 2 ? "不能重复下单" : "秒杀库存未就绪");
        }

        String orderJson = JSONUtil.toJsonStr(voucherOrder);

        // 3. 先写本地消息表（独立事务，返回即已提交），再投递。
        //    Redis 预扣已经发生，如果这里不落库，进程在发送前崩溃就会静默少卖。
        try {
            if (!localMessageService.savePending(voucherOrder,
                    RabbitMQConfig.SECKILL_EXCHANGE, RabbitMQConfig.SECKILL_ROUTING_KEY, orderJson)) {
                throw new IllegalStateException("本地消息落库失败");
            }
        } catch (RuntimeException persistenceFailure) {
            try { redisReservations.release(orderId, voucherId, userId); }
            catch (RuntimeException releaseFailure) { persistenceFailure.addSuppressed(releaseFailure); }
            log.error("受理事务失败，释放当前订单的 Redis 预占，orderId={}", orderId, persistenceFailure);
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 4. 事务已提交，再发 MQ；发送失败交给定时任务补发，不影响本次响应
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ROUTING_KEY, voucherOrder,
                    new OrderCorrelationData(String.valueOf(orderId), orderJson,
                            RabbitMQConfig.SECKILL_EXCHANGE, RabbitMQConfig.SECKILL_ROUTING_KEY));
        } catch (RuntimeException e) {
            log.warn("消息投递失败，等待本地消息表补发，orderId={}", orderId, e);
            localMessageService.markRetry(String.valueOf(orderId), e.getMessage());
        }
        return Result.ok(orderId);
    }

    /**
     * 消费端建单：Redisson 锁串行化同一用户，锁内查重挡住重复请求，
     * 防重表 + 扣库存 + 建单在同一个本地事务里完成。
     */
    @Override
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(ORDER_LOCK_PREFIX + userId);
        boolean locked = false;
        try {
            // waitTime=0：抢不到就失败，由重试任务接手，绝不在这里排队阻塞消费线程
            locked = lock.tryLock(0, lockLeaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("下单锁被占用，userId=" + userId);
            }

            Boolean created = transactionTemplate.execute(status -> persist(voucherOrder));
            // 事务提交后才释放 Redis；失败由持久化补偿记录重试。
            if (!Boolean.TRUE.equals(created)) reservations.releaseEventually(voucherOrder.getId());
            return Boolean.TRUE.equals(created);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("创建订单时锁被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean persist(VoucherOrder voucherOrder) {
        Timer.Sample databaseSample = Timer.start(meterRegistry);
        try {
            OrderReservationService.Creation decision = reservations.prepareCreation(voucherOrder);
            if (decision == OrderReservationService.Creation.CANCELLED) return false;
            if (decision == OrderReservationService.Creation.ALREADY_CREATED) return true;
            faultSettings.delayDatabaseIfConfigured();

            // 主键是订单号、唯一键是 (user_id, voucher_id)：重复消息在这里返回 0 行
            int inserted = orderDedupMapper.insertIgnore(voucherOrder.getId(),
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            if (inserted == 0) {
                reservations.duplicate(voucherOrder.getId());
                return false;
            }

            boolean success = seckillVoucherService.deductStock(voucherOrder.getVoucherId());
            if (!success) {
                throw new IllegalStateException("数据库库存扣减失败，券ID：" + voucherOrder.getVoucherId());
            }
            if (!save(voucherOrder)) throw new IllegalStateException("订单没有成功写入");
            orderDedupMapper.markDone(voucherOrder.getId());
            reservations.created(voucherOrder.getId());
            return true;
        } finally {
            databaseSample.stop(Timer.builder("campus.seckill.database.duration")
                    .description("秒杀订单数据库事务处理耗时")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    @Override
    public void simulateDiagnosticDatabaseStage() {
        Timer.Sample databaseSample = Timer.start(meterRegistry);
        try {
            faultSettings.delayDatabaseIfConfigured();
        } finally {
            databaseSample.stop(Timer.builder("campus.seckill.database.duration")
                    .description("秒杀订单数据库事务处理耗时")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }
}

