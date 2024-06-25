package cn.wildfirechat.bridge.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "domain")
public class Domain {
    //对方id
    @Id
    @Column(name = "id", length = 64)
    public String domainId;

    //对方的密钥
    @Column(length = 64)
    public String secret;

    //https://mesh.im.wildfirechat.net:8200/api
    @Column(length = 1024)
    public String url;

    @Column(length = 256)
    public String detailInfo;

    @Column(length = 64)
    public String email;

    @Column(length = 64)
    public String tel;

    @Column(length = 64)
    public String name;

    @Column(length = 64)
    public String address;

    @Column(length = 1024)
    public String extra;

    //我方密钥
    @Column(length = 128)
    public String mySecret;
}
