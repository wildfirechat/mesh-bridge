package cn.wildfirechat.bridge.jpa;

import java.io.Serializable;

public class InMessageIdsKey implements Serializable {
    public long messageId;
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