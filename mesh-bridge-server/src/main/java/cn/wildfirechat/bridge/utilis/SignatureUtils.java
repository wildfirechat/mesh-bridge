package cn.wildfirechat.bridge.utilis;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 桥接服务请求签名工具。
 *
 * 签名覆盖 method/path/query/contentLength/body hash/contentType/业务头/域/时间戳/nonce，
 * 防止请求被重放、篡改或把签名挪到其他接口使用。
 */
public final class SignatureUtils {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SignatureUtils() {
    }

    public static String canonical(String method,
                                   String path,
                                   String query,
                                   long contentLength,
                                   String bodySha256,
                                   String contentType,
                                   String mediaType,
                                   String fileExtension,
                                   String domainId,
                                   String timestamp,
                                   String nonce) {
        // 字段顺序必须与 HttpUtils 生成签名时完全一致
        return nullToEmpty(method) + "\n"
            + nullToEmpty(path) + "\n"
            + nullToEmpty(query) + "\n"
            + contentLength + "\n"
            + nullToEmpty(bodySha256) + "\n"
            + normalizeContentType(contentType) + "\n"
            + nullToEmpty(mediaType) + "\n"
            + nullToEmpty(fileExtension) + "\n"
            + nullToEmpty(domainId) + "\n"
            + nullToEmpty(timestamp) + "\n"
            + nullToEmpty(nonce);
    }

    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            chars[i * 2] = HEX[b >>> 4];
            chars[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(chars);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeContentType(String value) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf(';');
        String base = index >= 0 ? value.substring(0, index) : value;
        return base.trim().toLowerCase();
    }
}
