package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface OutMessageIdsRepository extends CrudRepository<OutMessageIds, Long> {

    @Query(value = "select id from out_message_ids where to_domain_id = ?1 and to_message_id = ?2 limit 1", nativeQuery = true)
    Optional<Long> findByDomainIdAndToMessageId(String domainId, long toMessageId);
}
