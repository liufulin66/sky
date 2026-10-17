package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.Dish;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.*;
import com.sky.properties.OrderProperties;
import com.sky.service.ShopService;
import com.sky.service.ShoppingCartService;
import com.sky.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceImplTest {
    private OrderServiceImpl service;
    private OrderMapper orders;
    private OrderDetailMapper details;
    private ShoppingCartMapper carts;
    private AddressBookMapper addresses;
    private DishMapper dishes;
    private SetmealMapper setmeals;
    private UserMapper users;
    private ShopService shop;
    private OrderProperties fees;
    private Orders inserted;
    private OrderRepetitionMapper repetitions;
    private ShoppingCartService shoppingCartService;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl();
        orders = mock(OrderMapper.class);
        details = mock(OrderDetailMapper.class);
        carts = mock(ShoppingCartMapper.class);
        addresses = mock(AddressBookMapper.class);
        dishes = mock(DishMapper.class);
        setmeals = mock(SetmealMapper.class);
        users = mock(UserMapper.class);
        shop = mock(ShopService.class);
        fees = new OrderProperties();
        repetitions = mock(OrderRepetitionMapper.class);
        shoppingCartService = mock(ShoppingCartService.class);
        when(repetitions.getOrderIdByRequestId(anyLong(), anyString())).thenReturn(null);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderDetailMapper", details);
        ReflectionTestUtils.setField(service, "shoppingCartMapper", carts);
        ReflectionTestUtils.setField(service, "addressBookMapper", addresses);
        ReflectionTestUtils.setField(service, "dishMapper", dishes);
        ReflectionTestUtils.setField(service, "setmealMapper", setmeals);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "shopService", shop);
        ReflectionTestUtils.setField(service, "orderProperties", fees);
        ReflectionTestUtils.setField(service, "orderRepetitionMapper", repetitions);
        ReflectionTestUtils.setField(service, "shoppingCartService", shoppingCartService);
        BaseContext.setCurrentId(5L);
        when(users.lockById(5L)).thenReturn(5L);
        when(addresses.getByIdAndUserId(1L, 5L)).thenReturn(AddressBook.builder().id(1L).userId(5L)
                .consignee("张三").phone("13800138000").provinceName("北京市").detail("示例路1号").build());
        when(carts.listByUserId(5L)).thenReturn(List.of(ShoppingCart.builder().dishId(10L)
                .number(2).amount(new BigDecimal("1.00")).name("旧名称").dishFlavor("微辣").build()));
        when(dishes.getByIdForUser(10L)).thenReturn(Dish.builder().id(10L).name("当前菜名")
                .price(new BigDecimal("25.00")).image("new-image").build());
        doAnswer(invocation -> {
            inserted = invocation.getArgument(0);
            inserted.setId(9L);
            return null;
        }).when(orders).insert(any());
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        PageHelper.clearPage();
    }

    private OrdersSubmitDTO form() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setRequestId("test-request");
        dto.setAddressBookId(1L);
        dto.setPayMethod(1);
        dto.setAmount(new BigDecimal("50.00"));
        return dto;
    }

    private void noOrderWritten() {
        verify(orders, never()).insert(any());
        verify(details, never()).insertBatch(anyList());
        verify(carts, never()).deleteByUserId(anyLong());
    }

    @Test
    void submitRepricesFromDatabaseSnapshotsAddressAndClearsCartAfterDetails() {
        var result = service.submit(form());
        assertEquals(9L, result.getId());
        assertEquals(new BigDecimal("50.00"), result.getOrderAmount());
        assertEquals(Orders.PENDING_PAYMENT, inserted.getStatus());
        assertEquals(Orders.UN_PAID, inserted.getPayStatus());
        assertEquals(5L, inserted.getUserId());
        assertEquals("北京市示例路1号", inserted.getAddress());
        assertEquals(64, inserted.getRequestHash().length());
        assertEquals(0, inserted.getOrderTime().getNano());
        verify(details).insertBatch(argThat(list -> list.size() == 1 && list.get(0).getOrderId().equals(9L)
                && list.get(0).getAmount().compareTo(new BigDecimal("25")) == 0
                && "当前菜名".equals(list.get(0).getName()) && list.get(0).getNumber() == 2));
        var sequence = inOrder(users, orders, carts, details);
        sequence.verify(users).lockById(5L);
        sequence.verify(orders).getByRequestId(5L, "test-request");
        sequence.verify(carts).listByUserId(5L);
        sequence.verify(orders).insert(any());
        sequence.verify(details).insertBatch(anyList());
        sequence.verify(carts).deleteByUserId(5L);
    }

    @Test
    void changedPriceRejectsWithoutWritingOrClearingCart() {
        OrdersSubmitDTO dto = form();
        dto.setAmount(new BigDecimal("2.00"));
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.submit(dto)).getMessage().contains("刷新"));
        noOrderWritten();
    }

    @Test
    void configuredFeesAreCalculatedAndStoredByServer() {
        fees.setPackAmount(1);
        fees.setDeliveryAmount(new BigDecimal("2.50"));
        OrdersSubmitDTO dto = form();
        dto.setAmount(new BigDecimal("53.50"));
        dto.setPackAmount(1);
        service.submit(dto);
        assertEquals(new BigDecimal("53.50"), inserted.getAmount());
        assertEquals(1, inserted.getPackAmount());
        assertEquals(new BigDecimal("2.50"), inserted.getDeliveryAmount());
    }

    @Test
    void replayReturnsOriginalOrderWithoutRecheckingShopAddressPriceOrNewCart() {
        var first = service.submit(form());
        when(orders.getByRequestId(5L, "test-request")).thenReturn(inserted);
        clearInvocations(shop, addresses, carts, dishes, orders, details);
        var retry = service.submit(form());
        assertEquals(first, retry);
        verify(orders, never()).insert(any());
        verifyNoInteractions(shop, addresses, carts, dishes, details);
    }

    @Test
    void reusingKeyWithDifferentFormIsRejected() {
        service.submit(form());
        when(orders.getByRequestId(5L, "test-request")).thenReturn(inserted);
        OrdersSubmitDTO changed = form();
        changed.setRemark("新备注");
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.submit(changed)).getMessage().contains("requestId"));
        verify(orders, times(1)).insert(any());
    }

    @Test
    void unavailableDishOrSetmealAndInvalidCartCannotGenerateOrders() {
        when(dishes.getByIdForUser(10L)).thenReturn(null);
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        when(carts.listByUserId(5L)).thenReturn(List.of(ShoppingCart.builder().setmealId(20L).number(1).build()));
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        when(carts.listByUserId(5L)).thenReturn(List.of(ShoppingCart.builder().dishId(10L).number(0).build()));
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        noOrderWritten();
    }

    @Test
    void missingAddressAndEmptyCartRejectWithoutWrites() {
        when(addresses.getByIdAndUserId(1L, 5L)).thenReturn(null);
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        noOrderWritten();
    }

    @Test
    void emptyCartCannotGenerateEmptyOrder() {
        when(carts.listByUserId(5L)).thenReturn(List.of());
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        noOrderWritten();
    }

    @Test
    void malformedFieldsAreRejectedBeforeLockingOrQuerying() {
        List<Consumer<OrdersSubmitDTO>> invalid = List.of(d -> d.setRequestId(null), d -> d.setRequestId("a b"),
                d -> d.setAddressBookId(0L), d -> d.setPayMethod(null), d -> d.setPayMethod(3),
                d -> d.setAmount(null), d -> d.setAmount(new BigDecimal("-1")),
                d -> d.setAmount(new BigDecimal("1.001")), d -> d.setAmount(new BigDecimal("100000000")),
                d -> d.setRemark("x".repeat(101)), d -> d.setPackAmount(-1), d -> d.setDeliveryStatus(2),
                d -> d.setDeliveryStatus(0), d -> d.setTablewareStatus(2), d -> d.setTablewareNumber(-1),
                d -> d.setTablewareStatus(0));
        for (var change : invalid) {
            OrdersSubmitDTO dto = form();
            change.accept(dto);
            assertThrows(OrderBusinessException.class, () -> service.submit(dto));
        }
        verifyNoInteractions(users, orders, carts, addresses, dishes);
    }

    @Test
    void expiredReservationAndClosedShopCannotSubmit() {
        OrdersSubmitDTO dto = form();
        dto.setDeliveryStatus(0);
        dto.setEstimatedDeliveryTime(LocalDateTime.now().minusDays(1).withNano(0));
        assertThrows(OrderBusinessException.class, () -> service.submit(dto));
        doThrow(new OrderBusinessException("店铺已打烊")).when(shop).checkOpenForNewOrder();
        assertThrows(OrderBusinessException.class, () -> service.submit(form()));
        noOrderWritten();
    }

    @Test
    void historyQueriesOwnOrdersAndBatchesDetails() {
        Page<OrderVO> page = new Page<>(1, 10);
        OrderVO order = new OrderVO();
        order.setId(9L);
        page.add(order);
        page.setTotal(1);
        when(orders.pageByUserId(5L, 1)).thenReturn(page);
        when(details.listByOrderIdsAndUserId(List.of(9L), 5L)).thenReturn(List.of(
                OrderDetail.builder().orderId(9L).name("菜品").number(2).build()));
        var result = service.historyOrders(1, 10, 1);
        assertEquals(1, result.getTotal());
        assertEquals("菜品×2", result.getRecords().get(0).getOrderDishes());
        verify(details).listByOrderIdsAndUserId(List.of(9L), 5L);
    }

    @Test
    void invalidPaginationAndForeignOrderDetailsAreRejected() {
        assertThrows(OrderBusinessException.class, () -> service.historyOrders(0, 10, null));
        assertThrows(OrderBusinessException.class, () -> service.historyOrders(1, 101, null));
        assertThrows(OrderBusinessException.class, () -> service.historyOrders(1, 10, 7));
        assertThrows(OrderBusinessException.class, () -> service.orderDetail(0L));
        assertThrows(OrderBusinessException.class, () -> service.orderDetail(99L));
        verify(details, never()).listByOrderIdsAndUserId(anyList(), anyLong());
    }

    @Test
    void missingIdentityAndDeletedUserCannotWrite() {
        BaseContext.removeCurrentId();
        assertThrows(UserNotLoginException.class, () -> service.submit(form()));
        BaseContext.setCurrentId(999L);
        when(users.lockById(999L)).thenReturn(null);
        assertThrows(UserNotLoginException.class, () -> service.submit(form()));
        noOrderWritten();
    }

    @Test
    void cancelUsesDefaultReasonAndConditionalUpdateAfterLocking() {
        when(orders.getByIdAndUserIdForUpdate(9L, 5L)).thenReturn(Orders.builder().id(9L).status(1).payStatus(0).build());
        when(orders.cancelUnpaid(eq(9L), eq(5L), anyString(), any())).thenReturn(1);
        service.cancel(9L, "   ");
        var sequence = inOrder(users, orders);
        sequence.verify(users).lockById(5L);
        sequence.verify(orders).getByIdAndUserIdForUpdate(9L, 5L);
        sequence.verify(orders).cancelUnpaid(eq(9L), eq(5L), eq("用户主动取消"), argThat(t -> t.getNano() == 0));
        verifyNoInteractions(carts, details, shop);
    }

    @Test
    void cancelledUnpaidOrderIsIdempotentAndPaidOrdersAreRejected() {
        when(orders.getByIdAndUserIdForUpdate(9L, 5L)).thenReturn(Orders.builder().id(9L).status(6).payStatus(0).build());
        service.cancel(9L, "第二次原因");
        verify(orders, never()).cancelUnpaid(anyLong(), anyLong(), anyString(), any());
        for (int status : new int[]{1, 2, 3, 4, 5, 6}) {
            when(orders.getByIdAndUserIdForUpdate(9L, 5L)).thenReturn(Orders.builder().id(9L).status(status).payStatus(1).build());
            assertThrows(OrderBusinessException.class, () -> service.cancel(9L, null));
        }
    }

    @Test
    void cancelRejectsForeignOrdersInvalidReasonsAndConcurrentStateChanges() {
        assertThrows(OrderBusinessException.class, () -> service.cancel(99L, null));
        assertThrows(OrderBusinessException.class, () -> service.cancel(9L, "x".repeat(256)));
        when(orders.getByIdAndUserIdForUpdate(9L, 5L)).thenReturn(Orders.builder().id(9L).status(1).payStatus(0).build());
        when(orders.cancelUnpaid(eq(9L), eq(5L), anyString(), any())).thenReturn(0);
        assertThrows(OrderBusinessException.class, () -> service.cancel(9L, null));
    }

    @Test
    void repetitionReusesQuantityAwareCartServiceAndRecordsOnlyAfterSuccess() {
        OrderVO order = new OrderVO();
        order.setId(9L);
        order.setStatus(1); // 任意状态的本人订单均可再来一单。
        when(orders.getByIdAndUserId(9L, 5L)).thenReturn(order);
        when(details.listByOrderIdsAndUserId(List.of(9L), 5L)).thenReturn(List.of(
                OrderDetail.builder().dishId(10L).dishFlavor("微辣").number(3).name("历史菜名").build()));
        service.repetition(9L, "repeat-1");
        var sequence = inOrder(users, repetitions, orders, shoppingCartService);
        sequence.verify(users).lockById(5L);
        sequence.verify(repetitions).getOrderIdByRequestId(5L, "repeat-1");
        sequence.verify(orders).getByIdAndUserId(9L, 5L);
        sequence.verify(shoppingCartService).add(argThat(d -> d.getDishId().equals(10L) && "微辣".equals(d.getDishFlavor())), eq(3));
        sequence.verify(repetitions).insert(eq(5L), eq("repeat-1"), eq(9L), any());
        verifyNoInteractions(shop);
        verify(orders, never()).insert(any());
    }

    @Test
    void repetitionReplayDoesNotReadOrderOrModifyCartAndOtherSourceIsRejected() {
        when(repetitions.getOrderIdByRequestId(5L, "repeat-1")).thenReturn(9L);
        service.repetition(9L, "repeat-1");
        assertThrows(OrderBusinessException.class, () -> service.repetition(10L, "repeat-1"));
        verifyNoInteractions(shoppingCartService, orders, details, shop);
        verify(repetitions, never()).insert(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void repetitionRejectsMissingForeignAndBrokenOrdersWithoutSuccessRecord() {
        assertThrows(OrderBusinessException.class, () -> service.repetition(99L, "repeat-1"));
        when(orders.getByIdAndUserId(9L, 5L)).thenReturn(new OrderVO());
        when(details.listByOrderIdsAndUserId(List.of(9L), 5L)).thenReturn(List.of());
        assertThrows(OrderBusinessException.class, () -> service.repetition(9L, "repeat-1"));
        when(details.listByOrderIdsAndUserId(List.of(9L), 5L)).thenReturn(List.of(OrderDetail.builder().number(0).build()));
        assertThrows(OrderBusinessException.class, () -> service.repetition(9L, "repeat-1"));
        when(details.listByOrderIdsAndUserId(List.of(9L), 5L)).thenReturn(List.of(
                OrderDetail.builder().dishId(10L).name("已停售的菜").number(2).build()));
        doThrow(new ShoppingCartBusinessException("菜品不存在或已不可售")).when(shoppingCartService).add(any(), eq(2));
        assertTrue(assertThrows(OrderBusinessException.class, () -> service.repetition(9L, "repeat-1"))
                .getMessage().contains("已停售的菜"));
        verify(repetitions, never()).insert(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void cancellationAndRepetitionRejectInvalidIdentityIdsAndRequestKeys() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(OrderBusinessException.class, () -> service.cancel(id, null));
            assertThrows(OrderBusinessException.class, () -> service.repetition(id, "repeat-1"));
        }
        for (String key : new String[]{null, "", "a b", "x".repeat(65)}) {
            assertThrows(OrderBusinessException.class, () -> service.repetition(9L, key));
        }
        verifyNoInteractions(users, orders, repetitions);
        BaseContext.removeCurrentId();
        assertThrows(UserNotLoginException.class, () -> service.cancel(9L, null));
        assertThrows(UserNotLoginException.class, () -> service.repetition(9L, "repeat-1"));
    }
}
