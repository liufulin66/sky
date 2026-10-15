package com.sky.controller.user;

import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("userOrderController")
@RequestMapping("/user/order")
@Tag(name = "用户端订单接口")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleInvalidBody(HttpMessageNotReadableException ex) {
        return Result.error("下单请求体缺失或格式错误");
    }

    @PostMapping("/submit")
    @Operation(summary = "提交订单，相同requestId和表单返回同一订单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO dto) {
        return Result.success(orderService.submit(dto));
    }

    @GetMapping("/historyOrders")
    @Operation(summary = "分页查询本人订单，status可选")
    public Result<PageResult<OrderVO>> historyOrders(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.historyOrders(page, pageSize, status));
    }

    @GetMapping("/orderDetail/{id}")
    @Operation(summary = "查询本人订单详情和成交快照")
    public Result<OrderVO> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.orderDetail(id));
    }
}
