package cn.wildfirechat.bridge.utilis;

public class DomainIdUtils {
    private static String domainDivider = "@";

    public static void setDomainDivider(String domainDivider) {
        DomainIdUtils.domainDivider = domainDivider;
    }

    //当请求对方时，需要转换ID。如果是带有域的ID：当域为对方域时，去掉域；当域非对方域时保留域。如果不带域，需要加上自己的域。
    //用在请求对方时。
    public static String toExternalId(String remoteDomainId, String targetId, String localDomainId) {
        if(isExternalId(targetId)) {
            String domainId = getExternalId(targetId);
            if(remoteDomainId.equals(domainId)) {
                return getRawId(targetId);
            }
            return targetId;
        } else {
            return targetId + domainDivider + localDomainId;
        }
    }

    //当请求对方返回时，需要转换ID。如果不带域，需要加上对方的域。如果是带有域的ID：当域为自己域时，去掉域；当域非己方域时保留域。
    //用在请求对方返回时。
    public static String toInternalId(String remoteDomainId, String targetId, String localDomainId) {
        if(isExternalId(targetId)) {
            String domainId = getExternalId(targetId);
            if(localDomainId.equals(domainId)) {
                return getRawId(targetId);
            }
            return targetId;
        } else {
            return targetId + domainDivider + remoteDomainId;
        }
    }

    public static boolean isExternalId(String targetId) {
        if(targetId.contains(domainDivider)) {
            return true;
        }
        return false;
    }

    public static String getExternalId(String targetId) {
        String[] ss = targetId.split(domainDivider);
        return ss[1];
    }

    private static String getRawId(String targetId) {
        String[] ss = targetId.split(domainDivider);
        return ss[0];
    }
}
