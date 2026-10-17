package com.sky.controller.admin;

import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.AdminOrderService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;

@RestController("adminOrderController")
@RequestMapping("/admin/order")
@Tag(name = "商家端订单管理接口")
public class OrderController {
    @Autowired private AdminOrderService adminOrderService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> invalidBody(HttpMessageNotReadableException ex) {
        return Result.error("订单请求体缺失或格式错误");
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> invalidQuery(BindException ex) {
        return Result.error("订单查询参数格式错误，时间格式为yyyy-MM-dd HH:mm:ss");
    }

    @GetMapping("/conditionSearch")
    @Operation(summary = "商家分页搜索订单")
    public Result<PageResult<OrderVO>> search(OrdersPageQueryDTO query) {
        return Result.success(adminOrderService.search(query));
    }

    @GetMapping("/details/{id}")
    @Operation(summary = "商家查询订单详情和成交快照")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(adminOrderService.detail(id));
    }

    @GetMapping("/statistics")
    @Operation(summary = "全部日期待接单、待派送、派送中订单数量")
    public Result<OrderStatisticsVO> statistics() {
        return Result.success(adminOrderService.statistics());
    }

    @PutMapping("/confirm")
    @Operation(summary = "接单，仅支持待接单且已支付订单")
    public Result<Void> confirm(@RequestBody OrdersConfirmDTO dto) {
        // 目标状态由服务端决定，不使用客户端status。
        adminOrderService.confirm(dto.getId());
        return Result.success();
    }

    @PutMapping("/delivery/{id}")
    @Operation(summary = "派送已接单且已支付订单")
    public Result<Void> delivery(@PathVariable Long id) {
        adminOrderService.delivery(id);
        return Result.success();
    }

    @PutMapping("/complete/{id}")
    @Operation(summary = "完成派送中且已支付订单")
    public Result<Void> complete(@PathVariable Long id) {
        adminOrderService.complete(id);
        return Result.success();
    }

    @PutMapping("/cancel")
    @Operation(summary = "商家取消未付款订单，已支付订单暂不支持取消")
    public Result<Void> cancel(@RequestBody OrdersCancelDTO dto) {
        adminOrderService.cancel(dto.getId(), dto.getCancelReason());
        return Result.success();
    }

    @PutMapping("/rejection")
    @Operation(summary = "拒单校验；暂未接入退款，不执行已支付订单拒单")
    public Result<Void> reject(@RequestBody OrdersRejectionDTO dto) {
        adminOrderService.reject(dto.getId(), dto.getRejectionReason());
        return Result.success();
    }
}
