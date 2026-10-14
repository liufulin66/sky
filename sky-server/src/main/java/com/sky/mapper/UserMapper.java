package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("select * from `user` where openid = #{openid}")
    User getByOpenid(String openid);

    // User 只有 createTime，不符合 @AutoFill 的完整审计字段约定。
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into `user` (openid, create_time) values (#{openid}, #{createTime})")
    void insert(User user);

    /** 购物车写事务中锁定当前用户，串行化同一用户的增减和清空。 */
    Long lockById(Long id);
}
