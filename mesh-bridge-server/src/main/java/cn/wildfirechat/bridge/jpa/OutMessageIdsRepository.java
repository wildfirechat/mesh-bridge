package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutMessageIdsRepository extends CrudRepository<OutMessageIds, OutMessageIdsKey> {

    Optional<OutMessageIds> findByMessageIdAndToDomainId(long messageId, String toDomainId);

    Optional<OutMessageIds> findByToDomainIdAndToMessageId(String toDomainId, long toMessageId);

    //组合消息批量转换用：一次查出多个本域消息在目标域的ID，避免每个子消息查一次
    @Query("select o from OutMessageIds o where o.toDomainId = :domainId and o.messageId in :messageIds")
    List<OutMessageIds> findByMessageIds(@Param("domainId") String domainId, @Param("messageIds") Collection<Long> messageIds);

    //组合消息批量恢复用：一次按对端消息ID反查多个本域消息ID
    @Query("select o from OutMessageIds o where o.toDomainId = :domainId and o.toMessageId in :toMessageIds")
    List<OutMessageIds> findByToMessageIds(@Param("domainId") String domainId, @Param("toMessageIds") Collection<Long> toMessageIds);
}
