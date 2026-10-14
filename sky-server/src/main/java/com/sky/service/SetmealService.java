package com.sky.service;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.vo.DishItemVO;
import com.sky.result.PageResult;
import com.sky.vo.SetmealVO;

import java.util.List;

public interface SetmealService {

    /**
     * 新增套餐（含菜品关系）
     *
     * @param setmealDTO
     */
    void saveWithDish(SetmealDTO setmealDTO);

    /**
     * 套餐分页查询
     *
     * @param setmealPageQueryDTO
     * @return
     */
    PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);

    /**
     * 批量删除套餐
     *
     * @param ids
     */
    void deleteBatch(List<Long> ids);

    /**
     * 根据id查询套餐（含菜品关系，回显用）
     *
     * @param id
     * @return
     */
    SetmealVO getByIdWithDish(Long id);

    /**
     * 修改套餐（含菜品关系）
     *
     * @param setmealDTO
     */
    void updateWithDish(SetmealDTO setmealDTO);

    /**
     * 套餐起售停售
     *
     * @param status
     * @param id
     */
    void startOrStop(Integer status, Long id);

    /** 用户端在售套餐列表，排除禁用分类和包含停售菜品的套餐。 */
    List<Setmeal> listForUser(Long categoryId);

    /** 用户端套餐内菜品及份数；不可见或不存在的套餐返回空集合。 */
    List<DishItemVO> getDishItemsForUser(Long id);

}
