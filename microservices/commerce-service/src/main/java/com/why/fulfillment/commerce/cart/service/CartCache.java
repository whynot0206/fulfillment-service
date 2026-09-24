package com.why.fulfillment.commerce.cart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 购物车读缓存。
 *
 * <p><b>一致性口径：MySQL 是事实，Redis 只是副本。</b>写操作只做两件事——
 * 写库、然后删键。不做「写库 + 改缓存」：两个写请求的「改缓存」步骤可能乱序到达，
 * 留下一个比数据库还旧的值，而删键最坏只是下一次读回源。</p>
 *
 * <p><b>Redis 挂掉不影响功能。</b>读失败当缓存未命中、写失败只记日志，
 * 因为这里缓存的是可以随时从 MySQL 重建的数据。如果哪天有人把
 * 「只存在于 Redis 的字段」加进来，这条降级就不再成立了。</p>
 *
 * <p><b>没有做的事，写在这里避免以后误以为做过：</b>没有防缓存击穿的单飞锁，
 * 也没有空值缓存。购物车是按用户分散的键，不存在单键热点，
 * 加锁的收益抵不上复杂度。真要证明缓存有收益，得先压一次带/不带缓存的读接口。</p>
 */
@Component
public class CartCache {

    private static final Logger log = LoggerFactory.getLogger(CartCache.class);

    private static final String KEY_PREFIX = "commerce:cart:";
    private static final TypeReference<List<CartItemSnapshot>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration ttl;

    public CartCache(StringRedisTemplate redisTemplate,
                     @Value("${commerce.cart.cache-ttl-seconds}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** @return 缓存命中的条目；未命中或 Redis 不可用时返回 null */
    public List<CartItemSnapshot> read(long userId) {
        try {
            String raw = redisTemplate.opsForValue().get(key(userId));
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, LIST_TYPE);
        } catch (Exception exception) {
            // 包括反序列化失败：老格式的残留值不该让购物车打不开。
            log.warn("Cart cache read failed for user {}: {}", userId, exception.toString());
            return null;
        }
    }

    public void write(long userId, List<CartItemSnapshot> items) {
        try {
            redisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(items), ttl);
        } catch (Exception exception) {
            log.warn("Cart cache write failed for user {}: {}", userId, exception.toString());
        }
    }

    /** 任何改动购物车的写操作**提交之后**都要调用。 */
    public void evict(long userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (Exception exception) {
            // 删不掉就只能等 TTL 到期。这是有界的陈旧，不是永久脏读——
            // 这也正是 TTL 不能设成永不过期的原因。
            log.warn("Cart cache evict failed for user {}: {}", userId, exception.toString());
        }
    }

    /**
     * 在当前事务结束之后再删键；没有事务时立即删。
     *
     * <p><b>为什么不能在事务里直接删：</b>删键发生在提交之前的话，
     * 另一个线程可能在这个间隙读缓存未命中、回源读到**尚未提交**的旧数据、
     * 再把旧数据写回缓存。于是缓存里留下一个比数据库还旧的值，
     * 而且不会再被任何写操作失效，只能等 TTL。这类 bug 在本机单线程
     * 测试里永远复现不出来。</p>
     *
     * <p>用 afterCompletion 而不是 afterCommit：回滚时多删一次缓存的代价
     * 只是一次回源，而漏删的代价是一段时间的脏读。</p>
     */
    public void evictAfterTransaction(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                evict(userId);
            }
        });
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
