package com.sky.controller.user;

import com.sky.entity.Setmeal;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
@Tag(name = "用户端套餐浏览")
@Slf4j
public class SetmealController {
    @Autowired
    private SetmealService setmealService;

    @GetMapping("/list")
    @Operation(summary = "按分类查询可售套餐，categoryId必填")
    public Result<List<Setmeal>> list(@RequestParam(required = false) Long categoryId) {
        log.info("用户端查询套餐：categoryId={}", categoryId);
        return Result.success(setmealService.listForUser(categoryId));
    }

    @GetMapping("/dish/{id}")
    @Operation(summary = "查询可售套餐内的菜品及份数")
    public Result<List<DishItemVO>> dishItems(@PathVariable Long id) {
        log.info("用户端查询套餐菜品：setmealId={}", id);
        return Result.success(setmealService.getDishItemsForUser(id));
    }
}
