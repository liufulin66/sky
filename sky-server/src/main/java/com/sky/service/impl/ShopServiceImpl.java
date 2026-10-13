package com.sky.service.impl;

import com.sky.constant.ShopStatusConstant;
import com.sky.exception.BaseException;
import com.sky.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopServiceImpl implements ShopService {
    private final StringRedisTemplate redisTemplate;

    @Override
    public void setStatus(Integer status) {
        if (!ShopStatusConstant.CLOSED.equals(status) && !ShopStatusConstant.OPEN.equals(status)) {
            throw new BaseException("营业状态只能为0（打烊）或1（营业）");
        }
        try {
            // 不设置有效期，也不在应用启动时覆盖管理员已设置的状态。
            redisTemplate.opsForValue().set(ShopStatusConstant.REDIS_KEY, status.toString());
        } catch (DataAccessException ex) {
            log.error("设置店铺营业状态失败", ex);
            throw new BaseException("营业状态设置失败，请稍后重试");
        }
    }

    @Override
    public Integer getStatus() {
        String value;
        try {
            value = redisTemplate.opsForValue().get(ShopStatusConstant.REDIS_KEY);
        } catch (DataAccessException ex) {
            log.error("读取店铺营业状态失败", ex);
            throw new BaseException("营业状态暂时无法确认，请稍后重试");
        }
        // 尚未设置状态时默认打烊；读取接口不写入 Redis，避免覆盖并发的开店操作。
        if (value == null || "0".equals(value)) {
            return ShopStatusConstant.CLOSED;
        }
        if ("1".equals(value)) {
            return ShopStatusConstant.OPEN;
        }
        log.error("Redis 中的店铺营业状态值异常");
        throw new BaseException("营业状态暂时无法确认，请稍后重试");
    }

    @Override
    public void checkOpenForNewOrder() {
        if (!ShopStatusConstant.OPEN.equals(getStatus())) {
            throw new BaseException("店铺已打烊，暂时无法下单");
        }
    }
}
