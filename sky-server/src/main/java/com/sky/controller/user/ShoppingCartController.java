package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import com.sky.result.Result;
import com.sky.service.ShoppingCartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.List;

@RestController("userShoppingCartController")
@RequestMapping("/user/shoppingCart")
@Tag(name = "用户端购物车接口")
@Slf4j
public class ShoppingCartController {
    @Autowired
    private ShoppingCartService shoppingCartService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleInvalidBody(HttpMessageNotReadableException ex) {
        log.warn("购物车请求体缺失或格式错误：用户id={}", BaseContext.getCurrentId());
        return Result.error("购物车请求体缺失或格式错误");
    }

    @PostMapping("/add")
    @Operation(summary = "添加一份商品到购物车")
    public Result<Void> add(@RequestBody ShoppingCartDTO dto) {
        shoppingCartService.add(dto);
        log.info("购物车添加成功：用户id={}，dishId={}，setmealId={}",
                BaseContext.getCurrentId(), dto.getDishId(), dto.getSetmealId());
        return Result.success();
    }

    @GetMapping("/list")
    @Operation(summary = "查询当前用户购物车，amount为单价")
    public Result<List<ShoppingCart>> list() {
        log.info("查询购物车：用户id={}", BaseContext.getCurrentId());
        return Result.success(shoppingCartService.list());
    }

    @PostMapping("/sub")
    @Operation(summary = "减少一份商品，数量为零时删除")
    public Result<Void> sub(@RequestBody ShoppingCartDTO dto) {
        shoppingCartService.sub(dto);
        log.info("购物车减少成功：用户id={}，dishId={}，setmealId={}",
                BaseContext.getCurrentId(), dto.getDishId(), dto.getSetmealId());
        return Result.success();
    }

    @DeleteMapping("/clean")
    @Operation(summary = "清空当前用户购物车")
    public Result<Void> clean() {
        shoppingCartService.clean();
        log.info("清空购物车成功：用户id={}", BaseContext.getCurrentId());
        return Result.success();
    }
}
