package cn.wildfirechat.bridge.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的 nonce 去重缓存，防止签名重放。
 *
 * 注意：这是单实例内存实现。多实例部署时应替换为 Redis/数据库等共享存储，
 * 否则同一 nonce 可以在不同实例上各用一次。
 */
@Component
public class NonceCache {
    private static final int MAX_ENTRIES = 200_000;

    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();

    public boolean tryUse(String key, long now, long ttlMillis) {
        final boolean[] accepted = {false};
        nonces.compute(key, (k, old) -> {
            if (old != null && now - old < ttlMillis) {
                accepted[0] = false;
                return old;
            }
            accepted[0] = true;
            return now;
        });
        if (nonces.size() > MAX_ENTRIES) {
            cleanup(now, ttlMillis);
        }
        return accepted[0];
    }

    private void cleanup(long now, long ttlMillis) {
        for (String key : nonces.keySet()) {
            Long value = nonces.get(key);
            if (value == null || now - value >= ttlMillis) {
                nonces.remove(key, value);
            }
        }
    }
}
