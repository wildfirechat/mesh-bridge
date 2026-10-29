package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.controller.InternalController;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.bridge.jpa.InMessageIds;
import cn.wildfirechat.bridge.jpa.InMessageIdsKey;
import cn.wildfirechat.bridge.jpa.InMessageIdsRepository;
import cn.wildfirechat.bridge.jpa.OutMessageIds;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.bridge.utilis.LimitedInputStream;
import cn.wildfirechat.pojos.*;
import cn.wildfirechat.pojos.mesh.*;
import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.sdk.MeshAdmin;
import cn.wildfirechat.sdk.MessageAdmin;
import cn.wildfirechat.sdk.RelationAdmin;
import cn.wildfirechat.sdk.messagecontent.MessageContent;
import cn.wildfirechat.sdk.messagecontent.MessageContentFactory;
import cn.wildfirechat.sdk.messagecontent.TextMessageContent;
import cn.wildfirechat.sdk.model.IMResult;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InService {
    private static final Logger LOG = LoggerFactory.getLogger(InService.class);

    @Value("${minio.endpoint}")
    String minioEndpoint;

    @Value("${minio.access_key}")
    String minioAccessKey;

    @Value("${minio.secret_key}")
    String minioSecretKey;

    //允许接收的文件最大大小(MB)，0表示不限制
    @Value("${bridge.file_transfer_max_size_mb:100}")
    long fileTransferMaxSizeMb;

    @Autowired
    Environment environment;

    MinioClient minioClient;

    //已经确认存在的桶，避免每次上传都去 bucketExists/makeBucket
    private final Set<String> knownBuckets = ConcurrentHashMap.newKeySet();

    private final Object bucketLock = new Object();

    @Value("${bridge.my_domain_id}")
    String myDomainId;

    @Value("${bridge.api_threads:32}")
    int apiThreads;

    @Value("${bridge.api_queue:1000}")
    int apiQueue;

    ExecutorService apiExecutor;

    //这些类型可能被浏览器当作脚本执行，不能按对端声明的类型保存
    private static final Set<String> UNSAFE_CONTENT_TYPES = new HashSet<>(Arrays.asList(
        "text/html", "application/xhtml+xml", "image/svg+xml",
        "text/javascript", "application/javascript", "application/x-javascript",
        "application/xml", "text/xml", "text/css"
    ));

    @PostConstruct
    void init() {
        minioClient = MinioClient.builder().endpoint(minioEndpoint).credentials(minioAccessKey, minioSecretKey).build();
        int threads = apiThreads > 0 ? apiThreads : 32;
        int queue = apiQueue > 0 ? apiQueue : 1000;
        AtomicInteger index = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "mesh-api-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        apiExecutor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queue), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void destroy() {
        if (apiExecutor != null) {
            apiExecutor.shutdownNow();
        }
    }

    private void runAsync(DeferredResult<String> deferredResult, Runnable task) {
        try {
            apiExecutor.execute(() -> {
                try {
                    task.run();
                } catch (IllegalArgumentException e) {
                    LOG.warn("reject invalid bridge request: {}", e.getMessage());
                    setError(deferredResult, MeshRestResult.MeshRestCode.ERROR_INVALID_PARAMETER.code, "请求参数不合法");
                } catch (Throwable t) {
                    LOG.error("process bridge request failed", t);
                    setError(deferredResult, MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.error("bridge api thread pool is full");
            setError(deferredResult, MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务繁忙，请稍后重试");
        }
    }

    private void setError(DeferredResult<String> deferredResult, int code, String message) {
        if (!deferredResult.isSetOrExpired()) {
            deferredResult.setResult(MeshRestResult.remoteMeshError(code, message).toString());
        }
    }

    private void requireDomainId(String domainId) {
        if (!StringUtils.hasText(domainId)) {
            throw new IllegalArgumentException("domainId is empty");
        }
    }

    /**
     * 请求中的发送者/操作者必须是带域的外部 ID，且不能冒充本域用户。
     * 这阻止了对端把 sender/operator 写成 admin 之类的本地用户 ID。
     */
    private String requireExternalActor(String domainId, String id, String field) {
        requireDomainId(domainId);
        if (!StringUtils.hasText(id) || !DomainIdUtils.isExternalId(id)) {
            throw new IllegalArgumentException(field + " must be an external id");
        }
        String actorDomain = DomainIdUtils.getExternalId(id);
        if (myDomainId.equals(actorDomain)) {
            throw new IllegalArgumentException(field + " must not be a local user");
        }
        return id;
    }

    /**
     * 直接请求（好友、群操作、会议请求等）要求操作者属于请求方域，不能伪造其他域用户。
     */
    private String requireRequesterActor(String domainId, String id, String field) {
        String actor = requireExternalActor(domainId, id, field);
        if (!domainId.equals(DomainIdUtils.getExternalId(actor))) {
            throw new IllegalArgumentException(field + " must belong to the requesting domain");
        }
        return actor;
    }

    /**
     * 目标/群/成员 ID 允许是本域 ID（去掉 @本域 后缀），也允许是外部 ID；裸 ID 视为本域。
     */
    private String normalizeTargetId(String id) {
        if (!StringUtils.hasText(id) || !DomainIdUtils.isExternalId(id)) {
            return id;
        }
        String domainId = DomainIdUtils.getExternalId(id);
        if (myDomainId.equals(domainId)) {
            return DomainIdUtils.toInternalId(myDomainId, id, myDomainId);
        }
        return id;
    }

    private void requireGroupDomain(String domainId, String groupId) {
        requireDomainId(domainId);
        if (!StringUtils.hasText(groupId) || !DomainIdUtils.isExternalId(groupId)) {
            throw new IllegalArgumentException("groupId must be an external id");
        }
        String groupDomain = DomainIdUtils.getExternalId(groupId);
        if (!myDomainId.equals(groupDomain) && !domainId.equals(groupDomain)) {
            throw new IllegalArgumentException("group does not belong to this domain");
        }
    }

    private void requireMessageBelongsToDomain(String domainId, long messageId) {
        // 请求里的 messageId 是本域消息 ID：要么是本域收到的对端消息（in 表按 local_message_id 反查），
        // 要么是本域发给对端的消息（out 表按本域 message_id 查）。
        boolean incoming = inMessageIdsRepository.findByDomainIdAndLocalMessageId(domainId, messageId).isPresent();
        boolean outgoing = outMessageIdsRepository.findByMessageIdAndToDomainId(messageId, domainId).isPresent();
        if (!incoming && !outgoing) {
            throw new IllegalArgumentException("message does not belong to this domain");
        }
    }

    private List<String> normalizeTargets(List<String> ids) {
        if (ids == null) {
            return null;
        }
        List<String> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(normalizeTargetId(id));
        }
        return result;
    }

    private List<PojoGroupMember> normalizeMembers(List<PojoGroupMember> members) {
        if (members == null) {
            return null;
        }
        for (PojoGroupMember member : members) {
            if (member != null) {
                member.setMember_id(normalizeTargetId(member.getMember_id()));
            }
        }
        return members;
    }

    @Autowired
    OutMessageIdsRepository outMessageIdsRepository;

    @Autowired
    InMessageIdsRepository inMessageIdsRepository;

    public Object onPing(String domainId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireDomainId(domainId);
                IMResult<InputOutputDomainInfo> imResult = MeshAdmin.getDomain(domainId);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result.name);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onSearchUser(String keyword, int searchType, int userType, int page) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                IMResult<PojoSearchUserRes> imResult = MeshAdmin.searchUser(keyword, searchType, userType, page);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onAddFriendRequest(String domainId, String fromUserId, String reason, String targetUid) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, fromUserId, "fromUserId");
                String safeTargetUid = normalizeTargetId(targetUid);
                IMResult<Void> imResult = MeshAdmin.sendFriendRequest(fromUserId, safeTargetUid, reason);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok();
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onHandleFriendRequest(String domainId, String fromUserId, int status, String targetUid) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, fromUserId, "fromUserId");
                String safeTargetUid = normalizeTargetId(targetUid);
                IMResult<Void> imResult = MeshAdmin.handleFriendRequest(fromUserId, safeTargetUid, status);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok();
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onDeleteFriend(String domainId, String fromUserId, String friendUid) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, fromUserId, "fromUserId");
                String safeFriendUid = normalizeTargetId(friendUid);
                IMResult<Void> imResult = RelationAdmin.setUserFriend(fromUserId, safeFriendUid, false, "");
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok();
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onSendMessageRequest(String domainId, long messageId, SendMessageData messageData, String sendClientId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (messageData == null || messageData.getConv() == null || messageData.getPayload() == null) {
                    throw new IllegalArgumentException("messageData is empty");
                }
                requireRequesterActor(domainId, messageData.getSender(), "sender");
                messageData.getConv().setTarget(normalizeTargetId(messageData.getConv().getTarget()));
                List<String> safeToUsers = normalizeTargets(messageData.getToUsers());
                IMResult<SendMessageResult> imResult = MeshAdmin.sendMessage(messageData.getSender(), messageData.getConv(), messageData.getPayload(), safeToUsers);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    InMessageIdsKey key = new InMessageIdsKey(messageId, domainId);
                    InMessageIds inMessageIds = new InMessageIds();
                    inMessageIds.id = key;
                    inMessageIds.localMessageId = imResult.getResult().getMessageUid();
                    inMessageIdsRepository.save(inMessageIds);
                    LOG.info("send in message success, from messageId {}, to messageId {}", messageId, imResult.getResult().getMessageUid());
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onPublishMessageRequest(String domainId, long messageId, SendMessageData messageData, List<String> receivers, boolean republish) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (messageData == null || messageData.getConv() == null || messageData.getPayload() == null) {
                    throw new IllegalArgumentException("messageData is empty");
                }
                if (receivers == null || receivers.isEmpty()) {
                    throw new IllegalArgumentException("receivers is empty");
                }
                requireExternalActor(domainId, messageData.getSender(), "sender");
                messageData.getConv().setTarget(normalizeTargetId(messageData.getConv().getTarget()));
                List<String> safeReceivers = normalizeTargets(receivers);
                // republish消息是消息删除/撤回/更正后，重新发送的
                if(republish) {
                    InMessageIdsKey key = new InMessageIdsKey(messageId, domainId);
                    Optional<InMessageIds> inMessageIdsOptional = inMessageIdsRepository.findById(key);
                    boolean inExist = true;
                    if(inMessageIdsOptional.isPresent()) {
                        if(messageData.getPayload().getType() == 80 || messageData.getPayload().getType() == 81) {
                            String messageUidStr = inMessageIdsOptional.get().localMessageId + "";
                            messageData.getPayload().setBase64edData(Base64.getEncoder().encodeToString(messageUidStr.getBytes(StandardCharsets.UTF_8)));
                        }
                        IMResult<Void> imResult = MeshAdmin.updateMessageContent(messageData.getSender(), inMessageIdsOptional.get().localMessageId, messageData.getPayload(), true);
                        if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                            SendMessageResult sendMessageResult = new SendMessageResult();
                            sendMessageResult.setMessageUid(inMessageIdsOptional.get().localMessageId);
                            sendMessageResult.setTimestamp(System.currentTimeMillis());
                            restResult = MeshRestResult.ok(sendMessageResult);
                        } else {
                            restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                        }
                    } else {
                        restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_NOT_EXIST.code, "消息不存在");
                        inExist = false;
                    }

                    Optional<OutMessageIds> outMessageOptional = outMessageIdsRepository.findByToDomainIdAndToMessageId(domainId, messageId);
                    if(outMessageOptional.isPresent()) {
                        if(StringUtils.hasText(messageData.getPayload().getRemoteMediaUrl()) && StringUtils.hasText(outMessageOptional.get().mediaUrl) && !outMessageOptional.get().mediaUrl.equals(messageData.getPayload().getRemoteMediaUrl())) {
                            //如果是从本域发出的再publish回来的消息，要把媒体地址改成本域里保存的地址，可以参考 convertMessagePayloadDomainId 方法里的对文件消息的处理
                            messageData.getPayload().setRemoteMediaUrl(outMessageOptional.get().mediaUrl);
                        }
                        IMResult<Void> imResult = MeshAdmin.updateMessageContent(messageData.getSender(), outMessageOptional.get().messageId, messageData.getPayload(), true);
                        if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                            SendMessageResult sendMessageResult = new SendMessageResult();
                            sendMessageResult.setMessageUid(outMessageOptional.get().messageId);
                            sendMessageResult.setTimestamp(System.currentTimeMillis());
                            restResult = MeshRestResult.ok(sendMessageResult);
                        } else {
                            restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                        }
                    } else {
                        if(!inExist) {
                            restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_NOT_EXIST.code, "消息不存在");
                        }
                    }
                } else {
                    Optional<OutMessageIds> outMessageOptional = outMessageIdsRepository.findByToDomainIdAndToMessageId(domainId, messageId);
                    //只有"本域发出、被对端 publish 回本域"的消息才有对应记录，对端自己发起的消息没有记录，不能直接 get()
                    if(outMessageOptional.isPresent() && StringUtils.hasText(messageData.getPayload().getRemoteMediaUrl()) && StringUtils.hasText(outMessageOptional.get().mediaUrl) && !outMessageOptional.get().mediaUrl.equals(messageData.getPayload().getRemoteMediaUrl())) {
                        //如果是从本域发出的再publish回来的消息，要把媒体地址改成本域里保存的地址，可以参考 convertMessagePayloadDomainId 方法里的对文件消息的处理
                        messageData.getPayload().setRemoteMediaUrl(outMessageOptional.get().mediaUrl);
                    }
                    IMResult<SendMessageResult> imResult = MeshAdmin.publishMessage(messageData, safeReceivers, outMessageOptional.isPresent()?outMessageOptional.get().messageId:0);
                    if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                        restResult = MeshRestResult.ok(imResult.result);
                        // if ((messageData.getPayload().getPersistFlag() & 0x01) > 0) {
                        // 正常情况下，应该根据存储标记判断是否记录消息id对应关系，但server api很多情况下忽略了存储标记，导致server api发送的消息撤回/删除失败
                        // 所以这里把透传消息以外的所有消息都记录下来。
                        if (messageData.getPayload().getPersistFlag() != ProtoConstants.MessagePersistFlag.TRANSPARENT) {
                            InMessageIdsKey key = new InMessageIdsKey(messageId, domainId);
                            InMessageIds inMessageIds = new InMessageIds();
                            inMessageIds.id = key;
                            inMessageIds.localMessageId = imResult.getResult().getMessageUid();
                            inMessageIdsRepository.save(inMessageIds);
                            LOG.info("publish in message success, from messageId {}, to messageId {}", messageId, imResult.getResult().getMessageUid());
                        }
                    } else {
                        restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                    }
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onRecallMessageRequest(String domainId, long messageId, String operator, boolean isSenderRecall) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireExternalActor(domainId, operator, "operator");
                requireMessageBelongsToDomain(domainId, messageId);
                IMResult<String> imResult = MeshAdmin.recallMessage(operator, messageId);
                if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    //接收对端传过来的文件流，不落地，边接收边上传到minio
    public Object onTransferFileRequest(String domainId, InputStream inputStream, String extension, String contentType, int mediaType, long contentLength) {
        MeshRestResult restResult;
        try {
            extension = normalizeExtension(extension);
            if(fileTransferMaxSizeMb > 0) {
                long maxBytes = fileTransferMaxSizeMb * 1024 * 1024;
                if(contentLength > maxBytes) {
                    LOG.error("transfer file from domain {} rejected, contentLength {} exceeds limit {}", domainId, contentLength, maxBytes);
                    restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_INVALID_PARAMETER.code, "文件超过大小限制 " + fileTransferMaxSizeMb + "MB");
                    return restResult.toString();
                }
                inputStream = new LimitedInputStream(inputStream, maxBytes);
            }
            String url = uploadToMinio(inputStream, extension, contentType, mediaType);
            LOG.info("transfer file from domain {} success, uploaded to {}", domainId, url);
            restResult = MeshRestResult.ok(url);
        } catch (Exception e) {
            LOG.error("transfer file from domain {} failure", domainId, e);
            //不要把内部错误细节(如对象存储地址、桶名)返回给对端
            restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "transfer file failure");
        }
        return restResult.toString();
    }

    private String normalizeExtension(String extension) {
        //后缀只保留字母数字，防止非法字符被注入到文件名
        if(StringUtils.hasText(extension) && extension.matches("^\\.?[A-Za-z0-9]{1,10}$")) {
            return extension.startsWith(".") ? extension : "." + extension;
        }
        return "";
    }

    //文件名使用随机uuid，后缀名跟原文件保持一致。根据mediaType选择对应的桶，返回的地址用配置的bucket_domain而不是minio.endpoint拼接
    private String uploadToMinio(InputStream inputStream, String extension, String contentType, int mediaType) throws Exception {
        String type = resolveBucketType(mediaType);
        String bucket = environment.getProperty("media.bucket_" + type + "_name");
        String bucketDomain = environment.getProperty("media.bucket_" + type + "_domain");
        if(!StringUtils.hasText(bucket) || !StringUtils.hasText(bucketDomain)) {
            throw new IllegalStateException("mediaType " + mediaType + " 对应的媒体桶没有配置，请检查配置 media.bucket_*_name 和 media.bucket_*_domain");
        }
        ensureBucket(bucket);
        String objectName = UUID.randomUUID().toString() + (extension == null ? "" : extension);
        contentType = sanitizeContentType(contentType, extension == null ? "" : extension, mediaType);
        //文件大小未知，使用分片上传
        minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName).stream(inputStream, -1, 10 * 1024 * 1024).contentType(contentType).build());
        return StringUtils.trimTrailingCharacter(bucketDomain, '/') + "/" + objectName;
    }

    //确认桶存在，按桶缓存结果，避免每次上传都做一次 bucketExists；并发建桶时容忍"已被其他线程创建"
    private void ensureBucket(String bucket) throws Exception {
        if(knownBuckets.contains(bucket)) {
            return;
        }
        synchronized (bucketLock) {
            if(knownBuckets.contains(bucket)) {
                return;
            }
            if(!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    LOG.info("created minio bucket {}", bucket);
                } catch (Exception e) {
                    if(!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                        throw e;
                    }
                }
            }
            knownBuckets.add(bucket);
        }
    }

    //mediaType: 0通用 1图片 2语音 3视频 4文件 5头像 6收藏 7表情 8朋友圈
    private String resolveBucketType(int mediaType) {
        //name 和 domain 必须成对回退：只有专用桶配了 name 才用专用桶，否则整体回退到通用桶
        String type = bucketTypeOf(mediaType);
        if(!"general".equals(type) && StringUtils.hasText(environment.getProperty("media.bucket_" + type + "_name"))) {
            return type;
        }
        return "general";
    }

    private String bucketTypeOf(int mediaType) {
        switch (mediaType) {
            case 1:
                return "image";
            case 2:
                return "voice";
            case 3:
                return "video";
            case 4:
                return "file";
            case 5:
                return "portrait";
            case 6:
                return "favorite";
            case 7:
                return "sticker";
            case 8:
                return "moments";
            default:
                return "general";
        }
    }

    //对端声明的 content-type 不能完全信任，html/svg 等类型可能被浏览器当脚本执行，出现存储型XSS
    private String sanitizeContentType(String contentType, String extension, int mediaType) {
        if(StringUtils.hasText(contentType)) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                if(parsed.getType() != null && parsed.getSubtype() != null) {
                    String type = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase();
                    if(!UNSAFE_CONTENT_TYPES.contains(type)) {
                        return type;
                    }
                }
            } catch (Exception ignore) {
                //类型非法时走下面的兜底逻辑
            }
        }
        return contentTypeOf(extension, mediaType);
    }

    private String contentTypeOf(String extension, int mediaType) {
        String ext = extension.toLowerCase();
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".mp4":
                return "video/mp4";
            case ".mp3":
                return "audio/mpeg";
            case ".amr":
                return "audio/amr";
            default:
                break;
        }
        //根据payload中的mediaType判断，mediaType: 1图片 2语音 3视频 4文件
        switch (mediaType) {
            case 1:
                return "image/jpeg";
            case 2:
                return "audio/amr";
            case 3:
                return "video/mp4";
            default:
                return "application/octet-stream";
        }
    }

    public Object onBatchGetUserInfoRequest(String domainId, List<String> userIds) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (userIds == null || userIds.isEmpty()) {
                    throw new IllegalArgumentException("userIds is empty");
                }
                List<String> safeUserIds = normalizeTargets(userIds);
                IMResult<OutputUserInfoList> imResult = MeshAdmin.getBatchUserInfos(safeUserIds);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onBatchGetGroupInfoRequest(String domainId, List<String> groupIds) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (groupIds == null || groupIds.isEmpty()) {
                    throw new IllegalArgumentException("groupIds is empty");
                }
                for (String id : groupIds) {
                    requireGroupDomain(domainId, id);
                }
                List<String> safeGroupIds = normalizeTargets(groupIds);
                IMResult<PojoGroupInfoList> imResult = MeshAdmin.batchGroupInfos(safeGroupIds);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object getGroupMemberRequest(String domainId, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                IMResult<OutputGroupMemberList> imResult = MeshAdmin.getGroupMembers(safeGroupId);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }


    public Object addGroupMemberRequest(String domainId, String operator, String groupId, List<PojoGroupMember> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (members == null || members.isEmpty()) {
                    throw new IllegalArgumentException("members is empty");
                }
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                List<PojoGroupMember> safeMembers = normalizeMembers(members);
                IMResult<Void> imResult = MeshAdmin.addGroupMembers(operator, safeGroupId, safeMembers, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object addJoinGroupRequest(String domainId, String operator, String groupId, List<String> userIds, String reason, String extra) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (userIds == null || userIds.isEmpty()) {
                    throw new IllegalArgumentException("userIds is empty");
                }
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                List<String> safeUserIds = normalizeTargets(userIds);
                IMResult<Void> imResult = MeshAdmin.addJoinGroupRequest(operator, safeGroupId, safeUserIds, reason, extra);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object quitGroupRequest(String domainId, String operator, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                IMResult<Void> imResult = MeshAdmin.quitGroup(operator, safeGroupId, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object dismissGroupRequest(String domainId, String operator, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                IMResult<Void> imResult = MeshAdmin.dismissGroup(operator, safeGroupId, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object kickoffGroupMemberRequest(String domainId, String operator, String groupId, List<String> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                if (members == null || members.isEmpty()) {
                    throw new IllegalArgumentException("members is empty");
                }
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                List<String> safeMembers = normalizeTargets(members);
                IMResult<Void> imResult = MeshAdmin.kickoffGroupMembers(operator, safeGroupId, safeMembers, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object transferGroupRequest(String domainId, String operator, String newOwner, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                String safeNewOwner = normalizeTargetId(newOwner);
                IMResult<Void> imResult = MeshAdmin.transferGroup(operator, safeGroupId, safeNewOwner, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object modifyGroupInfoRequest(String domainId, String operator, String groupId, int type, String value) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, operator, "operator");
                requireGroupDomain(domainId, groupId);
                String safeGroupId = normalizeTargetId(groupId);
                IMResult<Void> imResult = MeshAdmin.modifyGroupInfo(operator, safeGroupId, type, value, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object groupUpdated(String domainId, PojoGroupInfo groupInfo, List<PojoGroupMember> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireDomainId(domainId);
                if (groupInfo == null || !DomainIdUtils.isExternalId(groupInfo.getTarget_id())
                    || !domainId.equals(DomainIdUtils.getExternalId(groupInfo.getTarget_id()))) {
                    throw new IllegalArgumentException("groupUpdated must target a group owned by the requesting domain");
                }
                groupInfo.setTarget_id(normalizeTargetId(groupInfo.getTarget_id()));
                groupInfo.setOwner(normalizeTargetId(groupInfo.getOwner()));
                List<PojoGroupMember> safeMembers = normalizeMembers(members);
                IMResult<Void> imResult = MeshAdmin.syncGroup(groupInfo, safeMembers);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object conferenceRequest(String domainId, String clientID, String fromUser, String request, long sessionId, String roomId, String data, boolean advanced) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, fromUser, "fromUser");
                IMResult<PojoUserConferenceResponse> imResult = MeshAdmin.userConferenceRequest(clientID, fromUser, request, sessionId, roomId, data, advanced);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result.data);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object conferenceEvent(String domainId, String data, String userId, String clientId, boolean isRobot) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireExternalActor(domainId, userId, "userId");
                IMResult<Void> imResult = MeshAdmin.userConferenceEvent(data, userId, clientId, isRobot);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                LOG.error("bridge request failed", e);
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "服务内部错误");
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }


}
