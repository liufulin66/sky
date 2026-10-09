package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.CategoryDTO;
import com.sky.dto.CategoryPageQueryDTO;
import com.sky.entity.Category;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 新增分类
     *
     * @param categoryDTO
     */
    @Override
    public void save(CategoryDTO categoryDTO) {
        Category category = new Category();

        // 1. 前端可控字段：显式逐个赋值（与员工模块同一思路，不走 BeanUtils 黑盒）
        category.setType(categoryDTO.getType());
        category.setName(categoryDTO.getName());
        category.setSort(categoryDTO.getSort());

        // 2. 后端补齐字段：新分类默认启用（CategoryDTO 里根本没有 status 字段）
        category.setStatus(StatusConstant.ENABLE);

        

        // 4. 入库（name 唯一索引冲突会抛数据库异常，由全局异常处理器按索引名转成"分类名称已存在"）
        categoryMapper.insert(category);
    }

    /**
     * 分类分页查询
     *
     * @param categoryPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(CategoryPageQueryDTO categoryPageQueryDTO) {
        // PageHelper 设置分页参数（只对紧接着的下一条 MyBatis 查询生效），SQL 里没写 LIMIT
        PageHelper.startPage(categoryPageQueryDTO.getPage(), categoryPageQueryDTO.getPageSize());
        Page<Category> page = categoryMapper.pageQuery(categoryPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 启用禁用分类
     *
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        // 只携带"主键 + 要改的字段 + 审计字段"，其余保持 null，与动态 SQL <if> 配合：只更新非空字段
        Category category = Category.builder()
                .id(id)
                .status(status)
                .build();

        categoryMapper.update(category);
    }

    /**
     * 修改分类
     *
     * @param categoryDTO
     */
    @Override
    public void update(CategoryDTO categoryDTO) {
        Category category = new Category();

        // 1. id 用于定位记录，type/name/sort 是编辑表单允许修改的内容
        category.setId(categoryDTO.getId());
        category.setType(categoryDTO.getType());
        category.setName(categoryDTO.getName());
        category.setSort(categoryDTO.getSort());

        
        // 3. 复用与"启用禁用"同一条动态更新 SQL
        categoryMapper.update(category);
    }

    /**
     * 根据id查询分类
     *
     * @param id
     * @return
     */
    @Override
    public Category getById(Long id) {
        return categoryMapper.getById(id);
    }

    /**
     * 根据类型查询分类
     *
     * @param type
     * @return
     */
    @Override
    public List<Category> list(Integer type) {
        // Mapper 的 list 设计为接收 Category 对象（type/status 均为可选条件），管理端只传 type；
        // 将来用户端"按类型查分类"再补一个 status=启用，同一条 SQL 直接复用
        Category category = new Category();
        category.setType(type);

        return categoryMapper.list(category);
    }

    /**
     * 删除分类
     *
     * @param id
     */
    @Override
    public void deleteById(Long id) {
        // 1. 校验分类下是否关联了菜品（表间没有物理外键，关联关系靠应用层守）
        Integer dishCount = dishMapper.countByCategoryId(id);
        if (dishCount > 0) {
            // BaseException 子类 → 全局异常处理器统一转成 Result.error 返回前端
            throw new DeletionNotAllowedException(MessageConstant.CATEGORY_BE_RELATED_BY_DISH);
        }

        // 2. 校验分类下是否关联了套餐
        Integer setmealCount = setmealMapper.countByCategoryId(id);
        if (setmealCount > 0) {
            throw new DeletionNotAllowedException(MessageConstant.CATEGORY_BE_RELATED_BY_SETMEAL);
        }

        // 3. 都没关联，才允许删除
        categoryMapper.deleteById(id);
    }

}
