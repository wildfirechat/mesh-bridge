package cn.wildfirechat.bridge.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "out_message_ids")
public class OutMessageIds {
    @Id
    @Column(name = "id")
    public long messageId;

    public String toDomainId;

    public long toMessageId;

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

    public long getToMessageId() {
        return toMessageId;
    }

    public void setToMessageId(long toMessageId) {
        this.toMessageId = toMessageId;
    }
}
