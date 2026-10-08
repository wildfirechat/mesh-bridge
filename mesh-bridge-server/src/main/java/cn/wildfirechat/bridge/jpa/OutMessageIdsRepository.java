package cn.wildfirechat.bridge.jpa;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface OutMessageIdsRepository extends CrudRepository<OutMessageIds, Long> {

    Optional<OutMessageIds> findByToDomainIdAndToMessageId(String toDomainId, long toMessageId);
}
