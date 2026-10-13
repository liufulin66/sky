package com.sky.controller.admin;

import com.sky.context.BaseContext;
import com.sky.dto.ShopStatusDTO;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import com.sky.service.ShopService;
import com.sky.vo.ShopStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "管理端店铺接口")
public class ShopController {
    private final ShopService shopService;

    @PutMapping("/status")
    @Operation(summary = "设置店铺营业状态")
    public Result<Void> setStatus(@RequestBody ShopStatusDTO dto) {
        if (dto == null) {
            throw new BaseException("营业状态不能为空");
        }
        shopService.setStatus(dto.getStatus());
        log.info("设置店铺营业状态成功：员工id={}，status={}", BaseContext.getCurrentId(), dto.getStatus());
        return Result.success();
    }

    @GetMapping("/status")
    @Operation(summary = "查询店铺营业状态")
    public Result<ShopStatusVO> getStatus() {
        Integer status = shopService.getStatus();
        log.debug("管理端查询店铺营业状态：员工id={}，status={}", BaseContext.getCurrentId(), status);
        return Result.success(new ShopStatusVO(status));
    }
}
