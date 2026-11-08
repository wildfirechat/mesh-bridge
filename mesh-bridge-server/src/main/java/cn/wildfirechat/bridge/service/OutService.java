package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.*;
import cn.wildfirechat.bridge.utilis.CompositeMessageUtils;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.bridge.utilis.HttpUtils;
import cn.wildfirechat.bridge.utilis.LimitedInputStream;
import cn.wildfirechat.bridge.utilis.MediaTransferUtils;
import cn.wildfirechat.pojos.*;
import cn.wildfirechat.pojos.mesh.*;
import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.sdk.messagecontent.MessageContent;
import cn.wildfirechat.sdk.messagecontent.MessageContentFactory;
import cn.wildfirechat.sdk.messagecontent.TextMessageContent;
import cn.wildfirechat.sdk.utilities.JsonUtils;
import com.google.gson.*;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.apache.http.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.wildfirechat.pojos.mesh.MeshRestResult.MeshRestCode.*;

@Service
public class OutService {
    private static final Logger LOG = LoggerFactory.getLogger(OutService.class);

    //uid 批量查询时一次最多带多少个消息ID，避免 in 列表过长
    private static final int MESSAGE_ID_BATCH_SIZE = 500;

    @Value("${bridge.my_domain_id}")
    String myDomainId;

    @Value("${bridge.domain_divider}")
    String domainDivider;

    @Value("${bridge.media_public_accessible:true}")
    boolean mediaPublicAccessible;

    @Value("${bridge.file_transfer_timeout_minutes:10}")
    int fileTransferTimeoutMinutes;

    //文件传输的最大文件大小(MB)，0表示不限制
    @Value("${bridge.file_transfer_max_size_mb:100}")
    long fileTransferMaxSizeMb;

    //组合消息大文件(remoteMediaUrl 指向的JSON)的最大大小(MB)，0表示不限制。
    //组合文件需要整个读进内存解析成JSON再改写，不能沿用普通文件的100MB上限。
    @Value("${bridge.composite_file_max_size_mb:10}")
    long compositeFileMaxSizeMb;

    //文件传输并发线程数
    @Value("${bridge.file_transfer_threads:4}")
    int fileTransferThreads;

    //允许下载的媒体地址主机白名单(逗号分隔)。为空时只允许本域配置的 media.bucket_*_domain。
    @Value("${bridge.media_download_host_whitelist:}")
    String mediaDownloadHostWhitelist;

    @Value("${bridge.allow_http_domains:false}")
    boolean allowHttpDomains;

    @Value("${bridge.media_download_allow_private_hosts:false}")
    boolean allowPrivateMediaHosts;

    @Value("${minio.endpoint}")
    String minioEndpoint;

    @Value("${minio.access_key}")
    String minioAccessKey;

    @Value("${minio.secret_key}")
    String minioSecretKey;

    @Autowired
    Environment environment;

    MinioClient minioClient;

    OkHttpClient fileHttpClient;

    //文件传输专用线程池，避免大文件传输长时间占用公共线程池。使用有界队列，队列满时直接失败而不是无限堆积。
    ExecutorService transferFileExecutor;

    @Autowired
    MediaUrlMappingService mediaUrlMappingService;

    @Autowired
    OutMessageIdsRepository outMessageIdsRepository;

    @Autowired
    InMessageIdsRepository inMessageIdsRepository;

    @Autowired
    DomainRepository domainRepository;

    @PostConstruct
    void init() {
        HttpUtils.setMyDomainId(myDomainId);
        DomainIdUtils.setDomainDivider(domainDivider);
        fileHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofMinutes(fileTransferTimeoutMinutes))
            .callTimeout(Duration.ofMinutes(fileTransferTimeoutMinutes + 1L))
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        int threads = fileTransferThreads > 0 ? fileTransferThreads : 4;
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "mesh-file-transfer-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        transferFileExecutor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        minioClient = MinioClient.builder().endpoint(minioEndpoint).credentials(minioAccessKey, minioSecretKey).build();
    }

    //send_message/publish_message 内部可能先同步传输媒体文件，整个过程可能远超默认的30秒异步超时，
    //这里给这两个接口单独设置更长的超时，避免请求先超时失败而后台仍然把消息发出去。
    private DeferredResult<String> newTransferAwareDeferredResult() {
        return new DeferredResult<>((fileTransferTimeoutMinutes + 1L) * 60L * 1000L);
    }

    public Object onPing(String domainId) {
        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoDomainPingRequest request = new PojoDomainPingRequest();
        request.domainId = myDomainId;
        HttpUtils.httpPostToDomain(domain, "/ping", request, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http访问对端不通，对端地址:" + domain.url + "，状态码:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onSearchUser(String domainId, String keyword, int searchType, int userType, int page) {
        if(!StringUtils.hasText(keyword)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoSearchUserReq req = new PojoSearchUserReq();
        req.keyword = keyword;
        req.searchType = searchType;
        req.page = page;
        req.userType = userType;
        req.domainId = myDomainId;
        HttpUtils.httpPostToDomain(domain, "/search_user", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<PojoSearchUserRes> meshRestResult = JsonUtils.fromJsonObject2(content, PojoSearchUserRes.class);
                if(meshRestResult.getResult() != null) {
                    for (InputOutputUserInfo userInfo : ((PojoSearchUserRes) meshRestResult.getResult()).userInfos) {
                        userInfo.setUserId(DomainIdUtils.toInternalId(domainId, userInfo.getUserId(), myDomainId));
                        userInfo.setName(DomainIdUtils.toInternalId(domainId, userInfo.getName(), myDomainId));
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onAddFriendRequest(String domainId, String fromUserId, String reason, String targetUid) {
        if(!StringUtils.hasText(fromUserId) || !StringUtils.hasText(targetUid)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        targetUid = DomainIdUtils.toExternalId(domainId, targetUid, myDomainId);

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoAddFriendReq req = new PojoAddFriendReq();
        req.fromUserId = DomainIdUtils.toExternalId(domainId, fromUserId, myDomainId);
        req.reason = reason;
        req.targetUserId = targetUid;
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/add_friend_request", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onHandleFriendRequest(String domainId, String fromUserId, int status, String targetUid) {
        if(!StringUtils.hasText(fromUserId) || !StringUtils.hasText(targetUid)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        targetUid = DomainIdUtils.toExternalId(domainId, targetUid, myDomainId);

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoHandleFriendRequestReq req = new PojoHandleFriendRequestReq();
        req.userId = DomainIdUtils.toExternalId(domainId, fromUserId, myDomainId);
        req.status = status;
        req.targetUserId = targetUid;
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/handle_friend_request", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onDeleteFriend(String domainId, String fromUserId, String friendUid) {
        if(!StringUtils.hasText(fromUserId) || !StringUtils.hasText(friendUid)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoDeleteFriend req = new PojoDeleteFriend();
        req.operator = DomainIdUtils.toExternalId(domainId, fromUserId, myDomainId);
        req.friendUid = DomainIdUtils.toExternalId(domainId, friendUid, myDomainId);;
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/delete_friend", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onSendMessageRequest(String domainId, long messageId, SendMessageData messageData, String sendClientId) {
        if(!StringUtils.hasText(messageData.getSender()) || !StringUtils.hasText(messageData.getConv().getTarget())) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        //消息ID映射要用转换前的本域发送者判断消息来源，不能传转换后的对方视角ID
        String originalSender = messageData.getSender();
        messageData.getConv().setTarget(DomainIdUtils.toExternalId(domainId, messageData.getConv().getTarget(), myDomainId));
        messageData.setSender(DomainIdUtils.toExternalId(domainId, originalSender, myDomainId));

        DeferredResult<String> deferredResult = newTransferAwareDeferredResult();

        String originalMediaUrl = messageData.getPayload().getRemoteMediaUrl();
        convertMessagePayloadDomainId(domainId, messageData.getPayload(), myDomainId, false, false, originalSender, new ConvertCallback() {
            @Override
            public void onSuccess() {
                PojoSendMessageReq req = new PojoSendMessageReq();
                req.messageData = messageData;
                req.clientId = sendClientId;
                req.domainId = myDomainId;
                req.messageId = messageId;
                HttpUtils.httpPostToDomain(domain, "/send_message", req, new HttpUtils.HttpCallback() {
                    @Override
                    public void onSuccess(String content) {
                        MeshRestResult<SendMessageResult> meshRestResult = JsonUtils.fromJsonObject2(content, SendMessageResult.class);
                        if(meshRestResult.getLocal_mesh_code() == 0 && meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0) {
                            // if ((messageData.getPayload().getPersistFlag() & 0x01) > 0) {
                            // 正常情况下，应该根据存储标记判断是否记录消息id对应关系，但server api很多情况下忽略了存储标记，导致server api发送的消息撤回/删除失败
                            // 所以这里把透传消息以外的所有消息都记录下来。
                            if (messageData.getPayload().getPersistFlag() != ProtoConstants.MessagePersistFlag.TRANSPARENT) {
                                OutMessageIds outMessageIds = new OutMessageIds();
                                outMessageIds.messageId = messageId;
                                outMessageIds.toDomainId = domainId;
                                outMessageIds.toMessageId = meshRestResult.getResult().getMessageUid();
                                outMessageIds.mediaUrl = originalMediaUrl;
                                outMessageIdsRepository.save(outMessageIds);
                                LOG.info("send out message success, from messageId {}, to messageId {}", messageId, meshRestResult.getResult().getMessageUid());
                            }
                        } else {
                            LOG.error("send out message failure! result: {}", content);
                        }
                        deferredResult.setResult(content);
                    }

                    @Override
                    public void onFailure(int statusCode, String errorMessage) {
                        MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                        deferredResult.setResult(meshRestResult.toString());
                    }
                });
            }

            @Override
            public void onFailure(int error_code) {
                LOG.error("onSendMessageRequest: convert message payload domain id error: {}", error_code);
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "convertMessagePayloadDomainId error code:" + error_code);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    private long convertMessageUid(String remoteDomainId, String sender, long messageUid) {
        Optional<Long> mappedMessageUid = findMappedMessageUid(remoteDomainId, sender, messageUid);
        if(mappedMessageUid.isPresent()) {
            return mappedMessageUid.get();
        }
        LOG.warn("Convert message uid {} to domain {} failed, no mapping found, sender {}", messageUid, remoteDomainId, sender);
        return 0;
    }

    //查找消息在目标域里的消息ID(messageUid)。找不到时返回empty，由调用方决定是置0还是保留原值。
    private Optional<Long> findMappedMessageUid(String remoteDomainId, String sender, long messageUid) {
        // 一条本域消息可能发往多个域，每个域的对端消息ID不同，必须带上目标域才能取到正确的映射。
        // 中继消息(外域用户发到本域、本域再转发到第三个域)在本域里也有发往目标域的记录，所以先查 out_message_ids，
        // 这样"publish 到第三个域"和"转发回来的消息再发出去"都能取到正确的ID。
        Optional<OutMessageIds> optionalOutMessageIds = outMessageIdsRepository.findByMessageIdAndToDomainId(messageUid, remoteDomainId);
        if(optionalOutMessageIds.isPresent()) {
            return Optional.of(optionalOutMessageIds.get().getToMessageId());
        }
        // 收到的消息：in_message_ids 里记录的是(来源域, 来源域消息ID) -> 本域消息ID，
        // 只有在目标域就是消息来源域(publish 回原域)时，才能用本域消息ID反查出原域消息ID。
        if(DomainIdUtils.isExternalId(sender)) {
            String senderDomainId = DomainIdUtils.getExternalId(sender);
            if(remoteDomainId.equals(senderDomainId)) {
                return inMessageIdsRepository.findByDomainIdAndLocalMessageId(senderDomainId, messageUid);
            }
        }
        return Optional.empty();
    }

    interface ConvertCallback {
        void onSuccess();
        void onFailure(int error_code);
    }

    //这里是出去的消息进行转换的地方，消息中包含的用户ID/群组ID/频道ID和消息ID等需要转换为对方视角的ID。
    //理论上所有二开只能局限于这个函数。如果有其他改动容易出问题。如果其他地方需要改动，请给我们提PR或者联系我们修改，确保其他部分保持一致。
    private void convertMessagePayloadDomainId(String remoteDomainId, MessagePayload messagePayload, String localDomainId, boolean isPublish, boolean isPublish2OriganlDomain, ConvertCallback callback) {
        convertMessagePayloadDomainId(remoteDomainId, messagePayload, localDomainId, isPublish, isPublish2OriganlDomain, null, callback);
    }

    private void convertMessagePayloadDomainId(String remoteDomainId, MessagePayload messagePayload, String localDomainId, boolean isPublish, boolean isPublish2OriganlDomain, String messageSender, ConvertCallback callback) {
        convertMessagePayloadDomainId(remoteDomainId, messagePayload, localDomainId, isPublish, isPublish2OriganlDomain, messageSender, 0, callback);
    }

    private void convertMessagePayloadDomainId(String remoteDomainId, MessagePayload messagePayload, String localDomainId, boolean isPublish, boolean isPublish2OriganlDomain, String messageSender, int depth, ConvertCallback callback) {
        if(depth > CompositeMessageUtils.MAX_NEST_DEPTH) {
            LOG.error("message nesting is too deep, depth {}, remoteDomainId {}", depth, remoteDomainId);
            callback.onFailure(ERROR_INVALID_PARAMETER.code);
            return;
        }
        if(messagePayload.getMentionedTarget() != null && !messagePayload.getMentionedTarget().isEmpty()) {
            List<String> converted = new ArrayList<>();
            for (String s : messagePayload.getMentionedTarget()) {
                converted.add(DomainIdUtils.toExternalId(remoteDomainId, s, localDomainId));
            }
            messagePayload.setMentionedTarget(converted);
        }

        if(messagePayload.getType() == 1) {
            MessageContent msgContent = MessageContentFactory.decodeMessageContent(messagePayload);
            if(msgContent instanceof TextMessageContent) {
                TextMessageContent txtContent = (TextMessageContent) msgContent;
                if(txtContent.getQuoteInfo() != null && txtContent.getQuoteInfo().getMessageUid() > 0) {
                    // 文本消息，有可能有引用，引用信息中包含消息ID，这个消息ID在每个域中都是不同的。也包含发送者用户ID。

                    // 替换消息ID
                    long targetMessageUid = convertMessageUid(remoteDomainId, txtContent.getQuoteInfo().getUserId(), txtContent.getQuoteInfo().getMessageUid());
                    txtContent.getQuoteInfo().setMessageUid(targetMessageUid);

                    // 发送者转换域地址。
                    txtContent.getQuoteInfo().setUserId(DomainIdUtils.toExternalId(remoteDomainId, txtContent.getQuoteInfo().getUserId(), localDomainId));

                    //设置到payload中
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(txtContent.getQuoteInfo().encode().toJSONString().getBytes(StandardCharsets.UTF_8)));
                }
            }
        } else if(messagePayload.getType() == 10) {
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null && replaceOutString(object, "f", remoteDomainId, localDomainId)) {
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
        } else if(messagePayload.getType() == 400) {
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                boolean changed = replaceStringList(object, "ts", remoteDomainId, localDomainId);
                //单人对讲时客户端还会写一个 t，和 ts[0] 相同；多人时 t 是第一个被叫
                changed |= replaceOutString(object, "t", remoteDomainId, localDomainId);
                if(changed) {
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
            convertCallPushData(remoteDomainId, messagePayload, localDomainId, false, messageSender);
        } else if(messagePayload.getType() == 406) {
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                replaceOutString(object, "initiator", remoteDomainId, localDomainId);
                replaceStringList(object, "participants", remoteDomainId, localDomainId);
                if(object.has("existParticipants") && object.get("existParticipants") instanceof JsonArray) {
                    JsonArray existParticipants = (JsonArray) object.get("existParticipants");
                    for (int i = 0; i < existParticipants.size(); i++) {
                        JsonObject participant = existParticipants.get(i).getAsJsonObject();
                        String newTarget = DomainIdUtils.toExternalId(remoteDomainId, participant.get("userId").getAsString(), localDomainId);
                        participant.add("userId", new JsonPrimitive(newTarget));
                    }
                }
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
            convertCallPushData(remoteDomainId, messagePayload, localDomainId, false, messageSender);
        } else if(messagePayload.getType() == 402) {
            //通话结束消息里 u 是邀请消息在本域的消息ID，需要换成目标域的消息ID
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null && object.has("u") && object.get("u").isJsonPrimitive()) {
                long inviteMessageUid = object.get("u").getAsLong();
                if(inviteMessageUid > 0) {
                    Optional<Long> mappedUid = findMappedMessageUid(remoteDomainId, messageSender, inviteMessageUid);
                    //有发送者时找不到映射置0，表示目标域未知；没有发送者(旧调用)时保持原值，避免破坏数据
                    if(mappedUid.isPresent()) {
                        object.addProperty("u", mappedUid.get());
                        messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                    } else if(StringUtils.hasText(messageSender)) {
                        object.addProperty("u", 0);
                        messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
            convertCallPushData(remoteDomainId, messagePayload, localDomainId, true, messageSender);
        } else if(messagePayload.getType() == 408 || messagePayload.getType() == 420) {
            //会议邀请/PTT邀请里的 h 是主持人用户ID
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null && replaceOutString(object, "h", remoteDomainId, localDomainId)) {
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
        } else if(messagePayload.getType() == 416) {
            //多人通话进行中消息：发起人和所有被叫都是用户ID
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                boolean changed = replaceOutString(object, "initiator", remoteDomainId, localDomainId);
                changed |= replaceStringList(object, "targetIds", remoteDomainId, localDomainId);
                changed |= replaceStringList(object, "targets", remoteDomainId, localDomainId);
                if(changed) {
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
        } else if(messagePayload.getType() == 17) {
            //接龙/收藏消息：群ID、创建者、每条参与记录的用户ID
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                boolean changed = replaceOutString(object, "groupId", remoteDomainId, localDomainId);
                changed |= replaceOutString(object, "creatorId", remoteDomainId, localDomainId);
                JsonElement entries = object.get("entries");
                if(entries != null && entries.isJsonArray()) {
                    for (JsonElement entry : entries.getAsJsonArray()) {
                        if(entry.isJsonObject()) {
                            changed |= replaceOutString(entry.getAsJsonObject(), "userId", remoteDomainId, localDomainId);
                        }
                    }
                }
                if(changed) {
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
        } else if(messagePayload.getType() == 18 || messagePayload.getType() == 19) {
            //投票/投票结果：群ID和创建者
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                boolean changed = replaceOutString(object, "groupId", remoteDomainId, localDomainId);
                changed |= replaceOutString(object, "creatorId", remoteDomainId, localDomainId);
                if(changed) {
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
        } else if(messagePayload.getType() == 26) {
            //转写消息的 content 是 JSON，里面带发言人用户ID
            convertTranscriptionUserId(remoteDomainId, messagePayload, localDomainId);
        }
        //Call ID
        if(messagePayload.getType() >= 400 && messagePayload.getType() <= 407) {
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
        }

        //撤回或者删除消息
        if(messagePayload.getType() == 80 || messagePayload.getType() == 81) {
            String operatorId = messagePayload.getContent();
            String originalSender = operatorId;
            String base64edData = messagePayload.getBase64edData();
            long messageUid = StringUtils.hasText(base64edData)
                ? Long.parseLong(new String(Base64.getDecoder().decode(base64edData), StandardCharsets.UTF_8)) : 0;
            boolean isRealSender = false;
            if(!TextUtils.isEmpty(messagePayload.getExtra())) {
                JsonObject object = (JsonObject) JsonParser.parseString(messagePayload.getExtra());
                if(object != null) {
                    String sender = object.get("s").getAsString();
                    if(!StringUtils.isEmpty(sender)) {
                        originalSender = sender;
                        isRealSender = true;
                    }
                    replaceOutString(object, "s", remoteDomainId, localDomainId);
                    messagePayload.setExtra(object.toString());
                }
            }

            // 文本消息，有可能有引用，引用信息中包含消息ID，这个消息ID在每个域中都是不同的。也包含发送者用户ID。

            // 替换消息ID
            if(!isRealSender) {
                //需要用发送者的域信息来确定消息来源。删除消息在2025.12.1之前的版本不带原消息发送者的，只能猜测operatorId跟sender一个域。
                //有可能不是同一个域，导致错误。比一个域中的群中，有另外一个域的用户发送消息，群所在域可以用server api删除这个消息。
                //当出现这种情况时，需要升级IM服务，让删除消息带上发送者信息.
                LOG.error("撤回或删除消息内容，消息体中没有找到发送者。如果消息是对方域用户发送，删除将失败！");
            }
            if(messageUid > 0) {
                long targetMessageUid = convertMessageUid(remoteDomainId, originalSender, messageUid);
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(String.valueOf(targetMessageUid).getBytes(StandardCharsets.UTF_8)));
            }

            //替换操作者
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
        } else if(CompositeMessageUtils.isComposite(messagePayload)) {
            convertCompositeMessagePayload(remoteDomainId, messagePayload, localDomainId, isPublish, depth, new ConvertCallback() {
                @Override
                public void onSuccess() {
                    convertMessageMedia(remoteDomainId, messagePayload, localDomainId, isPublish, isPublish2OriganlDomain, depth, callback);
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure(errorCode);
                }
            });
            return;
        }

        if(messagePayload.getType() >= 104 && messagePayload.getType() <= 125) {
            JsonObject object = decodeJsonPayload(messagePayload.getBase64edData());
            if(object != null) {
                replaceOutString(object, "o", remoteDomainId, localDomainId);
                replaceOutString(object, "g", remoteDomainId, localDomainId);
                switch (messagePayload.getType()) {
                    case 105:  //add group member
                        //n 是审批人用户ID
                        replaceOutString(object, "n", remoteDomainId, localDomainId);
                        replaceStringList(object, "ms", remoteDomainId, localDomainId);
                        break;
                    case 106:  //kickoff group member
                    case 120:  //kickoff group member visible
                    case 117:  //change group manager
                    case 118:  //change group member mute state
                    case 119:  //change group member allow state
                        replaceStringList(object, "ms", remoteDomainId, localDomainId);
                        break;
                    case 125:  //reject join group
                        //mi 是被拒绝的用户ID
                        replaceOutString(object, "mi", remoteDomainId, localDomainId);
                        break;
                    case 109:  //transfer group
                    case 111:  //change group member alias
                    case 123:  //change group member extra
                        replaceOutString(object, "m", remoteDomainId, localDomainId);
                        break;
                    case 104:  //create group
                    case 107:  //quit group
                    case 121:  //quit group visible
                    case 108:  //dismiss group
                    case 110:  //change group name
                    case 112:  //change group portrait
                    case 113:  //change group mute state
                    case 114:  //change group join state
                    case 115:  //change group member private chat
                    case 116:  //change group searchable
                    case 122:  //change group extra
                    case 124:  //change group setting
                    default:
                        break;
                }
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
        }

        convertMessageMedia(remoteDomainId, messagePayload, localDomainId, isPublish, isPublish2OriganlDomain, depth, callback);
    }

    //部分消息类型的扩展数据是可选的(比如组合消息里的通话、群操作通知等子消息)，没有 data 时直接返回 null，避免 NPE
    private JsonObject decodeJsonPayload(String base64edData) {
        if(!StringUtils.hasText(base64edData)) {
            return null;
        }
        return JsonParser.parseString(new String(Base64.getDecoder().decode(base64edData), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    //通话类消息的 pushData 里也带参与者用户ID，推送打开时客户端会用到
    private void convertCallPushData(String remoteDomainId, MessagePayload messagePayload, String localDomainId,
                                     boolean convertInviteUid, String messageSender) {
        if(!StringUtils.hasText(messagePayload.getPushData())) {
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(messagePayload.getPushData());
            if(!element.isJsonObject()) {
                return;
            }
            JsonObject object = element.getAsJsonObject();
            boolean changed = replaceStringList(object, "participants", remoteDomainId, localDomainId);
            if(object.has("existParticipants") && object.get("existParticipants").isJsonArray()) {
                JsonArray participants = new JsonArray();
                for (JsonElement participant : object.getAsJsonArray("existParticipants")) {
                    if(participant.isJsonPrimitive()) {
                        participants.add(DomainIdUtils.toExternalId(remoteDomainId, participant.getAsString(), localDomainId));
                        changed = true;
                    } else if(participant.isJsonObject() && participant.getAsJsonObject().has("userId")) {
                        JsonObject participantObject = participant.getAsJsonObject();
                        replaceOutString(participantObject, "userId", remoteDomainId, localDomainId);
                        participants.add(participantObject);
                        changed = true;
                    } else {
                        participants.add(participant);
                    }
                }
                object.add("existParticipants", participants);
            }
            if(convertInviteUid && object.has("u") && object.get("u").isJsonPrimitive()) {
                long inviteMessageUid = object.get("u").getAsLong();
                Optional<Long> mappedUid = findMappedMessageUid(remoteDomainId, messageSender, inviteMessageUid);
                if(mappedUid.isPresent()) {
                    object.addProperty("u", mappedUid.get());
                    changed = true;
                } else if(StringUtils.hasText(messageSender)) {
                    object.addProperty("u", 0);
                    changed = true;
                }
            }
            if(changed) {
                messagePayload.setPushData(object.toString());
            }
        } catch (Exception e) {
            LOG.warn("convert call push data failed, pushData is not json");
        }
    }

    //转写消息的 content 是 JSON，userId 是发言人，需要换成目标域视角
    private void convertTranscriptionUserId(String remoteDomainId, MessagePayload messagePayload, String localDomainId) {
        if(!StringUtils.hasText(messagePayload.getContent())) {
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(messagePayload.getContent());
            if(!element.isJsonObject()) {
                return;
            }
            JsonObject object = element.getAsJsonObject();
            if(replaceOutString(object, "userId", remoteDomainId, localDomainId)) {
                messagePayload.setContent(object.toString());
            }
        } catch (Exception e) {
            LOG.warn("convert transcription message user id failed, content is not json");
        }
    }

    private void convertCompositeMessagePayload(String remoteDomainId, MessagePayload messagePayload, String localDomainId, boolean isPublish, int depth, ConvertCallback callback) {
        if(!StringUtils.hasText(messagePayload.getBase64edData())) {
            callback.onSuccess();
            return;
        }
        try {
            JsonObject composite = CompositeMessageUtils.decode(messagePayload.getBase64edData());
            convertCompositeMessages(remoteDomainId, CompositeMessageUtils.messages(composite), 0, localDomainId, isPublish, depth, new ConvertCallback() {
                @Override
                public void onSuccess() {
                    messagePayload.setBase64edData(CompositeMessageUtils.encode(composite));
                    callback.onSuccess();
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure(errorCode);
                }
            });
        } catch (Exception e) {
            LOG.error("decode composite message failure", e);
            callback.onFailure(ERROR_INVALID_PARAMETER.code);
        }
    }

    //串行转换组合消息里的子消息。子消息带媒体时需要等待异步传输完成后再处理下一条，
    //这里用 CompletableFuture 表示"子消息已转换完成"：同步完成时继续当前循环，异步完成时从回调里继续，
    //避免回调和循环同时推进，也避免用普通布尔变量在跨线程时出现可见性问题。
    private void convertCompositeMessages(String remoteDomainId, JsonArray messages, int index, String localDomainId, boolean isPublish, int depth, ConvertCallback callback) {
        if(index == 0) {
            //先按整层子消息批量转换 uid、预取媒体映射，避免每个子消息都查一次库
            convertCompositeChildUids(remoteDomainId, messages);
            prefetchCompositeChildMediaMappings(remoteDomainId, messages);
        }
        int currentIndex = index;
        while(currentIndex < messages.size()) {
            final JsonObject message;
            final MessagePayload childPayload;
            final String originalSender;
            try {
                JsonElement element = messages.get(currentIndex);
                if(!element.isJsonObject()) {
                    throw new IllegalArgumentException("composite child message is not a json object");
                }
                message = element.getAsJsonObject();
                //uid 已在整层批量转换过，这里只需要 from 用来判断是否发回原域
                originalSender = CompositeMessageUtils.getString(message, "from");
                replaceOutString(message, "target", remoteDomainId, localDomainId);
                replaceOutString(message, "from", remoteDomainId, localDomainId);
                //接收者字段各端写法不一致：安卓编码写 tos、解码读 toUsers，web 端 tos 可能是字符串，这里都兼容一下
                replaceStringList(message, "tos", remoteDomainId, localDomainId);
                replaceOutString(message, "tos", remoteDomainId, localDomainId);
                replaceStringList(message, "toUsers", remoteDomainId, localDomainId);
                replaceOutString(message, "toUsers", remoteDomainId, localDomainId);
                childPayload = CompositeMessageUtils.toPayload(message);
            } catch (Exception e) {
                LOG.error("decode composite child message failure, index {}", currentIndex, e);
                callback.onFailure(ERROR_INVALID_PARAMETER.code);
                return;
            }

            //子消息的原始发送者就是目标域的用户时，媒体文件本来就在目标域，不能再传回去。
            //send_message 和 publish 都可能出现这种子消息（比如 1:1 聊天里转发一条对方发的图片），
            //所以不能只看 isPublish。
            boolean childPublish2OriginalDomain = DomainIdUtils.isExternalId(originalSender)
                && remoteDomainId.equals(DomainIdUtils.getExternalId(originalSender));

            final CompletableFuture<Integer> converted;
            try {
                converted = convertCompositeChildPayload(remoteDomainId, childPayload, localDomainId, isPublish, childPublish2OriginalDomain, originalSender, depth + 1)
                    .thenApply(errorCode -> {
                        if(errorCode == 0) {
                            CompositeMessageUtils.updatePayload(message, childPayload);
                        }
                        return errorCode;
                    });
            } catch (Exception e) {
                LOG.error("convert composite child message failure, index {}", currentIndex, e);
                callback.onFailure(ERROR_INVALID_PARAMETER.code);
                return;
            }

            if(!converted.isDone()) {
                final int nextIndex = currentIndex + 1;
                final int convertingIndex = currentIndex;
                converted.whenComplete((errorCode, throwable) -> {
                    if(throwable != null) {
                        LOG.error("encode composite child message failure, index {}", convertingIndex, throwable);
                        callback.onFailure(ERROR_INVALID_PARAMETER.code);
                        return;
                    }
                    if(errorCode == null || errorCode != 0) {
                        callback.onFailure(errorCode == null ? ERROR_INVALID_PARAMETER.code : errorCode);
                        return;
                    }
                    //这个回调里抛出的异常不会有人处理，必须自己兜住，否则消息会一直挂到超时
                    try {
                        convertCompositeMessages(remoteDomainId, messages, nextIndex, localDomainId, isPublish, depth, callback);
                    } catch (Exception e) {
                        LOG.error("continue composite message conversion failure, index {}", nextIndex, e);
                        callback.onFailure(ERROR_INVALID_PARAMETER.code);
                    }
                });
                return;
            }
            if(converted.isCompletedExceptionally()) {
                LOG.error("encode composite child message failure, index {}", currentIndex);
                callback.onFailure(ERROR_INVALID_PARAMETER.code);
                return;
            }
            Integer errorCode = converted.join();
            if(errorCode == null || errorCode != 0) {
                callback.onFailure(errorCode == null ? ERROR_INVALID_PARAMETER.code : errorCode);
                return;
            }
            currentIndex++;
        }
        callback.onSuccess();
    }

    //转换单个子消息，正常完成返回0，失败返回错误码。用 CompletableFuture 表达完成状态，调用方据此决定是继续循环还是等回调。
    private CompletableFuture<Integer> convertCompositeChildPayload(String remoteDomainId, MessagePayload childPayload, String localDomainId, boolean isPublish, boolean isPublish2OriganlDomain, String originalSender, int depth) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        convertMessagePayloadDomainId(remoteDomainId, childPayload, localDomainId, isPublish, isPublish2OriganlDomain, originalSender, depth, new ConvertCallback() {
            @Override
            public void onSuccess() {
                future.complete(0);
            }

            @Override
            public void onFailure(int errorCode) {
                future.complete(errorCode);
            }
        });
        return future;
    }

    //整层子消息的 uid 一起转换：一次批量查询代替每个子消息各查一次。
    //批量查询失败时退化成逐条查询，保证功能不受影响。
    private void convertCompositeChildUids(String remoteDomainId, JsonArray messages) {
        Set<Long> allUids = new HashSet<>();
        Set<Long> originUids = new HashSet<>();
        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            long uid = CompositeMessageUtils.getLong(message, "uid");
            if(uid <= 0) {
                continue;
            }
            allUids.add(uid);
            String sender = CompositeMessageUtils.getString(message, "from");
            if(DomainIdUtils.isExternalId(sender) && remoteDomainId.equals(DomainIdUtils.getExternalId(sender))) {
                originUids.add(uid);
            }
        }
        if(allUids.isEmpty()) {
            return;
        }

        Map<Long, Long> outMappings = null;
        Map<Long, Long> inMappings = null;
        try {
            outMappings = new HashMap<>();
            for (List<Long> chunk : partitionMessageIds(allUids)) {
                for (OutMessageIds mapping : outMessageIdsRepository.findByMessageIds(remoteDomainId, chunk)) {
                    outMappings.put(mapping.messageId, mapping.toMessageId);
                }
            }
            if(!originUids.isEmpty()) {
                inMappings = new HashMap<>();
                for (List<Long> chunk : partitionMessageIds(originUids)) {
                    for (InMessageIds mapping : inMessageIdsRepository.findByLocalMessageIds(remoteDomainId, chunk)) {
                        inMappings.put(mapping.localMessageId, mapping.id.messageId);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("batch query composite child message ids failed, fallback to per-message query", e);
            outMappings = null;
            inMappings = null;
        }

        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            long uid = CompositeMessageUtils.getLong(message, "uid");
            if(uid <= 0) {
                continue;
            }
            String sender = CompositeMessageUtils.getString(message, "from");
            if(outMappings == null) {
                convertCompositeChildUid(remoteDomainId, message, sender);
                continue;
            }
            Long mappedUid = outMappings.get(uid);
            if(mappedUid == null && inMappings != null && DomainIdUtils.isExternalId(sender)
                && remoteDomainId.equals(DomainIdUtils.getExternalId(sender))) {
                mappedUid = inMappings.get(uid);
            }
            if(mappedUid != null && mappedUid > 0) {
                if(mappedUid != uid) {
                    message.addProperty("uid", mappedUid);
                }
            } else {
                //找不到映射时置0(表示目标域未知)，避免把别的域的uid当成本域uid使用
                LOG.debug("composite child message uid {} has no mapping to domain {}, set to 0", uid, remoteDomainId);
                message.addProperty("uid", 0);
            }
        }
    }

    //整层子消息的媒体映射一起预取，后面 transferFile 直接命中缓存，不再逐个查库
    private void prefetchCompositeChildMediaMappings(String remoteDomainId, JsonArray messages) {
        Set<String> mediaUrls = new HashSet<>();
        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                continue;
            }
            String mediaUrl = CompositeMessageUtils.getString(element.getAsJsonObject(), "mru");
            if(StringUtils.hasText(mediaUrl)) {
                mediaUrls.add(mediaUrl);
            }
        }
        if(!mediaUrls.isEmpty()) {
            mediaUrlMappingService.prefetchRemoteMediaUrls(remoteDomainId, mediaUrls);
        }
    }

    private static List<List<Long>> partitionMessageIds(Collection<Long> messageIds) {
        List<List<Long>> chunks = new ArrayList<>();
        List<Long> current = new ArrayList<>(MESSAGE_ID_BATCH_SIZE);
        for (Long messageId : messageIds) {
            current.add(messageId);
            if(current.size() >= MESSAGE_ID_BATCH_SIZE) {
                chunks.add(current);
                current = new ArrayList<>(MESSAGE_ID_BATCH_SIZE);
            }
        }
        if(!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private void convertCompositeChildUid(String remoteDomainId, JsonObject message, String originalSender) {
        long uid = CompositeMessageUtils.getLong(message, "uid");
        if(uid <= 0) {
            return;
        }
        Optional<Long> mappedUid = findMappedMessageUid(remoteDomainId, originalSender, uid);
        if(!mappedUid.isPresent() || mappedUid.get() <= 0) {
            //找不到映射时置0(表示目标域未知)。不能保留原值：这个uid可能来自别的域，
            //以后本域再把它当成本域uid去查 out_message_ids 时会误命中，改写成错误的消息ID。
            LOG.debug("composite child message uid {} has no mapping to domain {}, set to 0", uid, remoteDomainId);
            message.addProperty("uid", 0);
            return;
        }
        if(mappedUid.get() != uid) {
            message.addProperty("uid", mappedUid.get());
        }
    }

    //如果本域的对象存储服务无法让其他域的用户直接访问，需要把文件异步传输到对方域，并把地址改成对方域返回的地址。
    //有2种情况：一种是A域用户发给B域的群，A send 到 B，B处理完本域用户后再publish到C域(有非A域用户时也会publish回A)；
    //另一种是A域用户发到A域的群，处理完本域用户后再publish到B、C域。所以存在"A send 到B、B再publish回A"的情况，
    //此时目标域就是消息发送者所在域(isPublish2OriganlDomain)，不需要再传文件，直接用对方已经保存的地址，对端收到后按映射换回本域地址。
    private void convertMessageMedia(String remoteDomainId, MessagePayload messagePayload, String localDomainId, boolean isPublish, boolean isPublish2OriganlDomain, int depth, ConvertCallback callback) {
        if(TextUtils.isEmpty(messagePayload.getRemoteMediaUrl())) {
            callback.onSuccess();
            return;
        }
        if(isPublish2OriganlDomain) {
            //目标就是消息(或组合消息子消息)的来源域，文件本来就在对方域，绝对不能重新上传。
            //有反向映射时顺手换成对方域的原地址；没有映射就保持原地址，由接收方用消息ID映射再还原。
            mediaUrlMappingService.findRemoteMediaUrl(remoteDomainId, messagePayload.getRemoteMediaUrl())
                .ifPresent(messagePayload::setRemoteMediaUrl);
            callback.onSuccess();
            return;
        }
        if(CompositeMessageUtils.isComposite(messagePayload)) {
            //组合消息的大文件里存的是完整子消息列表，必须下载下来转换后重新上传，不能像普通文件那样只改地址。
            transferCompositeFile(remoteDomainId, messagePayload.getRemoteMediaUrl(), messagePayload.getMediaType(), localDomainId, isPublish, depth, new TransferFileCallback() {
                @Override
                public void onSuccess(String remoteMediaUrl) {
                    messagePayload.setRemoteMediaUrl(remoteMediaUrl);
                    callback.onSuccess();
                }

                @Override
                public void onFailure(int errorCode, String errorMessage) {
                    LOG.error("transfer composite file to domain {} failure, mediaUrl {}, error: {}", remoteDomainId, messagePayload.getRemoteMediaUrl(), errorMessage);
                    callback.onFailure(errorCode);
                }
            });
            return;
        }
        if(!mediaPublicAccessible) {
            transferFile(remoteDomainId, messagePayload.getRemoteMediaUrl(), messagePayload.getMediaType(), new TransferFileCallback() {
                @Override
                public void onSuccess(String remoteMediaUrl) {
                    messagePayload.setRemoteMediaUrl(remoteMediaUrl);
                    callback.onSuccess();
                }

                @Override
                public void onFailure(int errorCode, String errorMessage) {
                    LOG.error("transfer file to domain {} failure, mediaUrl {}, error: {}", remoteDomainId, messagePayload.getRemoteMediaUrl(), errorMessage);
                    callback.onFailure(errorCode);
                }
            });
            return;
        }
        callback.onSuccess();
    }

    private boolean replaceOutString(JsonObject object, String key, String remoteDomainId, String localDomainId) {
        if(object.has(key) && object.get(key) instanceof JsonPrimitive) {
            String o = object.get(key).getAsString();
            o = DomainIdUtils.toExternalId(remoteDomainId, o, localDomainId);
            object.addProperty(key, o);
            return true;
        }
        return false;
    }

    private boolean replaceInString(JsonObject object, String key, String remoteDomainId, String localDomainId) {
        if(object.has(key) && object.get(key) instanceof JsonPrimitive) {
            String o = object.get(key).getAsString();
            o = DomainIdUtils.toInternalId(remoteDomainId, o, localDomainId);
            object.addProperty(key, o);
            return true;
        }
        return false;
    }

    private boolean replaceStringList(JsonObject object, String key, String remoteDomainId, String localDomainId) {
        if(object.has(key) && object.get(key) instanceof JsonArray) {
            JsonArray jsonArray = object.get(key).getAsJsonArray();
            JsonArray newArray = new JsonArray();
            for (JsonElement jsonElement : jsonArray) {
                String element = jsonElement.getAsString();
                newArray.add(DomainIdUtils.toExternalId(remoteDomainId, element, localDomainId));
            }
            object.add(key, newArray);
            return true;
        }
        return false;
    }

    public Object onPublishMessageRequest(String domainId, SendMessageData messageData, List<String> receivers, boolean republish, long messageId) {
        if(!StringUtils.hasText(messageData.getSender()) || !StringUtils.hasText(messageData.getConv().getTarget()) || receivers.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        //判断是否publish回消息发送者所在域。必须在ID转换之前判断：转换后本域的群ID和发送者ID都会带上本域后缀，
        //会把"本域群+本域发送者"的正常跨域publish误判成publish回原域，导致跳过文件传输
        boolean isPublish2OriganlDomain = DomainIdUtils.isExternalId(messageData.getSender()) && domainId.equals(DomainIdUtils.getExternalId(messageData.getSender()));
        //消息ID映射要用转换前的本域发送者判断消息来源，不能传转换后的对方视角ID
        String originalSender = messageData.getSender();

        messageData.getConv().setTarget(DomainIdUtils.toExternalId(domainId, messageData.getConv().getTarget(), myDomainId));
        messageData.setSender(DomainIdUtils.toExternalId(domainId, originalSender, myDomainId));

        List<String> receiverIds = new ArrayList<>();
        for (String receiver : receivers) {
            receiverIds.add(DomainIdUtils.toExternalId(domainId, receiver, myDomainId));
        }
        DeferredResult<String> deferredResult = newTransferAwareDeferredResult();
        PojoPublishMessageReq req = new PojoPublishMessageReq();
        req.receivers = receiverIds;
        req.domainId = myDomainId;
        req.republish = republish;
        req.messageId = messageId;
        String originalMediaUrl = messageData.getPayload().getRemoteMediaUrl();

        convertMessagePayloadDomainId(domainId, messageData.getPayload(), myDomainId, true, isPublish2OriganlDomain, originalSender, new ConvertCallback() {
            @Override
            public void onSuccess() {
                req.messageData = messageData;
                HttpUtils.httpPostToDomain(domain, "/publish_message", req, new HttpUtils.HttpCallback() {
                    @Override
                    public void onSuccess(String content) {
                        MeshRestResult<SendMessageResult> meshRestResult = JsonUtils.fromJsonObject2(content, SendMessageResult.class);
                        if(meshRestResult.getLocal_mesh_code() == 0 && meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0) {
                            OutMessageIds outMessageIds = new OutMessageIds();
                            outMessageIds.messageId = messageId;
                            outMessageIds.toDomainId = domainId;
                            // 记录下地址，如果消息再分发回本域，可以找到地址进行替换
                            outMessageIds.mediaUrl = originalMediaUrl;
                            outMessageIds.toMessageId = meshRestResult.getResult().getMessageUid();

                            outMessageIdsRepository.save(outMessageIds);
                            LOG.info("publish out message success, local messageId {}, remote messageId {}", messageId, meshRestResult.getResult().getMessageUid());
                        } else {
                            LOG.error("publish out message failure! result is {}", content);
                        }

                        deferredResult.setResult(content);
                    }

                    @Override
                    public void onFailure(int statusCode, String errorMessage) {
                        MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                        deferredResult.setResult(meshRestResult.toString());
                    }
                });
            }

            @Override
            public void onFailure(int error_code) {
                LOG.error("onPublishMessageRequest: convert message payload domain id error: {}", error_code);
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "convertMessagePayloadDomainId error code:" + error_code);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onRecallMessageRequest(String domainId, long messageId, String operator, boolean isSenderRecall) {
        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoRecallMessageReq req = new PojoRecallMessageReq();
        req.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        req.domainId = myDomainId;
        if(isSenderRecall) {
            Optional<OutMessageIds> optionalOutMessageIds = outMessageIdsRepository.findByMessageIdAndToDomainId(messageId, domainId);
            if(!optionalOutMessageIds.isPresent()) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_NOT_EXIST.code, "Message Id not found");
                deferredResult.setResult(meshRestResult.toString());
            } else {
                req.messageId = optionalOutMessageIds.get().toMessageId;
            }
        } else {
            Optional<Long> optionalRemoteMessageId = inMessageIdsRepository.findByDomainIdAndLocalMessageId(domainId, messageId);
            if(!optionalRemoteMessageId.isPresent()) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_NOT_EXIST.code, "Message Id not found");
                deferredResult.setResult(meshRestResult.toString());
            } else {
                req.messageId = optionalRemoteMessageId.get();
            }
        }

        HttpUtils.httpPostToDomain(domain, "/recall_message", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
                if(!TextUtils.isEmpty(meshRestResult.getResult())) {
                    try {
                        JsonObject object = (JsonObject) JsonParser.parseString(meshRestResult.getResult());
                        if(object != null) {
                            replaceInString(object, "s", domainId, myDomainId);
                            meshRestResult.setResult(object.toString());
                        }
                    } catch (JsonSyntaxException e) {
                        LOG.warn("invalid recall message response from domain {}", domainId);
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    interface TransferFileCallback {
        void onSuccess(String remoteMediaUrl);
        void onFailure(int errorCode, String errorMessage);
    }

    //组合消息的大文件需要下载后改写再上传。包内可见，方便单元测试替换文件传输实现。
    void transferCompositeFile(String remoteDomainId, String mediaUrl, int mediaType, String localDomainId, boolean isPublish, int depth, TransferFileCallback callback) {
        //映射命中说明这个文件之前已经传过，直接复用对端地址，不再重复下载上传(地址在保存前已经校验过)
        Optional<String> mappedMediaUrl = mediaUrlMappingService.findRemoteMediaUrl(remoteDomainId, mediaUrl);
        if(mappedMediaUrl.isPresent()) {
            callback.onSuccess(mappedMediaUrl.get());
            return;
        }
        if(!isAllowedMediaUrl(mediaUrl)) {
            callback.onFailure(ERROR_INVALID_PARAMETER.code, "媒体地址不合法，只允许http/https且需要在白名单内");
            return;
        }
        Optional<Domain> optionalDomain = domainRepository.findById(remoteDomainId);
        if(!optionalDomain.isPresent()) {
            callback.onFailure(ERROR_NOT_EXIST.code, "服务实体（" + remoteDomainId + ")不存在");
            return;
        }
        Domain domain = optionalDomain.get();
        try {
            transferFileExecutor.execute(() -> downloadCompositeFile(domain, remoteDomainId, mediaUrl, mediaType, localDomainId, isPublish, depth, callback));
        } catch (RejectedExecutionException e) {
            callback.onFailure(ERROR_SERVER_ERROR.code, "文件传输队列已满，请稍后重试");
        }
    }

    //包内可见，方便单元测试替换"下载+改写+上传"的实现
    void downloadCompositeFile(Domain domain, String remoteDomainId, String mediaUrl, int mediaType, String localDomainId, boolean isPublish, int depth, TransferFileCallback callback) {
        //组合文件要整个读进内存解析成JSON，用单独的大小上限(默认10MB)，不能沿用普通文件的100MB
        long maxBytes = compositeFileMaxSizeMb > 0 ? compositeFileMaxSizeMb * 1024 * 1024 : -1;
        byte[] content;
        String downloadUrl = presignLocalMediaUrl(mediaUrl);
        try (Response response = fileHttpClient.newCall(new Request.Builder().url(downloadUrl).get().build()).execute()) {
            if(response.isRedirect()) {
                callback.onFailure(ERROR_SERVER_ERROR.code, "download file redirect is not allowed");
                return;
            }
            if(!response.isSuccessful() || response.body() == null) {
                callback.onFailure(ERROR_SERVER_ERROR.code, "download composite file failure, status code:" + response.code());
                return;
            }
            long contentLength = response.body().contentLength();
            if(maxBytes > 0 && contentLength > maxBytes) {
                callback.onFailure(ERROR_INVALID_PARAMETER.code, "组合消息文件超过大小限制 " + compositeFileMaxSizeMb + "MB");
                return;
            }
            content = readAll(response.body().byteStream(), maxBytes);
        } catch (Exception e) {
            LOG.error("download composite file failure, mediaUrl {}", mediaUrl, e);
            callback.onFailure(ERROR_SERVER_ERROR.code, "download composite file failure");
            return;
        }

        JsonObject composite;
        try {
            composite = CompositeMessageUtils.decode(content);
        } catch (Exception e) {
            LOG.error("invalid composite file, mediaUrl {}", mediaUrl, e);
            callback.onFailure(ERROR_INVALID_PARAMETER.code, "invalid composite file");
            return;
        }
        try {
            convertCompositeMessages(remoteDomainId, CompositeMessageUtils.messages(composite), 0, localDomainId, isPublish, depth, new ConvertCallback() {
                @Override
                public void onSuccess() {
                    uploadCompositeFile(domain, remoteDomainId, mediaUrl, mediaType, composite, callback);
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure(errorCode, "convert composite file failure");
                }
            });
        } catch (Exception e) {
            LOG.error("convert composite file failure, mediaUrl {}", mediaUrl, e);
            callback.onFailure(ERROR_SERVER_ERROR.code, "convert composite file failure");
        }
    }

    private byte[] readAll(InputStream inputStream, long maxBytes) throws IOException {
        InputStream limitedStream = maxBytes > 0 ? new LimitedInputStream(inputStream, maxBytes) : inputStream;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = limitedStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private void uploadCompositeFile(Domain domain, String remoteDomainId, String mediaUrl, int mediaType, JsonObject composite, TransferFileCallback callback) {
        try {
            byte[] contentBytes = composite.toString().getBytes(StandardCharsets.UTF_8);
            long maxBytes = compositeFileMaxSizeMb > 0 ? compositeFileMaxSizeMb * 1024 * 1024 : -1;
            if(maxBytes > 0 && contentBytes.length > maxBytes) {
                callback.onFailure(ERROR_INVALID_PARAMETER.code, "组合消息文件超过大小限制 " + compositeFileMaxSizeMb + "MB");
                return;
            }
            RequestBody uploadBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("application/json; charset=utf-8");
                }

                @Override
                public long contentLength() {
                    return contentBytes.length;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    sink.write(contentBytes);
                }
            };
            Map<String, String> extraHeaders = transferFileHeaders(mediaUrl, mediaType);
            String content = HttpUtils.httpPostStreamToDomain(domain, "/transfer_file", extraHeaders, uploadBody, fileTransferTimeoutMinutes);
            MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
            if(meshRestResult.getLocal_mesh_code() == 0 && meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0 && !TextUtils.isEmpty(meshRestResult.getResult())) {
                mediaUrlMappingService.saveMapping(remoteDomainId, mediaUrl, meshRestResult.getResult());
                callback.onSuccess(meshRestResult.getResult());
            } else {
                callback.onFailure(ERROR_SERVER_ERROR.code, "transfer composite file failure! result: " + content);
            }
        } catch (Exception e) {
            LOG.error("upload composite file to domain {} failure, mediaUrl {}", remoteDomainId, mediaUrl, e);
            callback.onFailure(ERROR_SERVER_ERROR.code, "upload composite file failure");
        }
    }

    //把媒体文件传输到对方域。本方的对象存储地址域外无法访问，需要先在本方下载，边下边传给对端，成功后回调返回对方域保存后的文件地址。
    //包内可见，方便单元测试替换文件传输实现。
    void transferFile(String remoteDomainId, String mediaUrl, int mediaType, TransferFileCallback callback) {
        //映射命中说明这个文件之前已经传过，直接复用对端地址，不再重复上传(地址在保存前已经校验过)
        Optional<String> mappedMediaUrl = mediaUrlMappingService.findRemoteMediaUrl(remoteDomainId, mediaUrl);
        if(mappedMediaUrl.isPresent()) {
            callback.onSuccess(mappedMediaUrl.get());
            return;
        }
        if(!isAllowedMediaUrl(mediaUrl)) {
            LOG.error("transfer file to domain {} rejected, illegal mediaUrl {}", remoteDomainId, mediaUrl);
            callback.onFailure(ERROR_INVALID_PARAMETER.code, "媒体地址不合法，只允许http/https且需要在白名单内");
            return;
        }
        Optional<Domain> optionalDomain = domainRepository.findById(remoteDomainId);
        if(!optionalDomain.isPresent()) {
            callback.onFailure(ERROR_NOT_EXIST.code, "服务实体（" + remoteDomainId + ")不存在");
            return;
        }
        Domain domain = optionalDomain.get();

        try {
            transferFileExecutor.execute(() -> doTransferFile(domain, remoteDomainId, mediaUrl, mediaType, callback));
        } catch (RejectedExecutionException e) {
            LOG.error("transfer file to domain {} rejected, queue is full, mediaUrl {}", remoteDomainId, mediaUrl);
            callback.onFailure(ERROR_SERVER_ERROR.code, "文件传输队列已满，请稍后重试");
        }
    }

    //包内可见，方便单元测试替换"下载+上传"的实现
    void doTransferFile(Domain domain, String remoteDomainId, String mediaUrl, int mediaType, TransferFileCallback callback) {
        //本域对象存储可能是私有读，如果地址是本域配置的桶地址，先签名出1小时内可读的临时地址再下载
        String downloadUrl = presignLocalMediaUrl(mediaUrl);
        try (Response downloadResponse = fileHttpClient.newCall(new Request.Builder().url(downloadUrl).get().build()).execute()) {
            if (downloadResponse.isRedirect()) {
                callback.onFailure(ERROR_SERVER_ERROR.code, "download file redirect is not allowed");
                return;
            }
            if(!downloadResponse.isSuccessful() || downloadResponse.body() == null) {
                callback.onFailure(ERROR_SERVER_ERROR.code, "download file from local media server failure, status code:" + downloadResponse.code());
                return;
            }
            final ResponseBody downloadBody = downloadResponse.body();
            final String contentType = downloadBody.contentType() != null ? downloadBody.contentType().toString() : null;
            final long maxBytes = fileTransferMaxSizeMb > 0 ? fileTransferMaxSizeMb * 1024 * 1024 : -1;
            final long contentLength = downloadBody.contentLength();
            if(maxBytes > 0 && contentLength > maxBytes) {
                callback.onFailure(ERROR_INVALID_PARAMETER.code, "文件超过大小限制 " + fileTransferMaxSizeMb + "MB");
                return;
            }
            final InputStream downloadStream = maxBytes > 0 ? new LimitedInputStream(downloadBody.byteStream(), maxBytes) : downloadBody.byteStream();

            //上传的body从下载流中读取，边下边传，文件不落地
            RequestBody uploadBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return contentType != null ? MediaType.parse(contentType) : null;
                }

                @Override
                public long contentLength() {
                    return contentLength;
                }

                @Override
                public boolean isOneShot() {
                    //流是一次性的，禁止OkHttp重试时重复调用writeTo
                    return true;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = downloadStream.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                    }
                    sink.flush();
                }
            };

            Map<String, String> extraHeaders = transferFileHeaders(mediaUrl, mediaType);

            String content = HttpUtils.httpPostStreamToDomain(domain, "/transfer_file", extraHeaders, uploadBody, fileTransferTimeoutMinutes);
            MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
            if(meshRestResult.getLocal_mesh_code() == 0 && meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0 && !TextUtils.isEmpty(meshRestResult.getResult())) {
                mediaUrlMappingService.saveMapping(remoteDomainId, mediaUrl, meshRestResult.getResult());
                callback.onSuccess(meshRestResult.getResult());
            } else {
                callback.onFailure(ERROR_SERVER_ERROR.code, "transfer file failure! result: " + content);
            }
        } catch (Exception e) {
            LOG.error("transfer file to domain {} failure, mediaUrl {}", remoteDomainId, mediaUrl, e);
            callback.onFailure(ERROR_SERVER_ERROR.code, "transfer file failure");
        }
    }

    //与InService.bucketTypeOf对应的全部桶类型，用于按配置的对象存储地址反查桶名
    private static final String[] BUCKET_TYPES = {"general", "image", "voice", "video", "file", "portrait", "favorite", "sticker", "moments"};

    //本域媒体文件可能是私有读，直接下载会403。如果地址匹配本域配置的某个桶地址(media.bucket_*_domain)，
    //用minio客户端签名出一个1小时内可读的临时地址；不匹配或签名失败时返回原地址。
    private String presignLocalMediaUrl(String mediaUrl) {
        for (String type : BUCKET_TYPES) {
            String bucketDomain = environment.getProperty("media.bucket_" + type + "_domain");
            String bucket = environment.getProperty("media.bucket_" + type + "_name");
            if(!StringUtils.hasText(bucketDomain) || !StringUtils.hasText(bucket)) {
                continue;
            }
            String prefix = StringUtils.trimTrailingCharacter(bucketDomain, '/') + "/";
            if(!mediaUrl.startsWith(prefix)) {
                continue;
            }
            String objectName = mediaUrl.substring(prefix.length());
            int queryIndex = objectName.indexOf('?');
            if(queryIndex >= 0) {
                objectName = objectName.substring(0, queryIndex);
            }
            if(!StringUtils.hasText(objectName)) {
                continue;
            }
            try {
                String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(objectName).expiry(1, TimeUnit.HOURS).build());
                LOG.info("presign local media url {} for download", mediaUrl);
                return presignedUrl;
            } catch (Exception e) {
                LOG.warn("presign local media url {} failure, download with original url", mediaUrl, e);
                return mediaUrl;
            }
        }
        return mediaUrl;
    }

    //只允许 http/https，且默认只允许本域媒体桶或显式白名单；拒绝私网/回环/链路本地地址和路径穿越。
    private boolean isAllowedMediaUrl(String mediaUrl) {
        if (!StringUtils.hasText(mediaUrl)) {
            return false;
        }
        try {
            URI uri = new URI(mediaUrl);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            scheme = scheme.toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            String lower = mediaUrl.toLowerCase();
            if (lower.contains("..") || lower.contains("%2e") || lower.contains("%2f")
                || lower.contains("%5c") || mediaUrl.contains("\\") || mediaUrl.contains(";")) {
                return false;
            }
            int schemeIndex = lower.indexOf("://");
            if (schemeIndex < 0 || mediaUrl.substring(schemeIndex + 3).contains("//")) {
                return false;
            }

            URI normalized = uri.normalize();
            String normalizedUrl = normalized.toString();
            if (isLocalBucketUrl(normalizedUrl)) {
                return true;
            }

            if (!"https".equals(scheme) && !(allowHttpDomains && "http".equals(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (!isExplicitlyAllowedHost(host)) {
                return false;
            }
            if (!allowPrivateMediaHosts && isPrivateHost(host)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLocalBucketUrl(String normalizedUrl) {
        for (String type : BUCKET_TYPES) {
            String bucketDomain = environment.getProperty("media.bucket_" + type + "_domain");
            if (!StringUtils.hasText(bucketDomain)) {
                continue;
            }
            String prefix = StringUtils.trimTrailingCharacter(bucketDomain, '/') + "/";
            if (normalizedUrl.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitlyAllowedHost(String host) {
        if (!StringUtils.hasText(mediaDownloadHostWhitelist)) {
            return false;
        }
        for (String allowed : mediaDownloadHostWhitelist.split(",")) {
            String value = allowed.trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            // 支持 host 或 host:port 两种写法；这里只比较主机名
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(']') < 0) {
                value = value.substring(0, colon);
            }
            if (host.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrivateHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || isUniqueLocalIpv6(address)
                    || isCarrierGradeNat(address)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    //传给对端 /transfer_file 的header。x-source-media-url 是本域原始地址，对端用它建立反向映射，
    //这样同一个文件再发回本域时可以直接复用本域地址，不需要重新上传。
    Map<String, String> transferFileHeaders(String mediaUrl, int mediaType) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-media-type", String.valueOf(mediaType));
        headers.put("x-file-extension", extensionOf(mediaUrl));
        String sourceMediaUrl = MediaTransferUtils.encodeSourceUrl(mediaUrl);
        if(sourceMediaUrl != null) {
            headers.put(MediaTransferUtils.SOURCE_MEDIA_URL_HEADER, sourceMediaUrl);
        }
        return headers;
    }

    private static String extensionOf(String mediaUrl) {
        String path = mediaUrl;
        int queryIndex = path.indexOf('?');
        if(queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int dotIndex = path.lastIndexOf('.');
        if(dotIndex > path.lastIndexOf('/') && dotIndex < path.length() - 1) {
            String extension = path.substring(dotIndex);
            //只保留字母数字后缀，防止非法字符导致header非法或者文件名被注入
            if(extension.matches("^\\.[A-Za-z0-9]{1,10}$")) {
                return extension;
            }
        }
        return "";
    }

    public Object onBatchGetUserInfoRequest(String domainId, List<String> userIds) {
        if(userIds == null || userIds.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        List<String> list = new ArrayList<>();
        for (String userId : userIds) {
            list.add(DomainIdUtils.toExternalId(domainId, userId, myDomainId));
        }

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoStringList req = new PojoStringList();
        req.stringList = list;
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/batch_user_infos", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<PojoSearchUserRes> meshRestResult = JsonUtils.fromJsonObject2(content, PojoSearchUserRes.class);
                if(meshRestResult.getResult() != null && meshRestResult.getResult().userInfos != null) {
                    for (InputOutputUserInfo userInfo : meshRestResult.getResult().userInfos) {
                        userInfo.setUserId(DomainIdUtils.toInternalId(domainId, userInfo.getUserId(), myDomainId));
                        userInfo.setName(DomainIdUtils.toInternalId(domainId, userInfo.getName(), myDomainId));
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object onBatchGetGroupInfoRequest(String domainId, List<String> groupIds) {
        if(groupIds == null || groupIds.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        List<String> list = new ArrayList<>();
        for (String groupId : groupIds) {
            list.add(DomainIdUtils.toExternalId(domainId, groupId, myDomainId));
        }

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoStringList req = new PojoStringList();
        req.stringList = list;
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/batch_group_infos", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<PojoGroupInfoList> meshRestResult = JsonUtils.fromJsonObject2(content, PojoGroupInfoList.class);
                if(meshRestResult.getResult() != null && meshRestResult.getResult().getGroupInfoList() != null) {
                    for (PojoGroupInfo groupInfo : meshRestResult.getResult().getGroupInfoList()) {
                        groupInfo.setTarget_id(DomainIdUtils.toInternalId(domainId, groupInfo.getTarget_id(), myDomainId));
                        groupInfo.setOwner(DomainIdUtils.toInternalId(domainId, groupInfo.getOwner(), myDomainId));
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object getGroupMemberRequest(String domainId, String groupId) {
        if(!StringUtils.hasText(groupId)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoString req = new PojoString();
        req.string = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        req.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/get_group_member", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<OutputGroupMemberList> meshRestResult = JsonUtils.fromJsonObject2(content, OutputGroupMemberList.class);
                if(meshRestResult.getResult() != null && meshRestResult.getResult().getMembers() != null) {
                    for (PojoGroupMember groupMember : meshRestResult.getResult().getMembers()) {
                        groupMember.setMember_id(DomainIdUtils.toInternalId(domainId, groupMember.getMember_id(), myDomainId));
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object addGroupMemberRequest(String domainId, String operator, String groupId, List<PojoGroupMember> members) {
        if(!StringUtils.hasText(groupId) || members.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoAddGroupMember object = new PojoAddGroupMember();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;
        object.members = members;
        for (PojoGroupMember member : members) {
            member.setMember_id(DomainIdUtils.toExternalId(domainId, member.getMember_id(), myDomainId));
        }

        HttpUtils.httpPostToDomain(domain, "/add_group_member", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object addJoinGroupRequest(String domainId, String operator, String groupId, List<String> userIds, String reason, String extra) {
        if(!StringUtils.hasText(groupId) || userIds.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoAddJoinGroupRequest object = new PojoAddJoinGroupRequest();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;
        object.reason = reason;
        object.extra = extra;
        object.userIds = new ArrayList<>();
        for (String member : userIds) {
            object.userIds.add(DomainIdUtils.toExternalId(domainId, member, myDomainId));
        }

        HttpUtils.httpPostToDomain(domain, "/add_join_group_request", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object quitGroupRequest(String domainId, String operator, String groupId) {
        if(!StringUtils.hasText(groupId) || !StringUtils.hasText(operator)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoQuitGroup object = new PojoQuitGroup();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/quit_group", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object dismissGroupRequest(String domainId, String operator, String groupId) {
        if(!StringUtils.hasText(groupId) || !StringUtils.hasText(operator)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoDismissGroup object = new PojoDismissGroup();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/dismiss_group", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object kickoffGroupMemberRequest(String domainId, String operator, String groupId, List<String> members) {
        if(!StringUtils.hasText(groupId) || members.isEmpty()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoKickoffGroupMember object = new PojoKickoffGroupMember();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;
        object.members = new ArrayList();
        for (String member : members) {
            object.members.add(DomainIdUtils.toExternalId(domainId, member, myDomainId));
        }

        HttpUtils.httpPostToDomain(domain, "/kickoff_group_member", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object transferGroupRequest(String domainId, String operator, String newOwner, String groupId) {
        if(!StringUtils.hasText(groupId) || !StringUtils.hasText(operator)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoTransferGroup object = new PojoTransferGroup();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.newOwner = DomainIdUtils.toExternalId(domainId, newOwner, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/transfer_group", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object modifyGroupInfoRequest(String domainId, String operator, String groupId, int type, String value) {
        if(!StringUtils.hasText(groupId) || !StringUtils.hasText(operator)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoModifyGroupInfo object = new PojoModifyGroupInfo();
        object.operator = DomainIdUtils.toExternalId(domainId, operator, myDomainId);
        object.group_id = DomainIdUtils.toExternalId(domainId, groupId, myDomainId);
        object.type = type;
        object.value = value;
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/modify_group_info", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object groupUpdated(List<String> domainIds, PojoGroupInfo groupInfo, List<PojoGroupMember> groupMembers) {
        if((domainIds == null || domainIds.isEmpty()) || (groupInfo == null && (groupMembers == null || groupMembers.isEmpty()))) {
            MeshRestResult<Void> meshRestResult = new MeshRestResult<Void>();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        String backupGroupInfoJson = null;
        String backupGroupMemberJson = null;
        Gson gson = null;
        if(domainIds.size() > 1) {
            gson = new Gson();
            backupGroupInfoJson =gson.toJson(groupInfo);
            backupGroupMemberJson = gson.toJson(groupMembers);
        }
        for (String domainId : domainIds) {
            Optional<Domain> optionalDomain = domainRepository.findById(domainId);
            if(!optionalDomain.isPresent()) {
                MeshRestResult<Void> meshRestResult = new MeshRestResult<Void>();
                meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
                return meshRestResult.toString();
            }
            Domain domain = optionalDomain.get();

            PojoGroupUpdated object = new PojoGroupUpdated();
            if(groupInfo != null) {
                groupInfo.setTarget_id(DomainIdUtils.toExternalId(domainId, groupInfo.getTarget_id(), myDomainId));
                groupInfo.setOwner(DomainIdUtils.toExternalId(domainId, groupInfo.getOwner(), myDomainId));
                object.groupInfo = groupInfo;
            }
            if(groupMembers != null) {
                object.members = new ArrayList<>();
                for (PojoGroupMember groupMember : groupMembers) {
                    groupMember.setMember_id(DomainIdUtils.toExternalId(domainId, groupMember.getMember_id(), myDomainId));
                    object.members.add(groupMember);
                }
            }
            object.domainIds = new ArrayList<>();
            object.domainIds.add(myDomainId);

    
            HttpUtils.httpPostToDomain(domain, "/group_updated", object, new HttpUtils.HttpCallback() {
                @Override
                public void onSuccess(String content) {
                    MeshRestResult<Void> meshRestResult = JsonUtils.fromJsonObject2(content, Void.class);

                }

                @Override
                public void onFailure(int statusCode, String errorMessage) {
                    MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);

                }
            });
            if(domainIds.size() > 1) {
                groupInfo = gson.fromJson(backupGroupInfoJson, PojoGroupInfo.class);
                groupMembers = JsonUtils.fromJsonObject3(backupGroupMemberJson, List.class, PojoGroupMember.class);
            }
        }

        return new MeshRestResult<Void>().toString();
    }

    private String convertConferenceRequestDomainId(String jsonStr, String domainId, String myDomainId) {
        if(TextUtils.isEmpty(jsonStr)) {
            return jsonStr;
        }
        JsonObject object = (JsonObject) new JsonParser().parse(jsonStr);
        if(object.has("user_id")) {
            String user_id = object.get("user_id").getAsString();
            object.addProperty("user_id", DomainIdUtils.toExternalId(domainId, user_id, myDomainId));
        }

        if(object.has("userId")) {
            String userId = object.get("userId").getAsString();
            object.addProperty("userId", DomainIdUtils.toExternalId(domainId, userId, myDomainId));
        }

        if(object.has("feed_id")) {
            String feed_id = object.get("feed_id").getAsString();
            object.addProperty("feed_id", DomainIdUtils.toExternalId(domainId, feed_id, myDomainId));
        }

        if(object.has("feedId")) {
            String feedId = object.get("feedId").getAsString();
            object.addProperty("feedId", DomainIdUtils.toExternalId(domainId, feedId, myDomainId));
        }

        if(object.has("id")) {
            String id = object.get("id").getAsString();
            object.addProperty("id", DomainIdUtils.toExternalId(domainId, id, myDomainId));
        }

        if(object.has("fromUser")) {
            String fromUser = object.get("fromUser").getAsString();
            object.addProperty("fromUser", DomainIdUtils.toExternalId(domainId, fromUser, myDomainId));
        }

        if(object.has("streams") && object.get("streams") instanceof JsonArray) {
            JsonArray streams = object.get("streams").getAsJsonArray();
            for (JsonElement stream : streams) {
                if(stream.getAsJsonObject().has("feed")) {
                    String feed = stream.getAsJsonObject().get("feed").getAsString();
                    stream.getAsJsonObject().addProperty("feed", DomainIdUtils.toExternalId(domainId, feed, myDomainId));
                }
            }
        }
        if(object.has("feed")) {
            String feed = object.get("feed").getAsString();
            object.addProperty("feed", DomainIdUtils.toExternalId(domainId, feed, myDomainId));
        }

        return object.toString();
    }

    public Object conferenceRequest(String domainId, String clientID, String fromUser, String request, long sessionId, String roomId, String data, boolean advanced) {
        if(!StringUtils.hasText(fromUser) || !StringUtils.hasText(request)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoUserConferenceRequest object = new PojoUserConferenceRequest();
        object.fromUser = DomainIdUtils.toExternalId(domainId, fromUser, myDomainId);
        object.roomId = DomainIdUtils.toExternalId(domainId, roomId, myDomainId);
        object.clientID = DomainIdUtils.toExternalId(domainId, clientID, myDomainId);
        object.request = request;
        object.sessionId = sessionId;
        object.data = convertConferenceRequestDomainId(data, domainId, myDomainId);
        object.advanced = advanced;
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/conference_request", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
                if(meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0 && meshRestResult.getLocal_mesh_code() == 0) {
                    if(!TextUtils.isEmpty(meshRestResult.getResult())) {
                        JsonObject object = (JsonObject) new JsonParser().parse(meshRestResult.getResult());
                        JsonObject data = object.getAsJsonObject("data");
                        if(data != null) {
                            if (data.has("room")) {
                                String room = data.get("room").getAsString();
                                data.addProperty("room", DomainIdUtils.toInternalId(domainId, room, myDomainId));
                            }
                            if (data.has("attendees") && data.get("attendees") instanceof JsonArray) {
                                JsonArray attendees = data.getAsJsonArray("attendees");
                                if (attendees != null) {
                                    JsonArray newAttendees = new JsonArray();
                                    for (JsonElement attendee : attendees) {
                                        String userId = attendee.getAsString();
                                        newAttendees.add(DomainIdUtils.toInternalId(domainId, userId, myDomainId));
                                    }
                                    data.add("attendees", newAttendees);
                                }
                            }
                            if (data.has("publishers") && data.get("publishers") instanceof JsonArray) {
                                JsonArray publishers = data.getAsJsonArray("publishers");
                                if (publishers != null) {
                                    for (JsonElement publisher : publishers) {
                                        String id = publisher.getAsJsonObject().get("id").getAsString();
                                        publisher.getAsJsonObject().addProperty("id", DomainIdUtils.toInternalId(domainId, id, myDomainId));
                                    }
                                }
                            }
                            if (data.has("id")) {
                                String id = data.get("id").getAsString();
                                data.addProperty("id", DomainIdUtils.toInternalId(domainId, id, myDomainId));
                            }
                            meshRestResult.setResult(object.toString());
                        }
                    }
                }
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    public Object conferenceEvent(String domainId, String data, String userId, String clientId, boolean isRobot) {
        if(!StringUtils.hasText(userId) || !StringUtils.hasText(clientId)) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_INVALID_PARAMETER.code, ERROR_INVALID_PARAMETER.msg);
            return meshRestResult.toString();
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if(!optionalDomain.isPresent()) {
            MeshRestResult meshRestResult = new MeshRestResult();
            meshRestResult.addLocalMeshError(ERROR_NOT_EXIST.code, "服务实体（" + domainId + ")不存在");
            return meshRestResult.toString();
        }
        Domain domain = optionalDomain.get();

        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoUserConferenceEvent object = new PojoUserConferenceEvent();
        object.userId = DomainIdUtils.toExternalId(domainId, userId, myDomainId);
        object.clientId = DomainIdUtils.toExternalId(domainId, clientId, myDomainId);
        object.data = convertConferenceEventDomainId(data, domainId, myDomainId);
        object.isRobot = isRobot;
        object.domainId = myDomainId;

        HttpUtils.httpPostToDomain(domain, "/conference_event", object, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(content, String.class);
                deferredResult.setResult(meshRestResult.toString());
            }

            @Override
            public void onFailure(int statusCode, String errorMessage) {
                MeshRestResult meshRestResult = MeshRestResult.localMeshError(ERROR_SERVER_ERROR.code, "Http request error:" + statusCode + ". message:" + errorMessage);
                deferredResult.setResult(meshRestResult.toString());
            }
        });

        return deferredResult;
    }

    //{"data":{"videoroom":"event","publishers":[{"streams":[{"codec":"opus","fec":true,"mid":"0","type":"audio","mindex":0}],"id":"nygqmws2k","audio_codec":"opus"}],"room":"nygqmws2k|138232873"},"sender":6015659038004548,"session_id":4715363580824409}
    private String convertConferenceEventDomainId(String jsonStr, String domainId, String myDomainId) {
        JsonObject object = (JsonObject) new JsonParser().parse(jsonStr);
        JsonObject data = object.getAsJsonObject("data");
        if(data == null) {
            return jsonStr;
        }
        String videoRoom = data.get("videoroom").getAsString();
        if(videoRoom.equals("event")) {
            if(data.has("publishers") && data.get("publishers") instanceof JsonArray) {
                JsonArray publishers = data.getAsJsonArray("publishers");
                if(publishers != null) {
                    for (JsonElement publisher : publishers) {
                        String id = publisher.getAsJsonObject().get("id").getAsString();
                        publisher.getAsJsonObject().addProperty("id", DomainIdUtils.toExternalId(domainId, id, myDomainId));
                    }
                }
            }
            if(data.has("unpublished")) {
                JsonObject unpublished = data.get("unpublished").getAsJsonObject();
                unpublished.addProperty("id", DomainIdUtils.toExternalId(domainId, unpublished.get("id").getAsString(), myDomainId));
            }
            if(data.has("leaving")) {
                JsonObject leaving = data.get("leaving").getAsJsonObject();
                leaving.addProperty("id", DomainIdUtils.toExternalId(domainId, leaving.get("id").getAsString(), myDomainId));
            }
            if(data.has("kicked")) {
                JsonObject kicked = data.get("kicked").getAsJsonObject();
                kicked.addProperty("id", DomainIdUtils.toExternalId(domainId, kicked.get("id").getAsString(), myDomainId));
            }
            if(data.has("joining")) {
                JsonObject joining = data.get("joining").getAsJsonObject();
                joining.addProperty("id", DomainIdUtils.toExternalId(domainId, joining.get("id").getAsString(), myDomainId));
            }
            if(data.has("room")) {
                String room = data.get("room").getAsString();
                data.addProperty("room", DomainIdUtils.toExternalId(domainId, room, myDomainId));
            }
        } else if("participants".equals(videoRoom)) {
            if(data.has("attendees") && data.get("attendees") instanceof JsonArray) {
                JsonArray attendees = data.getAsJsonArray("attendees");
                if(attendees != null) {
                    JsonArray newAttendees = new JsonArray();
                    for (JsonElement attendee : attendees) {
                        String userId = attendee.getAsString();
                        newAttendees.add(DomainIdUtils.toExternalId(domainId, userId, myDomainId));
                    }
                    data.add("attendees", newAttendees);
                }
            }
            if(data.has("leavings") && data.get("leavings") instanceof JsonArray) {
                JsonArray leavings = data.getAsJsonArray("leavings");
                if(leavings != null) {
                    JsonArray newLeavings = new JsonArray();
                    for (JsonElement leaving : leavings) {
                        String userId = leaving.getAsString();
                        newLeavings.add(userId);
                    }
                    data.add("leavings", newLeavings);
                }
            }
        } else if("mute".equals(videoRoom)) {
            JsonObject mute = data.getAsJsonObject("mute");
            JsonObject newMute = new JsonObject();
            for (Map.Entry<String, JsonElement> stringJsonElementEntry : mute.entrySet()) {
                String key = DomainIdUtils.toExternalId(domainId, stringJsonElementEntry.getKey(), myDomainId);
                newMute.add(key, stringJsonElementEntry.getValue());
            }
            data.add("mute", newMute);
        }

        return object.toString();
    }
}
