package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.constant.AutoFillConstant;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 公共字段自动填充切面
 *
 * 工作原理：Spring 给每个 Mapper 生成代理对象，调用被 @AutoFill 标记的方法时，
 * 先执行本切面的 @Before 逻辑（反射给参数实体补上审计字段），再真正执行 SQL。
 */
@Aspect
@Component
@Slf4j
public class AutoFillAspect {

    /**
     * 切点：com.sky.mapper 包下所有【标注了 @AutoFill】的方法
     * 两个条件缺一不可：
     *   execution(...)  限定包范围（缩小匹配面、防止误拦其他包的方法）
     *   @annotation(...) 只有带注解的方法才拦——注解就是"要不要填充"的开关
     */
    @Before("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFill(JoinPoint joinPoint) {
        log.info("开始进行公共字段自动填充...");

        // 1. 从方法签名上取出注解，得到操作类型（INSERT / UPDATE）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);
        OperationType operationType = autoFill.value();

        // 2. 约定：实体对象是方法的第一个参数（所有被标记的 mapper 方法都符合此约定）
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        Object entity = args[0];

        // 3. 准备填充值：当前时间 + 当前登录人id（拦截器已存入 ThreadLocal）
        LocalDateTime now = LocalDateTime.now();
        Long currentId = BaseContext.getCurrentId();

        // 4. 反射调用 setter —— 编译期只知道 entity 是 Object，无法直接调用，只能运行时按方法名查找
        //    方法名字符串来自 AutoFillConstant，与实体类型解耦：同一个切面能服务 Employee/Category/Dish 所有实体
        try {
            if (operationType == OperationType.INSERT) {
                Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
                Method setCreateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                setCreateTime.invoke(entity, now);
                setCreateUser.invoke(entity, currentId);
                setUpdateTime.invoke(entity, now);
                setUpdateUser.invoke(entity, currentId);
            } else if (operationType == OperationType.UPDATE) {
                Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
                Method setUpdateUser = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

                setUpdateTime.invoke(entity, now);
                setUpdateUser.invoke(entity, currentId);
            }
        } catch (Exception e) {
            // 反射失败=代码错误（如把注解标到了没有审计字段的实体上）。
            // 直接抛异常阻断本次数据库操作——宁可整个失败，也不能写入"只填了一半"的数据
            log.error("公共字段自动填充失败", e);
            throw new RuntimeException(e);
        }
    }
}
