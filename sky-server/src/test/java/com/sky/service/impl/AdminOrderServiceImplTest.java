package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.vo.OrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminOrderServiceImplTest {
    private AdminOrderServiceImpl service;
    private OrderMapper orders;
    private OrderDetailMapper details;

    @BeforeEach
    void setUp() {
        service = new AdminOrderServiceImpl();
        orders = mock(OrderMapper.class);
        details = mock(OrderDetailMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderDetailMapper", details);
    }

    @Test
    void searchNormalizesAllStatusAndLiteralWildcardsAndIgnoresUserId() {
        OrdersPageQueryDTO query = new OrdersPageQueryDTO();
        query.setNumber("  a_!%  ");
        query.setPhone(" 138 ");
        query.setStatus(0);
        query.setUserId(999L);
        Page<OrderVO> page = new Page<>();
        page.setTotal(20);
        when(orders.searchForAdmin(any())).thenReturn(page);
        var result = service.search(query);
        assertEquals(20, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verify(orders).searchForAdmin(argThat(q -> "a!_!!!%".equals(q.getNumber())
                && "138".equals(q.getPhone()) && q.getStatus() == null && q.getUserId() == null));
        verifyNoInteractions(details);
        assertNull(PageHelper.getLocalPage());
        assertEquals("  a_!%  ", query.getNumber());
    }

    @Test
    void searchRejectsInvalidPaginationStatusLengthAndTimeRange() {
        for (Consumer<OrdersPageQueryDTO> invalid : List.<Consumer<OrdersPageQueryDTO>>of(
                q -> q.setPage(0), q -> q.setPageSize(101), q -> q.setPageSize(0),
                q -> { q.setPage(Integer.MAX_VALUE); q.setPageSize(100); },
                q -> q.setStatus(-1), q -> q.setStatus(7), q -> q.setNumber("a".repeat(51)),
                q -> q.setPhone("1".repeat(12)),
                q -> { q.setBeginTime(LocalDateTime.of(2026, 10, 2, 0, 0));
                    q.setEndTime(LocalDateTime.of(2026, 10, 1, 0, 0)); })) {
            OrdersPageQueryDTO query = new OrdersPageQueryDTO();
            invalid.accept(query);
            assertThrows(OrderBusinessException.class, () -> service.search(query));
        }
        assertThrows(OrderBusinessException.class, () -> service.search(null));
        verifyNoInteractions(orders, details);
    }

    @Test
    void pageDetailsAreFetchedOnceAndMissingDetailsAreEmpty() {
        OrderVO one = new OrderVO(); one.setId(1L);
        OrderVO two = new OrderVO(); two.setId(2L);
        Page<OrderVO> page = new Page<>(); page.addAll(List.of(one, two)); page.setTotal(2);
        OrderDetail detail = new OrderDetail(); detail.setOrderId(1L); detail.setName("菜品"); detail.setNumber(2);
        when(orders.searchForAdmin(any())).thenReturn(page);
        when(details.listByOrderIdsForAdmin(List.of(1L, 2L))).thenReturn(List.of(detail));
        service.search(new OrdersPageQueryDTO());
        assertEquals("菜品×2", one.getOrderDishes());
        assertTrue(two.getOrderDetailList().isEmpty());
        verify(details, times(1)).listByOrderIdsForAdmin(anyList());
    }

    @Test
    void detailRejectsInvalidAndMissingIdsAndUsesAdminDetails() {
        assertThrows(OrderBusinessException.class, () -> service.detail(0L));
        assertThrows(OrderBusinessException.class, () -> service.detail(null));
        assertThrows(OrderBusinessException.class, () -> service.detail(9L));
        OrderVO order = new OrderVO(); order.setId(9L);
        when(orders.getByIdForAdmin(9L)).thenReturn(order);
        when(details.listByOrderIdsForAdmin(List.of(9L))).thenReturn(List.of());
        assertSame(order, service.detail(9L));
        assertTrue(order.getOrderDetailList().isEmpty());
    }

    @Test
    void transitionsAllowOnlyPaidSourceOrPaidTargetAndPreserveCompletionTimeOnRetry() {
        for (int operation = 0; operation < 3; operation++) {
            int from = operation + 2;
            int to = from + 1;
            for (int state = 1; state <= 6; state++) {
                for (int pay = 0; pay <= 2; pay++) {
                    reset(orders);
                    when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().id(9L).status(state).payStatus(pay).build());
                    when(orders.advancePaidStatus(anyLong(), anyInt(), anyInt(), nullable(LocalDateTime.class))).thenReturn(1);
                    final int action = operation;
                    if (pay == 1 && (state == from || state == to)) {
                        invoke(action);
                        if (state == to) {
                            verify(orders, never()).advancePaidStatus(anyLong(), anyInt(), anyInt(), nullable(LocalDateTime.class));
                        } else if (to == 5) {
                            verify(orders).advancePaidStatus(eq(9L), eq(from), eq(to), argThat(t -> t != null && t.getNano() == 0));
                        } else {
                            verify(orders).advancePaidStatus(9L, from, to, null);
                        }
                    } else {
                        assertThrows(OrderBusinessException.class, () -> invoke(action));
                        verify(orders, never()).advancePaidStatus(anyLong(), anyInt(), anyInt(), nullable(LocalDateTime.class));
                    }
                }
            }
        }
    }

    private void invoke(int action) {
        if (action == 0) { service.confirm(9L); }
        else if (action == 1) { service.delivery(9L); }
        else { service.complete(9L); }
    }

    @Test
    void failedConditionalUpdateAndMissingOrderAreReported() {
        assertThrows(OrderBusinessException.class, () -> service.confirm(9L));
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(2).payStatus(1).build());
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.confirm(9L)).getMessage().contains("已变化"));
        assertThrows(OrderBusinessException.class, () -> service.delivery(-1L));
        assertThrows(OrderBusinessException.class, () -> service.complete(null));
    }

    @Test
    void cancellationOnlyMutatesUnpaidPendingOrderAndTrimsReason() {
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(1).payStatus(0).build());
        when(orders.cancelUnpaidForAdmin(eq(9L), anyString(), any())).thenReturn(1);
        service.cancel(9L, " 售罄 ");
        verify(orders).cancelUnpaidForAdmin(eq(9L), eq("售罄"), argThat(t -> t.getNano() == 0));
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(6).payStatus(0).build());
        service.cancel(9L, "另外的原因");
        verify(orders, times(1)).cancelUnpaidForAdmin(anyLong(), anyString(), any());
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(2).payStatus(1).build());
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "售罄")).getMessage().contains("退款"));
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(3).payStatus(0).build());
        assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "售罄"));
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(6).payStatus(2).build());
        assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "售罄"));
    }

    @Test
    void cancellationConditionalFailureAndInvalidReasonsDoNotSucceed() {
        for (String reason : new String[]{null, " ", "a".repeat(256)}) {
            assertThrows(OrderBusinessException.class, () -> service.cancel(9L, reason));
            assertThrows(OrderBusinessException.class, () -> service.reject(9L, reason));
        }
        verifyNoInteractions(orders);
        assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "售罄"));
        when(orders.getByIdForUpdate(9L)).thenReturn(Orders.builder().status(1).payStatus(0).build());
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "售罄")).getMessage().contains("已变化"));
    }

    @Test
    void rejectionNeverWritesOrPretendsRefundSucceeded() {
        assertThrows(OrderBusinessException.class, () -> service.reject(0L, "售罄"));
        assertThrows(OrderBusinessException.class, () -> service.reject(9L, "售罄"));
        OrderVO order = new OrderVO(); order.setId(9L); order.setStatus(1); order.setPayStatus(0);
        when(orders.getByIdForAdmin(9L)).thenReturn(order);
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.reject(9L, "售罄")).getMessage().contains("待接单"));
        order.setStatus(2); order.setPayStatus(1);
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.reject(9L, "售罄")).getMessage().contains("暂未接入退款"));
        verify(orders, never()).cancelUnpaidForAdmin(anyLong(), anyString(), any());
        verify(orders, never()).advancePaidStatus(anyLong(), anyInt(), anyInt(), any());
        verifyNoInteractions(details);
    }
}
