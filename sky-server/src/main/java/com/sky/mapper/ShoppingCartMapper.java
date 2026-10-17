package com.sky.mapper;

import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShoppingCartMapper {
    List<ShoppingCart> listByUserId(Long userId);

    ShoppingCart getByVariant(ShoppingCart variant);

    void insert(ShoppingCart cart);

    int incrementAndRefresh(ShoppingCart cart);

    int incrementByAndRefresh(ShoppingCart cart);

    int decrement(@Param("id") Long id, @Param("userId") Long userId);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    void deleteByUserId(Long userId);
}
