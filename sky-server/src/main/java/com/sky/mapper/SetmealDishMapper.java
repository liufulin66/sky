package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

}
