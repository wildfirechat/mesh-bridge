package cn.wildfirechat.bridge.utilis;

import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

//文件传输时把"上传方的原始媒体地址"带给接收方的header。
//接收方据此保存一条 本域地址 <-> 来源域地址 的映射，这样同一个文件再发回来源域时可以直接复用来源域的原地址，不用重新上传。
public final class MediaTransferUtils {
    public static final String SOURCE_MEDIA_URL_HEADER = "x-source-media-url";

    //header值会占用HTTP头空间(Tomcat默认8KB)，超长地址不传，接收方只是少一条反向映射，不影响文件传输
    private static final int MAX_ENCODED_LENGTH = 4096;
    private static final int MAX_URL_LENGTH = 4096;

    private MediaTransferUtils() {
    }

    //地址需要percent编码后再放进header：header只允许可见ASCII字符，直接放带中文/空格的地址会被OkHttp拒绝
    public static String encodeSourceUrl(String mediaUrl) {
        if(!StringUtils.hasText(mediaUrl) || mediaUrl.length() > MAX_URL_LENGTH || !isHttpUrl(mediaUrl)) {
            return null;
        }
        String encoded = URLEncoder.encode(mediaUrl, StandardCharsets.UTF_8);
        return encoded.length() > MAX_ENCODED_LENGTH ? null : encoded;
    }

    //解析对端带来的原始地址。不是http/https、超长、编码非法时返回null，调用方忽略即可
    public static String decodeSourceUrl(String headerValue) {
        if(!StringUtils.hasText(headerValue) || headerValue.length() > MAX_ENCODED_LENGTH) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(headerValue, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if(decoded.length() > MAX_URL_LENGTH || !isHttpUrl(decoded)) {
            return null;
        }
        return decoded;
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
