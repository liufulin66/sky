package com.sky.cache;

import com.sky.properties.ProductCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 仅缓存公开商品浏览。版本号隔离旧结果，避免数据库修改期间的旧查询回填新缓存。
 */
@Component
@Slf4j
public class ProductBrowsingCache {
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final RedisTemplate<String, Object> redis;
    private final StringRedisTemplate strings;
    private final ProductCacheProperties properties;
    private final String prefix;

    @Autowired
    public ProductBrowsingCache(RedisTemplate<String, Object> redis, StringRedisTemplate strings,
                                ProductCacheProperties properties) {
        this(redis, strings, properties, "sky:product:v1:");
    }

    // 测试使用独立随机命名空间，避免读写业务缓存。
    ProductBrowsingCache(RedisTemplate<String, Object> redis, StringRedisTemplate strings,
                         ProductCacheProperties properties, String prefix) {
        this.redis = redis;
        this.strings = strings;
        this.properties = properties;
        this.prefix = prefix;
        if (properties.getTtl() == null || properties.getTtl().isZero() || properties.getTtl().isNegative()
                || properties.getEmptyTtl() == null || properties.getEmptyTtl().isZero()
                || properties.getEmptyTtl().isNegative() || properties.getJitterSeconds() < 0) {
            throw new IllegalArgumentException("商品缓存有效期必须为正数，随机偏移不能为负数");
        }
    }

    public <T> List<T> getList(String name, Class<T> elementType, Supplier<List<T>> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        String version = null;
        String key = null;
        String lockKey = null;
        boolean locked = false;
        String token = UUID.randomUUID().toString();
        try {
            version = getOrCreateVersion();
            if (version != null) {
                key = prefix + version + ":" + name;
                List<T> cached = readList(key, elementType);
                if (cached != null) {
                    return cached;
                }
                lockKey = key + ":lock";
                locked = Boolean.TRUE.equals(strings.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL));
                if (!locked) {
                    // 等待重建者填入缓存，超时便直接查库，不无限等待也不竞争回填。
                    long deadline = System.nanoTime() + WAIT_NANOS;
                    while (System.nanoTime() < deadline) {
                        TimeUnit.MILLISECONDS.sleep(20);
                        cached = readList(key, elementType);
                        if (cached != null) {
                            return cached;
                        }
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("读取商品缓存失败，回退数据库，查询={}", name, ex);
        }
        if (!locked) {
            return loader.get();
        }

        try {
            // 拿锁后再次检查，前一个重建者可能已经完成。
            try {
                List<T> cached = readList(key, elementType);
                if (cached != null) {
                    return cached;
                }
            } catch (RuntimeException ex) {
                log.warn("重查商品缓存失败，回退数据库，查询={}", name, ex);
                return loader.get();
            }
            // 数据库异常必须原样传播，不能被当成 Redis 故障重试或缓存。
            List<T> result = loader.get();
            try {
                if (version.equals(strings.opsForValue().get(versionKey()))) {
                    Duration ttl = result.isEmpty() ? properties.getEmptyTtl()
                            : properties.getTtl().plusSeconds(ThreadLocalRandom.current()
                                    .nextLong((long) properties.getJitterSeconds() + 1));
                    // 固定为 ArrayList，避免缓存 MyBatis/不可变集合的内部实现类型。
                    redis.opsForValue().set(key, new ArrayList<>(result), ttl);
                    log.debug("商品缓存已重建，查询={}，数量={}，有效期={}", name, result.size(), ttl);
                }
            } catch (RuntimeException ex) {
                log.warn("写入商品缓存失败，使用数据库结果，查询={}", name, ex);
            }
            return result;
        } finally {
            try {
                // Lua 原子检查持有者后再删除，避免锁过期后误删其他请求的新锁。
                strings.execute(RELEASE_LOCK, List.of(lockKey), token);
            } catch (RuntimeException ex) {
                log.warn("释放商品缓存重建锁失败，等待锁自动过期，查询={}", name, ex);
            }
        }
    }

    public void invalidateAfterCommit() {
        if (!properties.isEnabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    private void invalidate() {
        try {
            strings.opsForValue().set(versionKey(), UUID.randomUUID().toString());
            log.info("商品目录缓存版本已更新");
        } catch (RuntimeException ex) {
            // 数据库已提交，不将缓存故障伪装成业务修改失败；TTL 负责兜底。
            log.warn("商品修改已提交，但缓存失效失败，旧结果将由有效期兜底", ex);
        }
    }

    private String getOrCreateVersion() {
        String version = strings.opsForValue().get(versionKey());
        if (version == null) {
            // UUID 防止版本键丢失后复用旧版本；并发初始化不覆盖已存在的版本。
            strings.opsForValue().setIfAbsent(versionKey(), UUID.randomUUID().toString());
            version = strings.opsForValue().get(versionKey());
        }
        return version;
    }

    private String versionKey() {
        return prefix + "version";
    }

    private <T> List<T> readList(String key, Class<T> elementType) {
        Object value = redis.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("商品缓存不是列表");
        }
        List<T> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!elementType.isInstance(element)) {
                throw new IllegalStateException("商品缓存元素类型不匹配");
            }
            result.add(elementType.cast(element));
        }
        return result;
    }
}
