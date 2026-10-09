package cn.wildfirechat.bridge.jpa;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface OutMessageIdsRepository extends CrudRepository<OutMessageIds, OutMessageIdsKey> {

    Optional<OutMessageIds> findByMessageIdAndToDomainId(long messageId, String toDomainId);

    Optional<OutMessageIds> findByToDomainIdAndToMessageId(String toDomainId, long toMessageId);
}
