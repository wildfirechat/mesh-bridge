package cn.wildfirechat.bridge.jpa;


import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(name = "in_message_ids", indexes = {@Index(name = "idx_domain_local_msg", columnList = "domainId, localMessageId")})
public class InMessageIds {
    @EmbeddedId
    public InMessageIdsKey id;

    public long localMessageId;

    public InMessageIdsKey getId() {
        return id;
    }

    public void setId(InMessageIdsKey id) {
        this.id = id;
    }

    public long getLocalMessageId() {
        return localMessageId;
    }

    public void setLocalMessageId(long localMessageId) {
        this.localMessageId = localMessageId;
    }
}
