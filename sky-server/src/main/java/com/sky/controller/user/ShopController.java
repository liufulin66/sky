package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.ShopService;
import com.sky.vo.ShopStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("userShopController")
@RequestMapping("/user/shop")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "用户端店铺接口")
public class ShopController {
    private final ShopService shopService;

    @GetMapping("/status")
    @Operation(summary = "查询店铺营业状态（公开访问）")
    public Result<ShopStatusVO> getStatus() {
        Integer status = shopService.getStatus();
        log.debug("用户端查询店铺营业状态：status={}", status);
        return Result.success(new ShopStatusVO(status));
    }
}
