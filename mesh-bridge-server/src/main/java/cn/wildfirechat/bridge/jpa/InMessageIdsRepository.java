package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InMessageIdsRepository extends CrudRepository<InMessageIds, InMessageIdsKey> {

    @Query(value = "select message_id from in_message_ids where domain_id = ?1 and local_message_id = ?2 limit 1", nativeQuery = true)
    Optional<Long> findByDomainIdAndLocalMessageId(String domainId, long localMessageId);

    //组合消息批量转换用：一次查出多个本域消息在原域的ID，避免每个子消息查一次
    @Query("select i from InMessageIds i where i.id.domainId = :domainId and i.localMessageId in :localMessageIds")
    List<InMessageIds> findByLocalMessageIds(@Param("domainId") String domainId, @Param("localMessageIds") Collection<Long> localMessageIds);
}
