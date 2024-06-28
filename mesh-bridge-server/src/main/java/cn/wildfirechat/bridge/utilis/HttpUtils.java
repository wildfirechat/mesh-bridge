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
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class HttpUtils {
    private static final Logger LOG = LoggerFactory.getLogger(HttpUtils.class);
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public static final Map<String, OkHttpClient> httpClientMap = new ConcurrentHashMap<>();

    private static String myDomainId;

    public static void setMyDomainId(String myDomainId) {
        HttpUtils.myDomainId = myDomainId;
    }

    public interface HttpCallback {
        void onSuccess(String content);
        void onFailure(int statusCode, String errorMessage);
    }

    private static Headers getSignHeaders(Domain domain) {
        if(!StringUtils.hasText(myDomainId)) {
            throw new RuntimeException("HttpUtils没有正确初始化，需要设置本方domainId");
        }
        int nonce = (int)(Math.random() * 100000 + 3);
        long timestamp = System.currentTimeMillis();
        String str = nonce + "|" + domain.secret + "|" + timestamp;
        String sign = DigestUtils.sha1Hex(str);
        Headers headers = new Headers.Builder().add("nonce", nonce+"").add("timestamp", ""+timestamp).add("sign", sign).add("x-domain-id", myDomainId +"").build();
        return headers;
    }

    public static void httpPostToDomain(Domain domain, String path, final Object object, final HttpCallback httpCallback) {
        String jsonStr;
        if(object instanceof String) {
            jsonStr = (String) object;
        } else {
            jsonStr = gson.toJson(object);
        }
        String url = domain.url + path;
        Headers headers = getSignHeaders(domain);
        OkHttpClient httpClient = httpClientMap.computeIfAbsent(domain.domainId, integer -> new OkHttpClient(new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(20))));
        httpCallbackJsonPost(url, jsonStr, headers, httpCallback, httpClient);
    }

    private static void httpCallbackJsonPost(final String url, final String jsonStr, final Headers headers, final HttpCallback httpCallback, final OkHttpClient client) {
        //消息推送内容为 {"sender":"uCGUxUaa","senderName":"杨","convType":0,"target":"usq7v7UU","targetName":"鞋子","userId":"usq7v7UU","line":0,"cntType":400,"serverTime":1610590766485,"pushMessageType":1,"pushType":2,"pushContent":"","pushData":"","unReceivedMsg":1,"mentionedType":0,"packageName":"cn.wildfirechat.chat","deviceToken":"AFoieP9P6u6CccIkRK23gRwUJWKqSkdiqnb-6gC1kL7Wv-9XNoEYBPU7VsINU_q8_WTKfafe35qWu7ya7Z-NmgOTX9XVW3A3zd6ilh--quj6ccINXRvVnh8QmI9QQ","isHiddenDetail":false,"language":"zh"}
        //推送信息只打印前100个字符，防止敏感信息打印到日志中去。
        LOG.info("POST to {} with data {}...", url, jsonStr.substring(0, Math.min(jsonStr.length(), 100)));
        if (!StringUtils.hasText(url)) {
            LOG.error("http post failure with empty url");
            return;
        }

        if(httpCallback == null && client.dispatcher().queuedCallsCount() > 100000) {
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
                System.out.println("Http请求回调地址失败，请检查IM服务配置文件中对应的回调地址是否正确");
                e.printStackTrace();
                LOG.error("Http请求回调地址失败，请检查IM服务配置文件中对应的回调地址是否正确");
                LOG.error("POST to {} failed", url);
                if(httpCallback != null) {
                    httpCallback.onFailure(-1, e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                LOG.info("POST to {} success with response: {}", url, response.body());
                try {
                    if(httpCallback != null) {
                        int code = response.code();
                        if(code == 200) {
                            if(response.body() != null && response.body().contentLength() > 0) {
                                httpCallback.onSuccess(response.body().string());
                            } else {
                                httpCallback.onSuccess(null);
                            }
                        } else {
                            httpCallback.onFailure(code, response.message());
                        }
                    }

                    if (response.body() != null) {
                        response.body().close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
