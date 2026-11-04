package cn.wildfirechat.bridge.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "media_url_mappings",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_media_local", columnNames = {"domain_id", "local_url_hash"}),
        @UniqueConstraint(name = "uk_media_remote", columnNames = {"domain_id", "remote_url_hash"})
    },
    indexes = {
        //按保留期清理时要按 created_at 过滤，没有索引会全表扫描
        @Index(name = "idx_media_created_at", columnList = "created_at")
    })
public class MediaUrlMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "domain_id", nullable = false, length = 64)
    public String domainId;

    @Column(name = "local_url_hash", nullable = false, length = 64)
    public String localUrlHash;

    @Column(name = "local_media_url", nullable = false, length = 4096)
    public String localMediaUrl;

    @Column(name = "remote_url_hash", nullable = false, length = 64)
    public String remoteUrlHash;

    @Column(name = "remote_media_url", nullable = false, length = 4096)
    public String remoteMediaUrl;

    //记录创建时间(毫秒)，用于按保留期清理历史映射。允许为空，兼容已经建过表的环境。
    @Column(name = "created_at")
    public Long createdAt;
}
