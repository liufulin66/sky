package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.result.PageResult;
import com.sky.service.AdminOrderService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderDetailMapper orderDetailMapper;

    @Override
    public PageResult<OrderVO> search(OrdersPageQueryDTO input) {
        OrdersPageQueryDTO query = normalize(input);
        // 分页只作用于主表查询，随后按本页id批量查明细，避免逐单查询。
        Page<OrderVO> orders;
        try {
            PageHelper.startPage(query.getPage(), query.getPageSize());
            orders = orderMapper.searchForAdmin(query);
        } finally {
            PageHelper.clearPage();
        }
        List<OrderVO> records = orders.getResult();
        attachDetails(records);
        return new PageResult<>(orders.getTotal(), records);
    }

    @Override
    public OrderVO detail(Long id) {
        requireId(id);
        OrderVO order = orderMapper.getByIdForAdmin(id);
        if (order == null) { throw error("订单不存在"); }
        attachDetails(List.of(order));
        return order;
    }

    @Override
    public OrderStatisticsVO statistics() {
        return orderMapper.statisticsForAdmin();
    }

    @Override
    @Transactional
    public void confirm(Long id) {
        advance(id, Orders.TO_BE_CONFIRMED, Orders.CONFIRMED, "仅支持接单待接单且已支付的订单");
    }

    @Override
    @Transactional
    public void delivery(Long id) {
        advance(id, Orders.CONFIRMED, Orders.DELIVERY_IN_PROGRESS, "仅支持派送已接单且已支付的订单");
    }

    @Override
    @Transactional
    public void complete(Long id) {
        advance(id, Orders.DELIVERY_IN_PROGRESS, Orders.COMPLETED, "仅支持完成派送中且已支付的订单");
    }

    private void advance(Long id, Integer from, Integer to, String invalidStateMessage) {
        Orders order = lockedOrder(id);
        if (!Orders.PAID.equals(order.getPayStatus())) { throw error(invalidStateMessage); }
        // 仅已到本次目标状态的请求视作成功；已走到更后面的状态也不允许倒退。
        if (to.equals(order.getStatus())) { return; }
        if (!from.equals(order.getStatus())) { throw error(invalidStateMessage); }
        LocalDateTime deliveredAt = Orders.COMPLETED.equals(to) ? LocalDateTime.now().withNano(0) : null;
        if (orderMapper.advancePaidStatus(id, from, to, deliveredAt) != 1) {
            throw error("订单状态已变化，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public void cancel(Long id, String reason) {
        requireId(id);
        String text = requiredReason(reason, "取消原因");
        Orders order = lockedOrder(id);
        if (Orders.PAID.equals(order.getPayStatus())) {
            throw error("暂未接入退款，无法取消已支付订单");
        }
        if (Orders.CANCELLED.equals(order.getStatus()) && Orders.UN_PAID.equals(order.getPayStatus())) {
            return; // 保留第一次取消的原因和时间，包括用户已经取消的订单。
        }
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus())) {
            throw error("仅支持取消待付款且未支付的订单");
        }
        if (orderMapper.cancelUnpaidForAdmin(id, text, LocalDateTime.now().withNano(0)) != 1) {
            throw error("订单状态已变化，请刷新后重试");
        }
    }

    @Override
    public void reject(Long id, String reason) {
        requireId(id);
        requiredReason(reason, "拒单原因");
        Orders order = orderMapper.getByIdForAdmin(id);
        if (order == null) { throw error("订单不存在"); }
        if (!Orders.TO_BE_CONFIRMED.equals(order.getStatus()) || !Orders.PAID.equals(order.getPayStatus())) {
            throw error("仅支持拒绝待接单且已支付的订单");
        }
        // 当前拒单接口明确返回业务失败，不保存拒绝原因、不更改状态、不伪造退款成功。
        throw error("暂未接入退款，无法拒单");
    }

    private Orders lockedOrder(Long id) {
        requireId(id);
        Orders order = orderMapper.getByIdForUpdate(id);
        if (order == null) { throw error("订单不存在"); }
        return order;
    }

    private OrdersPageQueryDTO normalize(OrdersPageQueryDTO input) {
        if (input == null) { throw error("查询条件不能为空"); }
        if (input.getPage() <= 0 || input.getPageSize() <= 0 || input.getPageSize() > 100
                || (long) (input.getPage() - 1) * input.getPageSize() > Integer.MAX_VALUE) {
            throw error("page必须为正整数且偏移量不能超限，pageSize必须在1到100之间");
        }
        Integer status = input.getStatus();
        if (status != null && status == 0) { status = null; } // 兼容管理端“全部”标签。
        if (status != null && (status < Orders.PENDING_PAYMENT || status > Orders.CANCELLED)) {
            throw error("订单状态必须在1到6之间，0表示全部");
        }
        if (input.getBeginTime() != null && input.getEndTime() != null
                && input.getBeginTime().isAfter(input.getEndTime())) {
            throw error("开始时间不能晚于结束时间");
        }
        OrdersPageQueryDTO query = new OrdersPageQueryDTO();
        query.setPage(input.getPage());
        query.setPageSize(input.getPageSize());
        query.setNumber(likeText(input.getNumber(), "订单号", 50));
        query.setPhone(likeText(input.getPhone(), "手机号", 11));
        query.setStatus(status);
        query.setBeginTime(input.getBeginTime());
        query.setEndTime(input.getEndTime());
        // 商家查询整个店铺，不能将客户端userId或管理员员工id误作为下单用户过滤条件。
        return query;
    }

    private void attachDetails(List<OrderVO> orders) {
        if (orders.isEmpty()) { return; }
        List<Long> ids = orders.stream().map(Orders::getId).toList();
        Map<Long, List<OrderDetail>> grouped = orderDetailMapper.listByOrderIdsForAdmin(ids).stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));
        for (OrderVO order : orders) {
            List<OrderDetail> details = grouped.getOrDefault(order.getId(), List.of());
            order.setOrderDetailList(details);
            order.setOrderDishes(details.stream().map(d -> d.getName() + "×" + d.getNumber())
                    .collect(Collectors.joining("；")));
        }
    }

    private String likeText(String value, String field, int maxLength) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) { return null; }
        if (text.length() > maxLength) { throw error(field + "内容过长"); }
        // 用户输入的%、_、!按普通字符匹配，SQL参数绑定同时防止注入。
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String requiredReason(String reason, String field) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > 255) { throw error(field + "必须为1到255个字符"); }
        return text;
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) { throw error("订单id必须为正整数"); }
    }

    private OrderBusinessException error(String message) { return new OrderBusinessException(message); }
}
