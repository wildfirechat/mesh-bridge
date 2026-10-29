package cn.wildfirechat.bridge.jpa;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public class InMessageIdsKey implements Serializable {
    @Column(name = "message_id")
    public long messageId;

    @Column(name = "domain_id", length = 64)
    public String domainId;

    public InMessageIdsKey() {
    }

    public InMessageIdsKey(long messageId, String domainId) {
        this.messageId = messageId;
        this.domainId = domainId;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }
}
