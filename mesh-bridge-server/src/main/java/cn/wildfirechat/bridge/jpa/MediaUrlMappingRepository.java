package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MediaUrlMappingRepository extends CrudRepository<MediaUrlMapping, Long> {
    Optional<MediaUrlMapping> findByDomainIdAndLocalUrlHash(String domainId, String localUrlHash);

    Optional<MediaUrlMapping> findByDomainIdAndRemoteUrlHash(String domainId, String remoteUrlHash);

    //组合消息批量查询映射用，避免每个子消息查一次
    List<MediaUrlMapping> findByDomainIdAndLocalUrlHashIn(String domainId, Collection<String> localUrlHashes);

    List<MediaUrlMapping> findByDomainIdAndRemoteUrlHashIn(String domainId, Collection<String> remoteUrlHashes);

    //插入映射，已存在时忽略。用 upsert 替代"先查后插"，少一次查询，也避免并发下的唯一键异常
    @Transactional
    @Modifying
    @Query(value = "insert into media_url_mappings " +
        "(domain_id, local_url_hash, local_media_url, remote_url_hash, remote_media_url, created_at) " +
        "values (?1, ?2, ?3, ?4, ?5, ?6) on duplicate key update id = id", nativeQuery = true)
    int insertIgnore(String domainId, String localUrlHash, String localMediaUrl, String remoteUrlHash, String remoteMediaUrl, long createdAt);

    //按保留期分批删除历史映射，避免一次删除太多行导致长事务/长时间持锁
    //每个批次单独一个事务，避免整个清理任务一个大事务长时间持锁
    @Transactional
    @Modifying
    @Query(value = "delete from media_url_mappings where created_at is not null and created_at < :cutoff " +
        "order by created_at limit :limit", nativeQuery = true)
    int deleteExpired(@Param("cutoff") long cutoff, @Param("limit") int limit);
}
