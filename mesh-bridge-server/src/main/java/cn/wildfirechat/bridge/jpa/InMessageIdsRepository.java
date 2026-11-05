package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InMessageIdsRepository extends CrudRepository<InMessageIds, InMessageIdsKey> {

    //用 JPQL 而不是原生 SQL（原来的 select ... limit 1 只有 MySQL/PostgreSQL 支持），
    //由 Hibernate 按数据库方言生成 SQL，MySQL/PostgreSQL/达梦/金仓都能用。
    @Query("select i.id.messageId from InMessageIds i where i.id.domainId = :domainId and i.localMessageId = :localMessageId")
    List<Long> findMessageIdsByLocalMessageId(@Param("domainId") String domainId, @Param("localMessageId") long localMessageId);

    //(domain_id, local_message_id) 上有索引，正常只会有一条记录，取第一条即可
    default Optional<Long> findByDomainIdAndLocalMessageId(String domainId, long localMessageId) {
        List<Long> messageIds = findMessageIdsByLocalMessageId(domainId, localMessageId);
        return messageIds.isEmpty() ? Optional.empty() : Optional.of(messageIds.get(0));
    }

    //组合消息批量转换用：一次查出多个本域消息在原域的ID，避免每个子消息查一次
    @Query("select i from InMessageIds i where i.id.domainId = :domainId and i.localMessageId in :localMessageIds")
    List<InMessageIds> findByLocalMessageIds(@Param("domainId") String domainId, @Param("localMessageIds") Collection<Long> localMessageIds);
}
