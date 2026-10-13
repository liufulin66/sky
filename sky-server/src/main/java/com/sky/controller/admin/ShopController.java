package com.sky.controller.admin;

import com.sky.context.BaseContext;
import com.sky.result.Result;
import com.sky.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "管理端店铺接口")
public class ShopController {
    private final ShopService shopService;

    @PutMapping("/{status}")
    @Operation(summary = "设置店铺营业状态")
    public Result<Void> setStatus(@PathVariable Integer status) {
        shopService.setStatus(status);
        log.info("设置店铺营业状态成功：员工id={}，status={}", BaseContext.getCurrentId(), status);
        return Result.success();
    }

    @GetMapping("/status")
    @Operation(summary = "查询店铺营业状态")
    public Result<Integer> getStatus() {
        Integer status = shopService.getStatus();
        log.debug("管理端查询店铺营业状态：员工id={}，status={}", BaseContext.getCurrentId(), status);
        return Result.success(status);
    }
}
