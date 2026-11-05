package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.MediaUrlMapping;
import cn.wildfirechat.bridge.jpa.MediaUrlMappingRepository;
import cn.wildfirechat.bridge.utilis.SignatureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaUrlMappingService {
    private static final Logger LOG = LoggerFactory.getLogger(MediaUrlMappingService.class);

    //进程内缓存，避免每条出入站媒体消息都查一次库。映射一旦建立就不会再变，所以不需要过期时间，只做容量上限。
    private static final int CACHE_MAX_SIZE = 8192;

    //查不到映射时缓存一小段时间，避免"没有映射"的 URL 每次都查库；映射是只增不改的，短暂延迟可以接受
    private static final long NEGATIVE_CACHE_TTL_MS = 30_000L;

    //in 查询一次最多带多少个值，避免超长 SQL
    private static final int QUERY_BATCH_SIZE = 500;

    //一次清理任务最多删多少批，避免单次任务执行太久
    private static final int CLEANUP_MAX_BATCHES = 100;

    private final MediaUrlMappingRepository repository;

    private final Map<String, String> remoteMediaUrlCache = Collections.synchronizedMap(new BoundedCache());
    private final Map<String, String> localMediaUrlCache = Collections.synchronizedMap(new BoundedCache());

    //负缓存：key -> 过期时间(毫秒)。只存"查过但没有映射"的 key，容量有上限，满了直接清空
    private final Map<String, Long> negativeCache = new ConcurrentHashMap<>();

    //映射保留天数，<=0 表示不清理。组合消息被撤回/删除后重新publish时需要按映射恢复原地址，
    //所以保留期要明显大于消息可能被重新publish的时间窗口。
    @Value("${bridge.media_url_mapping_retention_days:180}")
    int retentionDays;

    //清理任务是否在本实例执行。多实例部署时建议只在一个实例上打开，避免并发删同一批数据
    @Value("${bridge.media_url_mapping_cleanup_enabled:true}")
    boolean cleanupEnabled;

    @Value("${bridge.media_url_mapping_cleanup_batch_size:1000}")
    int cleanupBatchSize;

    public MediaUrlMappingService(MediaUrlMappingRepository repository) {
        this.repository = repository;
    }

    public Optional<String> findRemoteMediaUrl(String domainId, String localMediaUrl) {
        if(!StringUtils.hasText(domainId) || !StringUtils.hasText(localMediaUrl)) {
            return Optional.empty();
        }
        String cacheKey = cacheKey(domainId, localMediaUrl);
        String cached = remoteMediaUrlCache.get(cacheKey);
        if(cached != null) {
            return Optional.of(cached);
        }
        if(isNegativeCached(cacheKey)) {
            return Optional.empty();
        }
        try {
            Optional<String> result = repository.findByDomainIdAndLocalUrlHash(domainId, hash(localMediaUrl))
                .filter(mapping -> localMediaUrl.equals(mapping.localMediaUrl))
                .map(mapping -> mapping.remoteMediaUrl);
            if(result.isPresent()) {
                remoteMediaUrlCache.put(cacheKey, result.get());
                removeNegative(cacheKey);
            } else {
                cacheNegative(cacheKey);
            }
            return result;
        } catch (DataAccessException e) {
            LOG.warn("query media url mapping failed, domain {}, local media url {}", domainId, localMediaUrl, e);
            return Optional.empty();
        }
    }

    public Optional<String> findLocalMediaUrl(String domainId, String remoteMediaUrl) {
        if(!StringUtils.hasText(domainId) || !StringUtils.hasText(remoteMediaUrl)) {
            return Optional.empty();
        }
        String cacheKey = cacheKey(domainId, remoteMediaUrl);
        String cached = localMediaUrlCache.get(cacheKey);
        if(cached != null) {
            return Optional.of(cached);
        }
        if(isNegativeCached(cacheKey)) {
            return Optional.empty();
        }
        try {
            Optional<String> result = repository.findByDomainIdAndRemoteUrlHash(domainId, hash(remoteMediaUrl))
                .filter(mapping -> remoteMediaUrl.equals(mapping.remoteMediaUrl))
                .map(mapping -> mapping.localMediaUrl);
            if(result.isPresent()) {
                localMediaUrlCache.put(cacheKey, result.get());
                removeNegative(cacheKey);
            } else {
                cacheNegative(cacheKey);
            }
            return result;
        } catch (DataAccessException e) {
            LOG.warn("query media url mapping failed, domain {}, remote media url {}", domainId, remoteMediaUrl, e);
            return Optional.empty();
        }
    }

    //组合消息批量预取：一次查出多个本域地址对应的对端地址，后面的 findRemoteMediaUrl 直接走缓存。
    //查询失败只记录日志，调用方会退化成逐个查询。
    public void prefetchRemoteMediaUrls(String domainId, Collection<String> localMediaUrls) {
        if(!StringUtils.hasText(domainId) || localMediaUrls == null || localMediaUrls.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String mediaUrl : localMediaUrls) {
            if(!StringUtils.hasText(mediaUrl)) {
                continue;
            }
            String cacheKey = cacheKey(domainId, mediaUrl);
            if(remoteMediaUrlCache.get(cacheKey) == null && !isNegativeCached(cacheKey)) {
                missing.add(mediaUrl);
            }
        }
        if(missing.isEmpty()) {
            return;
        }
        try {
            for (List<String> chunk : partition(missing, QUERY_BATCH_SIZE)) {
                Set<String> chunkSet = new HashSet<>(chunk);
                List<String> hashes = new ArrayList<>(chunk.size());
                for (String mediaUrl : chunk) {
                    hashes.add(hash(mediaUrl));
                }
                Set<String> found = new HashSet<>();
                for (MediaUrlMapping mapping : repository.findByDomainIdAndLocalUrlHashIn(domainId, hashes)) {
                    if(chunkSet.contains(mapping.localMediaUrl)) {
                        remoteMediaUrlCache.put(cacheKey(domainId, mapping.localMediaUrl), mapping.remoteMediaUrl);
                        found.add(mapping.localMediaUrl);
                    }
                }
                for (String mediaUrl : chunk) {
                    if(!found.contains(mediaUrl)) {
                        cacheNegative(cacheKey(domainId, mediaUrl));
                    }
                }
            }
        } catch (DataAccessException e) {
            LOG.warn("prefetch media url mappings failed, domain {}", domainId, e);
        }
    }

    //组合消息批量预取：一次查出多个对端地址对应的本域地址
    public void prefetchLocalMediaUrls(String domainId, Collection<String> remoteMediaUrls) {
        if(!StringUtils.hasText(domainId) || remoteMediaUrls == null || remoteMediaUrls.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String mediaUrl : remoteMediaUrls) {
            if(!StringUtils.hasText(mediaUrl)) {
                continue;
            }
            String cacheKey = cacheKey(domainId, mediaUrl);
            if(localMediaUrlCache.get(cacheKey) == null && !isNegativeCached(cacheKey)) {
                missing.add(mediaUrl);
            }
        }
        if(missing.isEmpty()) {
            return;
        }
        try {
            for (List<String> chunk : partition(missing, QUERY_BATCH_SIZE)) {
                Set<String> chunkSet = new HashSet<>(chunk);
                List<String> hashes = new ArrayList<>(chunk.size());
                for (String mediaUrl : chunk) {
                    hashes.add(hash(mediaUrl));
                }
                Set<String> found = new HashSet<>();
                for (MediaUrlMapping mapping : repository.findByDomainIdAndRemoteUrlHashIn(domainId, hashes)) {
                    if(chunkSet.contains(mapping.remoteMediaUrl)) {
                        localMediaUrlCache.put(cacheKey(domainId, mapping.remoteMediaUrl), mapping.localMediaUrl);
                        found.add(mapping.remoteMediaUrl);
                    }
                }
                for (String mediaUrl : chunk) {
                    if(!found.contains(mediaUrl)) {
                        cacheNegative(cacheKey(domainId, mediaUrl));
                    }
                }
            }
        } catch (DataAccessException e) {
            LOG.warn("prefetch media url mappings failed, domain {}", domainId, e);
        }
    }

    public void saveMapping(String domainId, String localMediaUrl, String remoteMediaUrl) {
        if(!StringUtils.hasText(domainId) || !StringUtils.hasText(localMediaUrl) || !StringUtils.hasText(remoteMediaUrl)) {
            return;
        }
        String localCacheKey = cacheKey(domainId, localMediaUrl);
        String remoteCacheKey = cacheKey(domainId, remoteMediaUrl);
        try {
            //直接插入，不"先查后插"，少一次查询。不用 MySQL 的 on duplicate key 这类方言，保证各数据库通用；
            //已存在（如并发下另一个请求先插入）会触发唯一键冲突，按"已存在"处理即可
            MediaUrlMapping mapping = new MediaUrlMapping();
            mapping.domainId = domainId;
            mapping.localUrlHash = hash(localMediaUrl);
            mapping.localMediaUrl = localMediaUrl;
            mapping.remoteUrlHash = hash(remoteMediaUrl);
            mapping.remoteMediaUrl = remoteMediaUrl;
            mapping.createdAt = System.currentTimeMillis();
            try {
                repository.save(mapping);
            } catch (DataIntegrityViolationException e) {
                LOG.debug("media url mapping already exists, domain {}, local media url {}", domainId, localMediaUrl);
            }
            remoteMediaUrlCache.put(localCacheKey, remoteMediaUrl);
            localMediaUrlCache.put(remoteCacheKey, localMediaUrl);
            removeNegative(localCacheKey);
            removeNegative(remoteCacheKey);
        } catch (DataAccessException e) {
            LOG.warn("save media url mapping failed, domain {}, local media url {}", domainId, localMediaUrl, e);
        }
    }

    //按保留期分批清理历史映射，避免一次删除太多行导致长事务和长时间持锁。
    @Scheduled(cron = "${bridge.media_url_mapping_cleanup_cron:0 30 3 * * *}")
    public void cleanupExpiredMappings() {
        if(!cleanupEnabled || retentionDays <= 0) {
            return;
        }
        int batchSize = cleanupBatchSize > 0 ? cleanupBatchSize : 1000;
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
        try {
            int total = 0;
            for (int i = 0; i < CLEANUP_MAX_BATCHES; i++) {
                List<Long> ids = repository.findExpiredIds(cutoff, PageRequest.of(0, batchSize));
                if(ids.isEmpty()) {
                    break;
                }
                total += repository.deleteByIds(ids);
                if(ids.size() < batchSize) {
                    break;
                }
            }
            if(total > 0) {
                LOG.info("deleted {} expired media url mappings older than {} days", total, retentionDays);
            }
        } catch (DataAccessException e) {
            LOG.warn("cleanup expired media url mappings failed", e);
        }
    }

    private boolean isNegativeCached(String cacheKey) {
        Long expireAt = negativeCache.get(cacheKey);
        if(expireAt == null) {
            return false;
        }
        if(expireAt <= System.currentTimeMillis()) {
            negativeCache.remove(cacheKey);
            return false;
        }
        return true;
    }

    private void cacheNegative(String cacheKey) {
        if(negativeCache.size() >= CACHE_MAX_SIZE) {
            negativeCache.clear();
        }
        negativeCache.put(cacheKey, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
    }

    private void removeNegative(String cacheKey) {
        negativeCache.remove(cacheKey);
    }

    private static <T> List<List<T>> partition(Collection<T> values, int batchSize) {
        List<List<T>> chunks = new ArrayList<>();
        List<T> current = new ArrayList<>(batchSize);
        for (T value : values) {
            current.add(value);
            if(current.size() >= batchSize) {
                chunks.add(current);
                current = new ArrayList<>(batchSize);
            }
        }
        if(!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private String cacheKey(String domainId, String mediaUrl) {
        return domainId + '\n' + mediaUrl;
    }

    private String hash(String mediaUrl) {
        return SignatureUtils.sha256Hex(mediaUrl.getBytes(StandardCharsets.UTF_8));
    }

    private static class BoundedCache extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;

        BoundedCache() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    }
}
