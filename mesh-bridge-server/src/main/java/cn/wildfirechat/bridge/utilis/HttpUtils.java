/*
 * This file is part of the Wildfire Chat package.
 * (c) Heavyrain2012 <heavyrain.lee@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package cn.wildfirechat.bridge.utilis;

import cn.wildfirechat.bridge.jpa.Domain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class HttpUtils {
    private static final Logger LOG = LoggerFactory.getLogger(HttpUtils.class);
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public static final Map<String, OkHttpClient> httpClientMap = new ConcurrentHashMap<>();

    private static volatile long responseMaxBytes = 1024 * 1024;
    private static volatile boolean allowHttpDomains = false;

    private static String myDomainId;

    public static void setMyDomainId(String myDomainId) {
        HttpUtils.myDomainId = myDomainId;
    }

    public static void setResponseMaxBytes(long maxBytes) {
        if (maxBytes > 0) {
            HttpUtils.responseMaxBytes = maxBytes;
        }
    }

    public static void setAllowHttpDomains(boolean allow) {
        HttpUtils.allowHttpDomains = allow;
    }

    public interface HttpCallback {
        void onSuccess(String content);
        void onFailure(int statusCode, String errorMessage);
    }

    private static String joinUrl(String base, String path) {
        if (!StringUtils.hasText(base)) {
            throw new IllegalArgumentException("domain url is empty");
        }
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String fullUrl = url + path;
        URI uri = URI.create(fullUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"https".equals(scheme) && !(allowHttpDomains && "http".equals(scheme))) {
            throw new IllegalArgumentException("peer domain url must use https");
        }
        return fullUrl;
    }

    private static Headers getSignHeaders(Domain domain,
                                          String method,
                                          String url,
                                          byte[] body,
                                          long contentLength,
                                          String contentType,
                                          Map<String, String> extraHeaders) {
        if (!StringUtils.hasText(myDomainId)) {
            throw new RuntimeException("HttpUtils没有正确初始化，需要设置本方domainId");
        }
        if (domain == null || !StringUtils.hasText(domain.secret)) {
            throw new RuntimeException("domain secret is empty");
        }

        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis();

        URI uri = URI.create(url);
        String path = uri.getRawPath();
        if (!StringUtils.hasText(path)) {
            path = "/";
        }
        String query = uri.getRawQuery();

        String mediaType = extraHeaders == null ? "" : extraHeaders.getOrDefault("x-media-type", "");
        String fileExtension = extraHeaders == null ? "" : extraHeaders.getOrDefault("x-file-extension", "");
        String bodySha256 = body == null ? "-" : SignatureUtils.sha256Hex(body);

        String canonical = SignatureUtils.canonical(
            method,
            path,
            query,
            contentLength,
            bodySha256,
            contentType,
            mediaType,
            fileExtension,
            myDomainId,
            timestamp + "",
            nonce);
        String sign = SignatureUtils.hmacSha256Hex(domain.secret, canonical);

        Headers.Builder builder = new Headers.Builder()
            .add("nonce", nonce)
            .add("timestamp", timestamp + "")
            .add("sign", sign)
            .add("x-domain-id", myDomainId);
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::add);
        }
        return builder.build();
    }

    public static void httpPostToDomain(Domain domain, String path, final Object object, final HttpCallback httpCallback) {
        String jsonStr;
        if (object instanceof String) {
            jsonStr = (String) object;
        } else {
            jsonStr = gson.toJson(object);
        }
        byte[] body = jsonStr.getBytes(StandardCharsets.UTF_8);
        String url = joinUrl(domain.url, path);
        Headers headers = getSignHeaders(domain, "POST", url, body, body.length, JSON.toString(), null);
        OkHttpClient httpClient = httpClientMap.computeIfAbsent(domain.domainId, integer -> new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60))
            .followRedirects(false)
            .followSslRedirects(false)
            .build());
        httpCallbackJsonPost(url, jsonStr, headers, httpCallback, httpClient);
    }

    //以流的形式POST文件到对端域，body可以是边下边传的流式RequestBody。文件可能比较大，读写超时单独设置
    public static String httpPostStreamToDomain(Domain domain, String path, Map<String, String> extraHeaders, RequestBody body, int timeoutMinutes) throws IOException {
        String url = joinUrl(domain.url, path);
        String contentType = body.contentType() == null ? "" : body.contentType().toString();
        long contentLength = body.contentLength();
        Headers headers = getSignHeaders(domain, "POST", url, null, contentLength, contentType, extraHeaders);
        OkHttpClient baseClient = httpClientMap.computeIfAbsent(domain.domainId, integer -> new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(false)
            .followSslRedirects(false)
            .build());
        OkHttpClient httpClient = baseClient.newBuilder()
            .readTimeout(Duration.ofMinutes(timeoutMinutes))
            .writeTimeout(Duration.ofMinutes(timeoutMinutes))
            .callTimeout(Duration.ofMinutes(timeoutMinutes + 1L))
            .build();
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .headers(headers)
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 200 && response.body() != null) {
                return readBody(response.body());
            }
            throw new IOException("POST to " + url + " failure, status code:" + response.code());
        }
    }

    private static void httpCallbackJsonPost(final String url, final String jsonStr, final Headers headers, final HttpCallback httpCallback, final OkHttpClient client) {
        //只记录 URL 和长度，避免把消息内容写进日志
        LOG.info("POST to {} with {} bytes", url, jsonStr == null ? 0 : jsonStr.length());
        if (!StringUtils.hasText(url)) {
            LOG.error("http post failure with empty url");
            return;
        }

        if (httpCallback == null && client.dispatcher().queuedCallsCount() > 100000) {
            LOG.error("Http post queue size exceed! Discord post request....");
            return;
        }

        RequestBody body = RequestBody.create(JSON, jsonStr);
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .headers(headers)
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOG.error("POST to {} failed: {}", url, e.getMessage());
                if (httpCallback != null) {
                    httpCallback.onFailure(-1, e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (httpCallback != null) {
                        int code = response.code();
                        if (code == 200) {
                            String content = response.body() == null ? null : readBody(response.body());
                            httpCallback.onSuccess(content);
                        } else {
                            httpCallback.onFailure(code, response.message());
                        }
                    }
                } catch (Exception e) {
                    LOG.error("read response from {} failed: {}", url, e.getMessage());
                    if (httpCallback != null) {
                        httpCallback.onFailure(-1, e.getMessage());
                    }
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });
    }

    private static String readBody(ResponseBody body) throws IOException {
        long contentLength = body.contentLength();
        if (contentLength > responseMaxBytes) {
            throw new IOException("response body too large: " + contentLength);
        }
        try (InputStream input = body.byteStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(contentLength > 0 ? (int) Math.min(contentLength, 8192) : 256);
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > responseMaxBytes) {
                    throw new IOException("response body too large");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
