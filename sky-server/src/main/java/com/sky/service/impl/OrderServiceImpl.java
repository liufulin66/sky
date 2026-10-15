package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.Dish;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.properties.OrderProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.ShopService;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderDetailMapper orderDetailMapper;
    @Autowired private ShoppingCartMapper shoppingCartMapper;
    @Autowired private AddressBookMapper addressBookMapper;
    @Autowired private DishMapper dishMapper;
    @Autowired private SetmealMapper setmealMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ShopService shopService;
    @Autowired private OrderProperties orderProperties;

    @Override
    @Transactional
    public OrderSubmitVO submit(OrdersSubmitDTO dto) {
        Long userId = currentUserId();
        OrdersSubmitDTO input = normalize(dto);
        String hash = requestHash(input);
        // 与购物车、地址簿共用锁；必须在首次普通查询之前获取，防止并发重试读取旧快照。
        if (userMapper.lockById(userId) == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        Orders existing = orderMapper.getByRequestId(userId, input.getRequestId());
        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) {
                throw error("requestId已用于不同的下单内容，请生成新的requestId");
            }
            // 重试不再检查购物车、当前价格或营业状态，也不清理后来添加的商品。
            return submitResult(existing);
        }

        shopService.checkOpenForNewOrder();
        if (input.getDeliveryStatus() == 0 && !input.getEstimatedDeliveryTime().isAfter(LocalDateTime.now())) {
            throw error("预约送达时间必须晚于当前时间");
        }
        AddressBook address = addressBookMapper.getByIdAndUserId(input.getAddressBookId(), userId);
        if (address == null) {
            throw error("收货地址不存在或无权使用");
        }
        List<ShoppingCart> carts = shoppingCartMapper.listByUserId(userId);
        if (carts.isEmpty()) {
            throw error(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        List<OrderDetail> details = buildDetails(carts);
        BigDecimal goodsAmount = details.stream()
                .map(d -> d.getAmount().multiply(BigDecimal.valueOf(d.getNumber())))
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        Integer packAmount = orderProperties.getPackAmount();
        if (packAmount == null || packAmount < 0) {
            throw error("打包费配置异常，暂时无法下单");
        }
        BigDecimal deliveryAmount = money(orderProperties.getDeliveryAmount(), "配送费配置");
        BigDecimal total = money(goodsAmount.add(BigDecimal.valueOf(packAmount)).add(deliveryAmount), "订单金额");
        if (input.getAmount().compareTo(total) != 0
                || (input.getPackAmount() != null && !input.getPackAmount().equals(packAmount))) {
            throw error("商品价格或费用已变化，请刷新结算页后确认");
        }

        Orders order = Orders.builder()
                .number(UUID.randomUUID().toString().replace("-", ""))
                .requestId(input.getRequestId()).requestHash(hash)
                .status(Orders.PENDING_PAYMENT).payStatus(Orders.UN_PAID)
                .userId(userId).addressBookId(address.getId()).orderTime(LocalDateTime.now().withNano(0))
                .payMethod(input.getPayMethod()).amount(total).remark(input.getRemark())
                .phone(requiredText(address.getPhone(), "收货手机号", 11))
                .consignee(requiredText(address.getConsignee(), "收货人", 50))
                .address(fullAddress(address))
                .estimatedDeliveryTime(input.getEstimatedDeliveryTime()).deliveryStatus(input.getDeliveryStatus())
                .packAmount(packAmount).deliveryAmount(deliveryAmount)
                .tablewareNumber(input.getTablewareNumber()).tablewareStatus(input.getTablewareStatus())
                .build();
        orderMapper.insert(order); // 自增订单id回填后才能关联明细。
        for (OrderDetail detail : details) {
            detail.setOrderId(order.getId());
        }
        orderDetailMapper.insertBatch(details);
        shoppingCartMapper.deleteByUserId(userId);
        return submitResult(order);
    }

    @Override
    public PageResult<OrderVO> historyOrders(Integer page, Integer pageSize, Integer status) {
        Long userId = currentUserId();
        if (page == null || page <= 0 || pageSize == null || pageSize <= 0 || pageSize > 100) {
            throw error("page必须为正整数，pageSize必须在1到100之间");
        }
        if (status != null && (status < Orders.PENDING_PAYMENT || status > Orders.CANCELLED)) {
            throw error("订单状态必须在1到6之间");
        }
        // PageHelper只作用于紧接着的订单查询，避免把明细查询意外分页。
        PageHelper.startPage(page, pageSize);
        Page<OrderVO> orders = orderMapper.pageByUserId(userId, status);
        List<OrderVO> records = orders.getResult();
        attachDetails(records, userId);
        return new PageResult<>(orders.getTotal(), records);
    }

    @Override
    public OrderVO orderDetail(Long id) {
        Long userId = currentUserId();
        if (id == null || id <= 0) {
            throw error("订单id必须为正整数");
        }
        OrderVO order = orderMapper.getByIdAndUserId(id, userId);
        if (order == null) {
            throw error("订单不存在或无权查看");
        }
        attachDetails(List.of(order), userId);
        return order;
    }

    private void attachDetails(List<OrderVO> orders, Long userId) {
        if (orders.isEmpty()) {
            return;
        }
        List<Long> ids = orders.stream().map(Orders::getId).toList();
        Map<Long, List<OrderDetail>> grouped = orderDetailMapper.listByOrderIdsAndUserId(ids, userId).stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));
        for (OrderVO order : orders) {
            List<OrderDetail> details = grouped.getOrDefault(order.getId(), List.of());
            order.setOrderDetailList(details);
            order.setOrderDishes(details.stream().map(d -> d.getName() + "×" + d.getNumber())
                    .collect(Collectors.joining("；")));
        }
    }

    private List<OrderDetail> buildDetails(List<ShoppingCart> carts) {
        List<OrderDetail> details = new ArrayList<>();
        Map<Long, Dish> dishes = new HashMap<>();
        Map<Long, Setmeal> setmeals = new HashMap<>();
        for (ShoppingCart cart : carts) {
            if ((cart.getDishId() == null) == (cart.getSetmealId() == null)
                    || cart.getNumber() == null || cart.getNumber() <= 0) {
                throw error("购物车商品或数量异常，请重新选择商品");
            }
            OrderDetail detail = new OrderDetail();
            detail.setDishId(cart.getDishId());
            detail.setSetmealId(cart.getSetmealId());
            detail.setNumber(cart.getNumber());
            detail.setDishFlavor(optionalText(cart.getDishFlavor(), "口味", 50));
            if (cart.getDishId() != null) {
                // 只调用Mapper，绕过商品浏览的Redis缓存。不同口味复用本次查询结果。
                Dish dish = dishes.computeIfAbsent(cart.getDishId(), dishMapper::getByIdForUser);
                if (dish == null) {
                    throw error("购物车中有菜品已下架或不可售，请重新选择");
                }
                detail.setName(dish.getName());
                detail.setImage(dish.getImage());
                detail.setAmount(money(dish.getPrice(), "菜品价格"));
            } else {
                if (detail.getDishFlavor() != null) {
                    throw error("套餐购物车口味异常，请重新选择商品");
                }
                Setmeal setmeal = setmeals.computeIfAbsent(cart.getSetmealId(), setmealMapper::getByIdForUser);
                if (setmeal == null) {
                    throw error("购物车中有套餐已下架或不可售，请重新选择");
                }
                detail.setName(setmeal.getName());
                detail.setImage(setmeal.getImage());
                detail.setAmount(money(setmeal.getPrice(), "套餐价格"));
            }
            details.add(detail);
        }
        return details;
    }

    private OrdersSubmitDTO normalize(OrdersSubmitDTO dto) {
        if (dto == null) {
            throw error("下单信息不能为空");
        }
        OrdersSubmitDTO input = new OrdersSubmitDTO();
        String requestId = dto.getRequestId();
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw error("requestId必须为1到64位字母、数字、下划线或短横线");
        }
        if (dto.getAddressBookId() == null || dto.getAddressBookId() <= 0) {
            throw error("收货地址id必须为正整数");
        }
        if (!Integer.valueOf(1).equals(dto.getPayMethod()) && !Integer.valueOf(2).equals(dto.getPayMethod())) {
            throw error("支付方式只能为1（微信）或2（支付宝）");
        }
        input.setRequestId(requestId);
        input.setAddressBookId(dto.getAddressBookId());
        input.setPayMethod(dto.getPayMethod());
        input.setRemark(optionalText(dto.getRemark(), "备注", 100));
        int deliveryStatus = flag(dto.getDeliveryStatus(), "配送状态");
        input.setDeliveryStatus(deliveryStatus);
        if (deliveryStatus == 0 && dto.getEstimatedDeliveryTime() == null) {
            throw error("预约配送必须填写预计送达时间");
        }
        if (dto.getEstimatedDeliveryTime() != null && dto.getEstimatedDeliveryTime().getNano() != 0) {
            throw error("预计送达时间精确到秒");
        }
        input.setEstimatedDeliveryTime(deliveryStatus == 0 ? dto.getEstimatedDeliveryTime() : null);
        int tablewareStatus = flag(dto.getTablewareStatus(), "餐具状态");
        input.setTablewareStatus(tablewareStatus);
        if (dto.getTablewareNumber() != null && dto.getTablewareNumber() < 0) {
            throw error("餐具数量不能为负数");
        }
        if (tablewareStatus == 0 && dto.getTablewareNumber() == null) {
            throw error("手动选择餐具时必须填写餐具数量");
        }
        // 按餐量提供时数量记0，由厨房根据订单内容提供；不擅自把套餐份数换算为人数。
        input.setTablewareNumber(tablewareStatus == 1 ? 0 : dto.getTablewareNumber());
        if (dto.getPackAmount() != null && dto.getPackAmount() < 0) {
            throw error("打包费不能为负数");
        }
        input.setPackAmount(dto.getPackAmount());
        input.setAmount(money(dto.getAmount(), "客户端订单金额"));
        return input;
    }

    private int flag(Integer value, String name) {
        if (value != null && value != 0 && value != 1) {
            throw error(name + "只能为0或1");
        }
        return value == null ? 1 : value;
    }

    private String requestHash(OrdersSubmitDTO input) {
        String[] fields = {String.valueOf(input.getAddressBookId()), String.valueOf(input.getPayMethod()),
                input.getRemark(), Objects.toString(input.getEstimatedDeliveryTime(), null),
                String.valueOf(input.getDeliveryStatus()), String.valueOf(input.getTablewareStatus()),
                String.valueOf(input.getTablewareNumber()), Objects.toString(input.getPackAmount(), null),
                input.getAmount().toPlainString()};
        StringBuilder canonical = new StringBuilder();
        // 长度前缀区分字段边界及null，避免备注中的分隔符造成不同请求生成相同原文。
        for (String field : fields) {
            canonical.append(field == null ? -1 : field.length()).append(':');
            if (field != null) { canonical.append(field); }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK缺少SHA-256算法", ex);
        }
    }

    private String fullAddress(AddressBook address) {
        requiredText(address.getDetail(), "详细地址", 200);
        String full = Arrays.stream(new String[]{address.getProvinceName(), address.getCityName(),
                        address.getDistrictName(), address.getDetail()})
                .filter(Objects::nonNull).map(String::trim).collect(Collectors.joining());
        return requiredText(full, "完整地址", 512);
    }

    private String requiredText(String value, String field, int maxLength) {
        String text = optionalText(value, field, maxLength);
        if (text == null) { throw error(field + "不能为空"); }
        return text;
    }

    private String optionalText(String value, String field, int maxLength) {
        String text = value == null ? null : value.trim();
        if (text != null && text.length() > maxLength) { throw error(field + "内容过长"); }
        return text == null || text.isEmpty() ? null : text;
    }

    private BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw error(field + "异常，金额必须在0到99999999.99之间");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw error(field + "最多支持两位有效小数");
        }
    }

    private OrderSubmitVO submitResult(Orders order) {
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber())
                .orderAmount(order.getAmount()).orderTime(order.getOrderTime()).build();
    }

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) { throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN); }
        return userId;
    }

    private OrderBusinessException error(String message) {
        return new OrderBusinessException(message);
    }
}
