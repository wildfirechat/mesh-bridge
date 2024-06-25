package cn.wildfirechat.bridge.jpa;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface InMessageIdsRepository extends CrudRepository<InMessageIds, InMessageIdsKey> {

    @Query(value = "select message_id from in_message_ids where domain_id = ?1 and local_message_id = ?2 limit 1", nativeQuery = true)
    Optional<Long> findByDomainIdAndLocalMessageId(String domainId, long localMessageId);
}
