package cn.wildfirechat.bridge.utilis;

public class DomainIdUtils {
    private static String domainDivider = "@";

    public static void setDomainDivider(String domainDivider) {
        if (domainDivider == null || domainDivider.isEmpty()) {
            throw new IllegalArgumentException("domain divider must not be empty");
        }
        DomainIdUtils.domainDivider = domainDivider;
    }

    //当请求对方时，需要转换ID。如果是带有域的ID：当域为对方域时，去掉域；当域非对方域时保留域。如果不带域，需要加上自己的域。
    //用在请求对方时。
    public static String toExternalId(String remoteDomainId, String targetId, String localDomainId) {
        if (targetId == null) {
            return null;
        }
        if (isExternalId(targetId)) {
            String domainId = getExternalId(targetId);
            if (remoteDomainId.equals(domainId)) {
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
        if (targetId == null) {
            return null;
        }
        if (isExternalId(targetId)) {
            String domainId = getExternalId(targetId);
            if (localDomainId.equals(domainId)) {
                return getRawId(targetId);
            }
            return targetId;
        } else {
            return targetId + domainDivider + remoteDomainId;
        }
    }

    public static boolean isExternalId(String targetId) {
        if (targetId == null) {
            return false;
        }
        int index = targetId.lastIndexOf(domainDivider);
        return index > 0 && index + domainDivider.length() < targetId.length();
    }

    public static String getExternalId(String targetId) {
        int index = targetId == null ? -1 : targetId.lastIndexOf(domainDivider);
        if (index <= 0 || index + domainDivider.length() >= targetId.length()) {
            throw new IllegalArgumentException("invalid external id");
        }
        return targetId.substring(index + domainDivider.length());
    }

    private static String getRawId(String targetId) {
        int index = targetId.lastIndexOf(domainDivider);
        if (index <= 0) {
            throw new IllegalArgumentException("invalid external id");
        }
        return targetId.substring(0, index);
    }

    public static boolean isSameExternalDomain(String targetId1, String targetId2) {
        if (!isExternalId(targetId1) || !isExternalId(targetId2)) {
            return false;
        } else {
            String domainId1 = getExternalId(targetId1);
            String domainId2 = getExternalId(targetId2);
            return domainId1.equals(domainId2);
        }
    }
}
