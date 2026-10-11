package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 套餐管理业务实现
 *
 * 六个接口全部实现完毕：saveWithDish（新增）、pageQuery（分页）、startOrStop（起售停售）、
 * getByIdWithDish（回显）、deleteBatch（删除）、updateWithDish（修改）。
 * 建议对照 DishServiceImpl 通读一遍：两套模块结构同构，能看清"一个标准管理端模块"的完整形态。
 */
@Service
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private DishMapper dishMapper;

    /**
     * 新增套餐（含菜品关系）
     */
    @Override
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        // 1. 前端可控字段显式搬运（审计字段不搬，由 @AutoFill(INSERT) 切面填充）
        Setmeal setmeal = new Setmeal();
        setmeal.setName(setmealDTO.getName());
        setmeal.setCategoryId(setmealDTO.getCategoryId());
        setmeal.setPrice(setmealDTO.getPrice());
        setmeal.setImage(setmealDTO.getImage());
        setmeal.setDescription(setmealDTO.getDescription());

        // 2. 后端强制：新增套餐默认【停售】——上架是运营动作，由管理员在列表页手动起售
        setmeal.setStatus(StatusConstant.DISABLE);

        // 3. 先插套餐 —— @Options 把自增主键回填到 setmeal.id，下一步关系记录才挂得上
        setmealMapper.insert(setmeal);

        // 4. 再批量插菜品关系（没选任何菜时跳过，允许创建空套餐）
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && !setmealDishes.isEmpty()) {
            setmealDishes.forEach(sd -> sd.setSetmealId(setmeal.getId()));
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 套餐分页查询
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        // 1. 把分页参数"记"给 PageHelper：它不查库，只挂起参数，拦截下一次 MyBatis 查询时改写 SQL
        //    必须紧挨着查询调用，中间不能插入其他数据库操作
        PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize());

        // 2. 返回的 List 实际是 Page 类型（PageHelper 运行时装进去的），额外带着 total 总数
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);

        // 3. 前端分页组件要的两样：总记录数 + 当前页数据
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除套餐
     */
    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        // 1. 先"全量校验"再动手：起售中的套餐不能删（在售商品不能凭空消失，要删先停售）；
        //    循环里判 setmeal != null 是防御式处理——id 已被并发删掉时跳过，不卡死整个请求
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal != null && StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }

        // 2. 先删关系，再删套餐 —— 两条 delete 必须同一事务：
        //    中途任何一条失败都整体回滚，不会留下"套餐还在、菜品关系没了"的残缺状态
        //    （setmeal 与 setmeal_dish 无物理外键，一致性全靠应用层 + 事务守）
        setmealDishMapper.deleteBySetmealIds(ids);
        setmealMapper.deleteByIds(ids);
    }

    /**
     * 根据id查询套餐（含菜品关系）
     */
    @Override
    public SetmealVO getByIdWithDish(Long id) {
        // 1. 查套餐本体；查不到直接返回 null —— 查询类接口把"没查到"当合法结果，
        //    由前端自行处理；对比"修改"接口里查不到就应该抛"套餐不存在"异常
        Setmeal setmeal = setmealMapper.getById(id);
        if (setmeal == null) {
            return null;
        }

        // 2. 显式组装（延续项目"不走 BeanUtils 黑盒"的风格；SetmealVO 有 @Builder，编译期可检查）
        //    categoryName 刻意不填：它是分页列表要展示的列，回显表单用不到
        return SetmealVO.builder()
                .id(setmeal.getId())
                .categoryId(setmeal.getCategoryId())
                .name(setmeal.getName())
                .price(setmeal.getPrice())
                .status(setmeal.getStatus())
                .description(setmeal.getDescription())
                .image(setmeal.getImage())
                .updateTime(setmeal.getUpdateTime())
                .setmealDishes(setmealDishMapper.getBySetmealId(id))
                .build();
    }

    /**
     * 修改套餐（含菜品关系）
     */
    @Override
    @Transactional
    public void updateWithDish(SetmealDTO setmealDTO) {
        // 1. 定位 + 表单字段显式搬运；刻意【不搬 status】——起售停售是改状态的唯一入口
        //    （status 若能从编辑表单溜进来被改，就能绕过"套餐含停售菜不能起售"的校验防线）
        Setmeal setmeal = new Setmeal();
        setmeal.setId(setmealDTO.getId());
        setmeal.setName(setmealDTO.getName());
        setmeal.setCategoryId(setmealDTO.getCategoryId());
        setmeal.setPrice(setmealDTO.getPrice());
        setmeal.setImage(setmealDTO.getImage());
        setmeal.setDescription(setmealDTO.getDescription());
        setmealMapper.update(setmeal);   // 动态 SQL 只改非空字段；审计字段由 @AutoFill(UPDATE) 切面填充

        // 2. 菜品关系"先删后插"：前端提交的就是完整清单，整体替换最简单可靠
        //    （setmeal_dish.id 会变，但该表不被任何外部数据引用，无副作用）
        setmealDishMapper.deleteBySetmealIds(Collections.singletonList(setmealDTO.getId()));

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && !setmealDishes.isEmpty()) {
            setmealDishes.forEach(sd -> sd.setSetmealId(setmealDTO.getId()));
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 套餐起售停售
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        // 1. 方向不对称：只有【起售】需要校验——起售意味着用户马上能下单，
        //    而套餐是"菜品的组合"，只要有一道菜停售，这个套餐根本做不出来；
        //    停售（下架）是收缩动作，永远安全，不需要任何检查
        if (StatusConstant.ENABLE.equals(status)) {
            List<Dish> dishes = dishMapper.getBySetmealId(id);
            if (dishes != null && !dishes.isEmpty()) {
                for (Dish dish : dishes) {
                    if (StatusConstant.DISABLE.equals(dish.getStatus())) {
                        // 业务校验失败 → 抛异常，由 GlobalExceptionHandler 统一转成 Result.error 给前端
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                }
            }
        }

        // 2. 复用动态 update：只传 id + status，其余字段为 null 不会被碰；
        //    update_time / update_user 由 @AutoFill(UPDATE) 切面在 SQL 执行前补齐
        Setmeal setmeal = Setmeal.builder().id(id).status(status).build();
        setmealMapper.update(setmeal);
    }

}
