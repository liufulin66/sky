package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishFlavorMapper {

    /**
     * 批量插入口味（foreach 动态 SQL 写在 XML）
     * ⚠ 不加 @AutoFill —— dish_flavor 表没有审计字段，反射会找不到 setter 而失败
     *
     * @param flavors
     */
    void insertBatch(@Param("flavors") List<DishFlavor> flavors);

    /**
     * 按菜品id批量删除口味（批量删除菜品 / 修改菜品"先删后插"共用）
     *
     * @param dishIds
     */
    void deleteByDishIds(@Param("dishIds") List<Long> dishIds);

    /**
     * 根据菜品id查询口味列表（回显用）
     *
     * @param dishId
     * @return
     */
    @Select("select * from dish_flavor where dish_id = #{dishId}")
    List<DishFlavor> getByDishId(Long dishId);

}
