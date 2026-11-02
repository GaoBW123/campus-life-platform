package com.campus.life.service;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.campus.life.entity.OrderReservation;
import com.campus.life.entity.SeckillVoucher;
import com.campus.life.entity.VoucherOrder;
import com.campus.life.mapper.OrderDedupMapper;
import com.campus.life.mapper.OrderReservationMapper;
import com.campus.life.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static com.campus.life.entity.OrderReservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderReservationServiceTest {
    private final OrderReservationMapper states = mock(OrderReservationMapper.class);
    private final VoucherOrderMapper orders = mock(VoucherOrderMapper.class);
    private final ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
    private final OrderDedupMapper dedup = mock(OrderDedupMapper.class);
    private final RedisReservationService redis = mock(RedisReservationService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private OrderReservationService service;
    private OrderReservation state;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        state = new OrderReservation();
        state.setOrderId(11L); state.setUserId(22L); state.setVoucherId(33L);
        state.setStatus(PENDING); state.setRedisReleased(false);
        state.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        state.setPaymentExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(states.lock(11L)).thenReturn(state);
        when(states.selectById(11L)).thenReturn(state);
        when(states.markCancelled(eq(11L), anyString())).thenAnswer(call -> { state.setStatus(CANCELLED); return 1; });
        when(states.markCompleted(11L)).thenAnswer(call -> { state.setStatus(COMPLETED); return 1; });
        when(states.markReleased(11L)).thenAnswer(call -> { state.setRedisReleased(true); return 1; });
        service = new OrderReservationService(states, orders, vouchers, dedup, redis, manager, 15, 900);
    }

    @Test
    void cancellationBeforeConsumptionBlocksLateOrderAndReleasesAfterCommit() {
        service.expire(11L);

        assertThat(state.getStatus()).isEqualTo(CANCELLED);
        assertThat(service.prepareCreation(message())).isEqualTo(OrderReservationService.Creation.CANCELLED);
        org.mockito.InOrder sequence = inOrder(manager, redis);
        sequence.verify(manager).commit(any());
        sequence.verify(redis).release(11L, 33L, 22L);
        verifyNoInteractions(vouchers, dedup);
        verify(orders, never()).closeUnpaid(anyLong());
    }

    @Test
    void consumerRejectsExpiredPendingOrderEvenIfTimeoutMessageIsLate() {
        assertThat(service.prepareCreation(message())).isEqualTo(OrderReservationService.Creation.CANCELLED);
        assertThat(state.getStatus()).isEqualTo(CANCELLED);
        verifyNoInteractions(redis, orders, vouchers);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createdOrderIsClosedAndDatabaseStockRestoredOnlyOnce() {
        state.setStatus(CREATED);
        VoucherOrder order = message(); order.setStatus(1);
        when(orders.lockById(11L)).thenReturn(order);
        when(orders.closeUnpaid(11L)).thenReturn(1);
        UpdateChainWrapper<SeckillVoucher> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        when(vouchers.update()).thenReturn(update);
        when(update.update()).thenReturn(true);

        service.expirePayment(11L);
        service.expirePayment(11L);

        verify(orders, times(1)).closeUnpaid(11L);
        verify(update, times(1)).update();
        verify(dedup, times(1)).deleteById(11L);
        verify(redis, times(1)).release(11L, 33L, 22L);
        assertThat(state.getStatus()).isEqualTo(CANCELLED);
    }

    @Test
    void failedRedisReleaseRemainsDurablyPendingAndCanBeRetried() {
        doThrow(new IllegalStateException("redis unavailable")).doNothing()
                .when(redis).release(11L, 33L, 22L);

        service.expire(11L);
        assertThat(state.getStatus()).isEqualTo(CANCELLED);
        assertThat(state.getRedisReleased()).isFalse();
        verify(states, never()).markReleased(11L);

        service.releaseCancelled(11L);
        assertThat(state.getRedisReleased()).isTrue();
        verify(redis, times(2)).release(11L, 33L, 22L);
    }

    @Test
    void paidOrderCannotBeCancelledOrRefundedByTimeout() {
        state.setStatus(CREATED);
        VoucherOrder paid = message(); paid.setStatus(2);
        when(orders.lockById(11L)).thenReturn(paid);

        service.expirePayment(11L);

        assertThat(state.getStatus()).isEqualTo(COMPLETED);
        verify(orders, never()).closeUnpaid(anyLong());
        verifyNoInteractions(redis, vouchers, dedup);
    }

    @Test
    void staleRetryExhaustionCannotRefundAnAlreadyCreatedOrder() {
        state.setStatus(CREATED);
        service.giveUp(11L, "retry exhausted");
        assertThat(state.getStatus()).isEqualTo(CREATED);
        verifyNoInteractions(orders, vouchers, redis);
    }

    @Test
    void earlyOrDuplicateTimeoutCannotExtendOrCancelBeforeDeadline() {
        state.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        service.expire(11L);
        assertThat(state.getStatus()).isEqualTo(PENDING);
        verify(states, never()).markCancelled(anyLong(), anyString());
        verifyNoInteractions(redis, orders);
    }

    @Test
    void inconsistentCreatedStateRequiresReviewInsteadOfBlindRefund() {
        state.setStatus(CREATED);
        assertThatThrownBy(() -> service.expirePayment(11L)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verifyNoInteractions(redis, vouchers);
    }

    @Test
    void lateCreationTimeoutCannotCloseANewlyCreatedOrder() {
        state.setStatus(CREATED);
        service.expire(11L);
        assertThat(state.getStatus()).isEqualTo(CREATED);
        verifyNoInteractions(orders, redis, vouchers);
    }

    @Test
    void paymentGetsItsOwnWindowEvenWhenCreationDeadlineHasPassed() {
        state.setStatus(CREATED);
        state.setPaymentExpiresAt(LocalDateTime.now().plusMinutes(15));
        service.expirePayment(11L);
        assertThat(state.getStatus()).isEqualTo(CREATED);
        verifyNoInteractions(orders, redis, vouchers);
    }

    @Test
    void creationSetsPaymentDeadlineOnlyOnce() {
        when(states.markCreated(11L, 900)).thenReturn(1);
        service.created(11L);
        verify(states).markCreated(11L, 900);
        state.setStatus(CREATED);
        assertThat(service.prepareCreation(message())).isEqualTo(OrderReservationService.Creation.ALREADY_CREATED);
        verify(states, times(1)).markCreated(anyLong(), anyLong());
    }

    @Test
    void paymentTimeoutCannotCancelPendingCreation() {
        service.expirePayment(11L);
        assertThat(state.getStatus()).isEqualTo(PENDING);
        verifyNoInteractions(orders, redis, vouchers);
    }

    @Test
    void missingPaymentDeadlineStopsAutomaticCompensation() {
        state.setStatus(CREATED);
        state.setPaymentExpiresAt(null);
        assertThatThrownBy(() -> service.expirePayment(11L)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verifyNoInteractions(orders, redis, vouchers);
    }

    private VoucherOrder message() {
        VoucherOrder order = new VoucherOrder();
        order.setId(11L); order.setUserId(22L); order.setVoucherId(33L);
        return order;
    }
}
