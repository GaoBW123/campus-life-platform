package com.campus.life.service;

import com.campus.life.entity.OrderReservation;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderDedupMapper;
import com.campus.life.mapper.OrderReservationMapper;
import com.campus.life.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static com.campus.life.entity.OrderReservation.*;

/** 状态行锁贯穿 MySQL 建单/取消事务，Redis 释放在提交后重试。 */
@Slf4j
@Service
public class OrderReservationService {
    public enum Creation { CREATE, ALREADY_CREATED, CANCELLED }

    private final OrderReservationMapper reservations;
    private final VoucherOrderMapper orders;
    private final ISeckillVoucherService vouchers;
    private final OrderDedupMapper dedup;
    private final RedisReservationService redis;
    private final TransactionTemplate transactions;
    private final TransactionTemplate outsideTransactions;
    private final long timeoutSeconds;
    private final long paymentTimeoutSeconds;

    public OrderReservationService(OrderReservationMapper reservations, VoucherOrderMapper orders,
                                   ISeckillVoucherService vouchers, OrderDedupMapper dedup,
                                   RedisReservationService redis, PlatformTransactionManager transactionManager,
                                   @Value("${seckill.order.timeout-seconds:15}") long timeoutSeconds,
                                   @Value("${seckill.order.payment-timeout-seconds:900}") long paymentTimeoutSeconds) {
        if (timeoutSeconds <= 0 || paymentTimeoutSeconds <= 0) throw new IllegalArgumentException("订单超时必须大于零");
        this.reservations = reservations;
        this.orders = orders;
        this.vouchers = vouchers;
        this.dedup = dedup;
        this.redis = redis;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outsideTransactions = new TransactionTemplate(transactionManager);
        this.outsideTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.timeoutSeconds = timeoutSeconds;
        this.paymentTimeoutSeconds = paymentTimeoutSeconds;
    }

    /** 与 outbox 同事务提交，消息发出前已存在可取消的状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(VoucherOrder order) {
        reservations.insertPending(order.getId(), order.getUserId(), order.getVoucherId(),
                LocalDateTime.now().plusSeconds(timeoutSeconds));
        verifyOwner(reservations.selectById(order.getId()), order);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Creation prepareCreation(VoucherOrder order) {
        OrderReservation reservation = lock(order.getId());
        if (reservation == null) throw new IllegalStateException("订单缺少已提交的受理记录：" + order.getId());
        verifyOwner(reservation, order);
        if (reservation.getStatus() == CANCELLED) return Creation.CANCELLED;
        if (reservation.getStatus() == CREATED || reservation.getStatus() == COMPLETED) return Creation.ALREADY_CREATED;
        if (!LocalDateTime.now().isBefore(reservation.getExpiresAt())) {
            reservations.markCancelled(order.getId(), "超过建单等待期限");
            return Creation.CANCELLED;
        }
        return Creation.CREATE;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Long orderId) {
        if (reservations.markCreated(orderId, paymentTimeoutSeconds) != 1) throw new IllegalStateException("建单状态迁移失败：" + orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void duplicate(Long orderId) {
        reservations.markCancelled(orderId, "该用户已有有效订单");
    }

    /** 原延迟消息只处理建单等待超时，不能取消已经建好的订单。 */
    public void expire(Long orderId) { cancel(orderId, true, PENDING, "建单等待超时"); }

    /** 支付截止时间从建单成功开始，数据库扫描触发。 */
    public void expirePayment(Long orderId) { cancel(orderId, true, CREATED, "支付超时"); }

    /** 重试耗尽只允许取消尚未建单的订单，不能回补已经建单的库存。 */
    public void giveUp(Long orderId, String reason) { cancel(orderId, false, PENDING, reason); }

    private void cancel(Long orderId, boolean checkDeadline, int expectedStatus, String reason) {
        Boolean cancelled = transactions.execute(tx -> {
            OrderReservation reservation = lock(orderId);
            if (reservation == null) {
                log.warn("未找到订单受理记录，保留现场而不直接回补，orderId={}", orderId);
                return false;
            }
            if (reservation.getStatus() == CANCELLED) return true;
            if (reservation.getStatus() == COMPLETED) return false;
            if (reservation.getStatus() != expectedStatus) return false;
            if (checkDeadline) {
                LocalDateTime deadline = expectedStatus == PENDING
                        ? reservation.getExpiresAt() : reservation.getPaymentExpiresAt();
                if (deadline == null) throw new IllegalStateException("订单缺少阶段截止时间：" + orderId);
                if (LocalDateTime.now().isBefore(deadline)) return false;
            }
            VoucherOrder order = orders.lockById(orderId);
            if (order != null && order.getStatus() != 1 && order.getStatus() != 4) {
                reservations.markCompleted(orderId);
                return false;
            }
            if (order == null && reservation.getStatus() == CREATED) {
                throw new IllegalStateException("已建单状态缺少订单，停止自动补偿：" + orderId);
            }
            if (order != null && order.getStatus() == 1) {
                if (orders.closeUnpaid(orderId) != 1) throw new IllegalStateException("关单状态迁移失败：" + orderId);
                if (!vouchers.update().setSql("stock = stock + 1")
                        .eq("voucher_id", reservation.getVoucherId()).update()) {
                    throw new IllegalStateException("数据库库存恢复失败：" + orderId);
                }
                // 保留订单号的取消墓碑，但允许取消后重新预占。
            }
            if (order != null) dedup.deleteById(orderId);
            reservations.markCancelled(orderId, LocalMessageService.truncate(reason));
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) releaseEventually(orderId);
    }

    public void releaseEventually(Long orderId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    // afterCommit 回调时原连接仍绑定在线程上，先挂起它再执行独立的补偿审计。
                    outsideTransactions.execute(tx -> { releaseEventually(orderId); return null; });
                }
            });
            return;
        }
        try { releaseCancelled(orderId); }
        catch (RuntimeException failure) {
            // CANCELLED + redis_released=0 已持久化，定时补偿可以跨进程重启继续执行。
            log.warn("订单已取消，Redis 释放等待重试，orderId={}", orderId, failure);
        }
    }

    public void releaseCancelled(Long orderId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Redis 库存释放必须在数据库事务提交后执行");
        }
        OrderReservation reservation = reservations.selectById(orderId);
        if (reservation == null || reservation.getStatus() != CANCELLED
                || Boolean.TRUE.equals(reservation.getRedisReleased())) return;
        redis.release(orderId, reservation.getVoucherId(), reservation.getUserId());
        reservations.markReleased(orderId);
    }

    private OrderReservation lock(Long orderId) {
        OrderReservation reservation = reservations.lock(orderId);
        if (reservation == null) {
            reservations.adoptOutbox(orderId, timeoutSeconds, paymentTimeoutSeconds);
            reservations.adoptExisting(orderId, timeoutSeconds, paymentTimeoutSeconds);
            reservation = reservations.lock(orderId);
        }
        return reservation;
    }

    private void verifyOwner(OrderReservation reservation, VoucherOrder order) {
        if (reservation == null || !order.getUserId().equals(reservation.getUserId())
                || !order.getVoucherId().equals(reservation.getVoucherId())) {
            throw new IllegalStateException("订单受理记录与消息不匹配：" + order.getId());
        }
    }
}
