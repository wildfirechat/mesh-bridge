package cn.wildfirechat.bridge.jpa;

import org.springframework.data.repository.CrudRepository;

public interface ShiroSessionRepository extends CrudRepository<ShiroSession, String> {
}
