package cn.wildfirechat.bridge.pojo;

/**
 * 管理端创建/修改域时使用的白名单 DTO。
 * 不接受 mySecret，避免客户端覆盖本域给对端的密钥。
 */
public class DomainRequest {
    public String domainId;
    public String secret;
    public String url;
    public String detailInfo;
    public String email;
    public String tel;
    public String name;
    public String address;
    public String extra;
}
