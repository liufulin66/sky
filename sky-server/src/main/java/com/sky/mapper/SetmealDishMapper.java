package com.sky.mapper;

import com.sky.entity.SetmealDish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealDishMapper {

    /**
     * 根据菜品id列表查询关联的套餐id列表（删除菜品前校验是否被套餐引用）
     *
     * @param dishIds
     * @return
     */
    List<Long> getSetmealIdsByDishIds(@Param("dishIds") List<Long> dishIds);

    // ==================== 以下方法骨架已挂好，SQL 待你补齐 ====================

    /**
     * 根据套餐id查询关联的菜品关系列表（套餐回显用，已实现）
     * 单表外键查询、无动态条件 —— 注解式一行，与 DishFlavorMapper.getByDishId 同款
     *
     * @param setmealId
     * @return
     */
    @Select("select * from setmeal_dish where setmeal_id = #{setmealId}")
    List<SetmealDish> getBySetmealId(Long setmealId);

    /**
     * 批量插入套餐菜品关系
     * TODO 你来写：XML <foreach> 批量 insert（照 DishFlavorMapper.xml 的 insertBatch）
     *      ⚠ 不加 @AutoFill —— setmeal_dish 表没有审计字段，反射会找不到 setter 而失败
     *
     * @param setmealDishes
     */
    void insertBatch(@Param("setmealDishes") List<SetmealDish> setmealDishes);

    /**
     * 按套餐id批量删除关联关系（删除套餐、修改套餐"先删后插"共用）
     * TODO 你来写：XML <foreach> delete（照 DishFlavorMapper.xml 的 deleteByDishIds）
     *
     * @param setmealIds
     */
    void deleteBySetmealIds(@Param("setmealIds") List<Long> setmealIds);

}
