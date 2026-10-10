package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishMapper {

    /**
     * 插入菜品数据
     * ⚠ 三个注解各司其职，一个都不能少：
     *   @AutoFill(INSERT) 切面自动填充审计字段
     *   @Options(useGeneratedKeys=true, keyProperty="id") 把数据库自增生成的主键【回填】到 dish.id
     *     —— 新增菜品后要靠它拿到菜品id，才能给口味记录设置 dishId
     */
    @AutoFill(OperationType.INSERT)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user) " +
            "values (#{name}, #{categoryId}, #{price}, #{image}, #{description}, #{status}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser})")
    void insert(Dish dish);

    /**
     * 菜品分页查询（XML：动态条件 + 关联分类表取分类名）
     *
     * @param dishPageQueryDTO
     * @return
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 根据id查询菜品
     *
     * @param id
     * @return
     */
    @Select("select * from dish where id = #{id}")
    Dish getById(Long id);

    /**
     * 动态更新（只更新非空字段）——「修改菜品」与「起售停售」共用
     *
     * @param dish
     */
    @AutoFill(OperationType.UPDATE)
    void update(Dish dish);

    /**
     * 批量删除菜品
     *
     * @param ids
     */
    void deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 根据条件查询菜品（XML 动态，管理端目前只传 categoryId；用户端将来补 status=起售）
     *
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

    Integer countByCategoryId(Long id);

}
