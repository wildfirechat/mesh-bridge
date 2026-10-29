package cn.wildfirechat.bridge.jpa;


import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(name = "in_message_ids", indexes = {@Index(name = "idx_domain_local_msg", columnList = "domain_id,local_message_id")})
public class InMessageIds {
    @EmbeddedId
    public InMessageIdsKey id;

    @Column(name = "local_message_id")
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
