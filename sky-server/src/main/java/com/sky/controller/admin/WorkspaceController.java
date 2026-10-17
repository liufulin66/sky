package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/workspace")
@Tag(name = "工作台接口")
public class WorkspaceController {
    @Autowired private WorkspaceService workspaceService;

    @GetMapping("/businessData")
    @Operation(summary = "今日营业数据，按下单时间统计")
    public Result<BusinessDataVO> businessData() {
        return Result.success(workspaceService.businessData());
    }

    @GetMapping("/overviewOrders")
    @Operation(summary = "今日下单的订单概览")
    public Result<OrderOverViewVO> overviewOrders() {
        return Result.success(workspaceService.overviewOrders());
    }

    @GetMapping("/overviewDishes")
    @Operation(summary = "全部菜品的起售、停售数量")
    public Result<DishOverViewVO> overviewDishes() {
        return Result.success(workspaceService.overviewDishes());
    }

    @GetMapping("/overviewSetmeals")
    @Operation(summary = "全部套餐的起售、停售数量")
    public Result<SetmealOverViewVO> overviewSetmeals() {
        return Result.success(workspaceService.overviewSetmeals());
    }
}
