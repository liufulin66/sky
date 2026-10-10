package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器：把 Controller 层抛出的各类异常统一转换为 Result 格式返回给前端
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务异常：一网打尽所有继承 BaseException 的异常，消息直接透传给前端
     */
    @ExceptionHandler(BaseException.class)
    public Result<String> handleBaseException(BaseException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 数据库唯一索引冲突（如新增员工时用户名重复、新增分类时名称重复），两种形态都接住：
     * 原始的 SQLIntegrityConstraintViolationException，
     * 或已被 MyBatis-Spring 持久层异常翻译机制转换后的 DuplicateKeyException
     */
    @ExceptionHandler({ SQLIntegrityConstraintViolationException.class, DuplicateKeyException.class })
    public Result<String> handleDuplicateKey(Exception ex) {
        // MySQL 驱动报错信息形如（不同版本带的索引名不同，但都包含索引名本身）：
        // Duplicate entry 'admin' for key 'employee.idx_username'
        // Duplicate entry '川菜' for key 'category.idx_category_name'
        String message = ex.getMessage();
        if (message != null && message.contains("Duplicate entry")) {
            // 提取单引号之间的重复值（即冲突的那条数据值）
            int start = message.indexOf('\'');
            int end = message.indexOf('\'', start + 1);
            String value = (start >= 0 && end > start) ? message.substring(start + 1, end) : "";

            // 报错串里带着冲突的唯一索引名——按索引分流文案，避免"分类重名"提示成"用户名已存在"
            if (message.contains("idx_category_name")) {
                return Result.error(MessageConstant.CATEGORY_ALREADY_EXISTS);
            }

            if (message.contains("idx_dish_name")) {
                return Result.error(MessageConstant.DISH_ALREADY_EXISTS);
            }

            // 其余（员工表 idx_username 等）沿用原文案
            return Result.error(value + MessageConstant.ALREADY_EXISTS);
        }
        log.error("数据库完整性约束异常", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    /**
     * 兜底：非业务异常（代码 bug、数据库连接失败等），不向外部暴露内部细节
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
