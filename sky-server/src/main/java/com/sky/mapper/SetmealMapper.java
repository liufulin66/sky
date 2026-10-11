package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.enumeration.OperationType;
import com.sky.vo.SetmealVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐数量（用于删除分类前的关联校验）
     * 注：setmeal.category_id 上没有物理外键，关联关系靠应用层维护
     *
     * @param categoryId
     * @return
     */
    @Select("select count(*) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    // ==================== 以下方法骨架已挂好，SQL 待你补齐 ====================

    /**
     * 插入套餐数据（已实现）
     * 三个注解各司其职，一个都不能少：
     *   @AutoFill(INSERT) 切面在 SQL 执行前反射填充审计字段（登录人取自 BaseContext）
     *   @Options(useGeneratedKeys = true, keyProperty = "id") 把数据库自增主键【回填】到 setmeal.id
     *     —— 新增套餐后要靠它拿到套餐id，才能给 setmeal_dish 关系记录设置 setmealId
     *   @Insert 里 #{createTime} 等审计字段业务代码不赋值、由切面负责，所以这些列必须留在 SQL 里
     *
     * @param setmeal
     */
    @AutoFill(OperationType.INSERT)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into setmeal (name, category_id, price, status, description, image, create_time, update_time, create_user, update_user) " +
            "values (#{name}, #{categoryId}, #{price}, #{status}, #{description}, #{image}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser})")
    void insert(Setmeal setmeal);

    /**
     * 套餐分页查询
     * TODO 你来写：XML 动态条件（name 模糊 / categoryId 精确 / status 精确）
     *      + left join category 取分类名，返回 Page<SetmealVO>——
     *      写法照 DishMapper.xml 的 pageQuery（PageHelper 会自动改写 SQL 追加 limit + 执行 count）
     *
     * @param setmealPageQueryDTO
     * @return
     */
    Page<SetmealVO> pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);

    /**
     * 根据id查询套餐（已实现）
     * 单表主键查询、无动态条件 —— 注解式一行即可，与 DishMapper.getById 同款
     *
     * @param id
     * @return
     */
    @Select("select * from setmeal where id = #{id}")
    Setmeal getById(Long id);

    /**
     * 动态更新（只更新非空字段）——「修改套餐」与「起售停售」共用
     * SQL 已在 SetmealMapper.xml 实现（update 语句）
     *
     * @param setmeal
     */
    @AutoFill(OperationType.UPDATE)
    void update(Setmeal setmeal);

    /**
     * 批量删除套餐
     * TODO 你来写：XML <foreach> 拼 where id in (1,2,3)（照 DishMapper.xml 的 deleteByIds）
     *
     * @param ids
     */
    void deleteByIds(@Param("ids") List<Long> ids);

}
