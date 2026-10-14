package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Tag(name = "用户端菜品浏览")
@Slf4j
public class DishController {
    @Autowired
    private DishService dishService;

    @GetMapping("/list")
    @Operation(summary = "按分类查询在售菜品及口味，categoryId必填")
    public Result<List<DishVO>> list(@RequestParam(required = false) Long categoryId) {
        log.info("用户端查询菜品：categoryId={}", categoryId);
        return Result.success(dishService.listWithFlavorForUser(categoryId));
    }
}
