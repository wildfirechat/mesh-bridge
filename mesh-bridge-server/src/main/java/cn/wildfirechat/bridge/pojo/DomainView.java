package cn.wildfirechat.bridge.pojo;

import cn.wildfirechat.bridge.jpa.Domain;

/**
 * 管理端返回的域信息。列表接口不返回任何密钥；单条查询才返回己方密钥，
 * 且不返回对端密钥，避免批量泄露。
 */
public class DomainView {
    public String domainId;
    public String url;
    public String detailInfo;
    public String email;
    public String tel;
    public String name;
    public String address;
    public String extra;
    public String mySecret;

    public static DomainView from(Domain domain, boolean includeMySecret) {
        DomainView view = new DomainView();
        view.domainId = domain.domainId;
        view.url = domain.url;
        view.detailInfo = domain.detailInfo;
        view.email = domain.email;
        view.tel = domain.tel;
        view.name = domain.name;
        view.address = domain.address;
        view.extra = domain.extra;
        view.mySecret = includeMySecret ? domain.mySecret : null;
        return view;
    }
}
