package com.sky.annotation;

import com.sky.enumeration.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义注解，标识某个 Mapper 方法需要做公共字段自动填充
 * 使用约定：只能标在【第一个参数是实体对象】的 insert / update 方法上
 */
@Target(ElementType.METHOD)          // 只能标在方法上
@Retention(RetentionPolicy.RUNTIME)  // 保留到运行期——切面要靠反射读它，SOURCE/CLASS 级别读不到
public @interface AutoFill {
    /**
     * 数据库操作类型：INSERT / UPDATE
     */
    OperationType value();
}
