package cn.wildfirechat.bridge.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

//这里只用 JPQL/派生查询，不写原生 SQL，由 Hibernate 按方言生成，保证 MySQL/PostgreSQL 等数据库都能用
public interface MediaUrlMappingRepository extends CrudRepository<MediaUrlMapping, Long> {
    Optional<MediaUrlMapping> findByDomainIdAndLocalUrlHash(String domainId, String localUrlHash);

    Optional<MediaUrlMapping> findByDomainIdAndRemoteUrlHash(String domainId, String remoteUrlHash);

    //组合消息批量查询映射用，避免每个子消息查一次
    List<MediaUrlMapping> findByDomainIdAndLocalUrlHashIn(String domainId, Collection<String> localUrlHashes);

    List<MediaUrlMapping> findByDomainIdAndRemoteUrlHashIn(String domainId, Collection<String> remoteUrlHashes);

    //按保留期分批清理：先按创建时间取一批过期记录的 ID（分页由方言生成 limit/fetch first），再按 ID 删除
    @Query("select m.id from MediaUrlMapping m where m.createdAt is not null and m.createdAt < :cutoff order by m.createdAt")
    List<Long> findExpiredIds(@Param("cutoff") long cutoff, Pageable pageable);

    //每个批次单独一个事务，避免整个清理任务一个大事务长时间持锁
    @Transactional
    @Modifying
    @Query("delete from MediaUrlMapping m where m.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
