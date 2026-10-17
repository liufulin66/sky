package com.sky.service;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;

import java.util.List;

public interface ShoppingCartService {
    void add(ShoppingCartDTO dto);

    /** 内部业务按指定份数加购，复用普通加购的参数、可售性与价格校验。 */
    void add(ShoppingCartDTO dto, int number);

    List<ShoppingCart> list();

    void sub(ShoppingCartDTO dto);

    void clean();
}
