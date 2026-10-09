package cn.wildfirechat.bridge.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;

//一条本域消息可能被 publish 到多个远端域，每个域会分配不同的对端消息ID，
//所以主键必须是 (messageId, toDomainId) 的组合，不能只用 messageId。
@Entity
@Table(name = "out_message_ids", indexes = {
    //消息被对端 publish 回本域时，会按 (to_domain_id, to_message_id) 反查，加索引避免全表扫描
    @Index(name = "idx_out_msg_to_domain_msg", columnList = "to_domain_id,to_message_id")
})
@IdClass(OutMessageIdsKey.class)
public class OutMessageIds {
    @Id
    @Column(name = "id")
    public long messageId;

    @Id
    @Column(name = "to_domain_id", length = 64)
    public String toDomainId;

    @Column(name = "to_message_id")
    public long toMessageId;

    //本域保存的原始媒体地址。当消息被对端 publish 回本域时，用它还原成本域可访问的地址。
    //预签名地址可能很长，这里给足够的长度，避免 Data too long。
    @Column(name = "media_url", length = 1024)
    public String mediaUrl;

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

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }
}
