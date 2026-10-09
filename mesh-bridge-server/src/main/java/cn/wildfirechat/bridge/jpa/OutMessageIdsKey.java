package cn.wildfirechat.bridge.jpa;

import java.io.Serializable;
import java.util.Objects;

//OutMessageIds 的复合主键：本域消息ID + 对端域ID。
public class OutMessageIdsKey implements Serializable {

    private long messageId;

    private String toDomainId;

    public OutMessageIdsKey() {
    }

    public OutMessageIdsKey(long messageId, String toDomainId) {
        this.messageId = messageId;
        this.toDomainId = toDomainId;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public String getToDomainId() {
        return toDomainId;
    }

    public void setToDomainId(String toDomainId) {
        this.toDomainId = toDomainId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OutMessageIdsKey that = (OutMessageIdsKey) o;
        return messageId == that.messageId && Objects.equals(toDomainId, that.toDomainId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, toDomainId);
    }
}
