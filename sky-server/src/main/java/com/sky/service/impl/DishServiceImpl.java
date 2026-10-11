package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    /**
     * 新增菜品（含口味）
     */
    @Override
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        // 1. 前端可控字段（显式搬运；审计字段由 @AutoFill(INSERT) 切面填充）
        dish.setName(dishDTO.getName());
        dish.setCategoryId(dishDTO.getCategoryId());
        dish.setPrice(dishDTO.getPrice());
        dish.setImage(dishDTO.getImage());
        dish.setDescription(dishDTO.getDescription());

        // 2. 后端补齐：新增菜品默认【停售】——菜品是否上架是运营决策，由管理员在列表页手动起售
        dish.setStatus(StatusConstant.DISABLE);

        // 3. 先插菜品 —— @Options 会把自增主键回填到 dish.id，下面口味才挂得上
        dishMapper.insert(dish);

        // 4. 再批量插口味（没有口味时跳过，允许创建无口味菜品）
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            flavors.forEach(f -> f.setDishId(dish.getId()));
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 菜品分页查询
     */
    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除菜品
     */
    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        // 1. 起售中的菜品不能删
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            if (dish != null && StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }

        // 2. 被套餐关联的菜品不能删（dish 和 setmeal_dish 无物理外键，应用层守）
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && !setmealIds.isEmpty()) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        // 3. 先删菜品，再删它们的所有口味（两条 delete 必须在同一事务里）
        dishMapper.deleteByIds(ids);
        dishFlavorMapper.deleteByDishIds(ids);
    }

    /**
     * 根据id查询菜品（含口味）
     */
    @Override
    public DishVO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.getById(id);
        if (dish == null) {
            return null;
        }

        // 显式组装（延续项目"不走 BeanUtils 黑盒"的风格；DishVO 有 @Builder，编译期可检查）
        return DishVO.builder()
                .id(dish.getId())
                .name(dish.getName())
                .categoryId(dish.getCategoryId())
                .price(dish.getPrice())
                .image(dish.getImage())
                .description(dish.getDescription())
                .status(dish.getStatus())
                .updateTime(dish.getUpdateTime())
                .flavors(dishFlavorMapper.getByDishId(id))
                .build();
    }

    /**
     * 修改菜品（含口味，先删后插）
     */
    @Override
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        // 1. 定位 + 表单字段；刻意不搬 status——起售停售是改状态的唯一入口（与分类模块同约定）
        dish.setId(dishDTO.getId());
        dish.setName(dishDTO.getName());
        dish.setCategoryId(dishDTO.getCategoryId());
        dish.setPrice(dishDTO.getPrice());
        dish.setImage(dishDTO.getImage());
        dish.setDescription(dishDTO.getDescription());
        dishMapper.update(dish);   // 审计字段由 @AutoFill(UPDATE) 切面填充
                                      
        // 2. 口味"先删后插"：前端提交的就是完整口味列表，整体替换最简单可靠
        //    （dish_flavor.id 会变，但该表不被任何外部数据引用，无副作用）
        dishFlavorMapper.deleteByDishIds(Collections.singletonList(dishDTO.getId()));

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            flavors.forEach(f -> f.setDishId(dishDTO.getId()));
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 菜品起售停售
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder().id(id).status(status).build();
        dishMapper.update(dish);   // 审计字段由切面填充
    }

    /**
     * 根据分类id查询菜品
     */
    @Override
    public List<Dish> list(Long categoryId) {
        // Mapper 的 list 是动态条件查询（name/categoryId/status 均可选）；
        // 管理端只按 categoryId 过滤，用户端将来补 status=起售即可复用
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        return dishMapper.list(dish);
    }

}
