package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ShoppingCartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private UserMapper userMapper;

    @Override
    @Transactional
    public void add(ShoppingCartDTO dto) {
        ShoppingCart variant = buildVariant(dto);
        lockUser(variant.getUserId());

        // 价格、名称、图片只从后端商品数据读取；可见性与商品浏览接口一致。
        if (variant.getDishId() != null) {
            Dish dish = dishMapper.getByIdForUser(variant.getDishId());
            if (dish == null) {
                throw new ShoppingCartBusinessException("菜品不存在或已不可售");
            }
            variant.setName(dish.getName());
            variant.setImage(dish.getImage());
            variant.setAmount(dish.getPrice());
        } else {
            Setmeal setmeal = setmealMapper.getByIdForUser(variant.getSetmealId());
            if (setmeal == null) {
                throw new ShoppingCartBusinessException("套餐不存在或已不可售");
            }
            variant.setName(setmeal.getName());
            variant.setImage(setmeal.getImage());
            variant.setAmount(setmeal.getPrice());
        }
        BigDecimal price = variant.getAmount();
        if (price == null || price.signum() < 0) {
            throw new ShoppingCartBusinessException("商品价格异常，暂时无法添加");
        }

        ShoppingCart existing = shoppingCartMapper.getByVariant(variant);
        if (existing == null) {
            variant.setNumber(1);
            variant.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.insert(variant);
        } else {
            variant.setId(existing.getId());
            if (shoppingCartMapper.incrementAndRefresh(variant) != 1) {
                throw new ShoppingCartBusinessException("购物车数量异常或已达上限");
            }
        }
    }

    @Override
    public List<ShoppingCart> list() {
        // 保留已加入的条目和单价，不因商品停售或改价而自动删除、改写购物车。
        return shoppingCartMapper.listByUserId(currentUserId());
    }

    @Override
    @Transactional
    public void sub(ShoppingCartDTO dto) {
        ShoppingCart variant = buildVariant(dto);
        lockUser(variant.getUserId());
        ShoppingCart existing = shoppingCartMapper.getByVariant(variant);
        if (existing == null) {
            return;
        }
        // 减少不校验商品是否仍然在售，停售、删除后仍允许清理已有条目。
        if (existing.getNumber() == null || existing.getNumber() <= 1) {
            shoppingCartMapper.deleteByIdAndUserId(existing.getId(), variant.getUserId());
        } else {
            shoppingCartMapper.decrement(existing.getId(), variant.getUserId());
        }
    }

    @Override
    @Transactional
    public void clean() {
        Long userId = currentUserId();
        lockUser(userId);
        shoppingCartMapper.deleteByUserId(userId);
    }

    private ShoppingCart buildVariant(ShoppingCartDTO dto) {
        Long userId = currentUserId();
        if (dto == null || (dto.getDishId() == null) == (dto.getSetmealId() == null)) {
            throw new ShoppingCartBusinessException("dishId和setmealId必须且只能填写一个");
        }
        Long productId = dto.getDishId() != null ? dto.getDishId() : dto.getSetmealId();
        if (productId <= 0) {
            throw new ShoppingCartBusinessException("商品id必须为正整数");
        }
        String flavor = dto.getDishFlavor() == null ? "" : dto.getDishFlavor().trim();
        if (flavor.length() > 50) {
            throw new ShoppingCartBusinessException("口味内容不能超过50个字符");
        }
        if (dto.getSetmealId() != null && !flavor.isEmpty()) {
            throw new ShoppingCartBusinessException("套餐不支持单独填写菜品口味");
        }
        return ShoppingCart.builder().userId(userId).dishId(dto.getDishId())
                .setmealId(dto.getSetmealId()).dishFlavor(flavor).build();
    }

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return userId;
    }

    private void lockUser(Long userId) {
        // 写操作先锁已有用户行，锁随事务结束释放；同一用户的首次添加也不会并发重复插入。
        if (userMapper.lockById(userId) == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
    }
}
