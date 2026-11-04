package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.controller.InternalController;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.bridge.jpa.InMessageIds;
import cn.wildfirechat.bridge.jpa.InMessageIdsKey;
import cn.wildfirechat.bridge.jpa.InMessageIdsRepository;
import cn.wildfirechat.bridge.jpa.OutMessageIds;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.bridge.utilis.CompositeMessageUtils;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.bridge.utilis.MediaTransferUtils;
import cn.wildfirechat.bridge.utilis.LimitedInputStream;
import cn.wildfirechat.pojos.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    //uid 批量查询时一次最多带多少个消息ID，避免 in 列表过长
    private static final int MESSAGE_ID_BATCH_SIZE = 500;

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

    /**
     * 撤回权限：操作者必须是原发送者，或者是该群的群主/管理员。
     * IM Server API 仍会做最终权限校验，这里是桥接层的 fail-closed 前置校验。
     */
    private boolean canRecallMessage(long messageId, String operator) {
        try {
            IMResult<OutputMessageData> messageResult = MessageAdmin.getMessage(messageId);
            if (messageResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS || messageResult.getResult() == null) {
                return false;
            }
            OutputMessageData message = messageResult.getResult();
            String normalizedOperator = normalizeTargetId(operator);
            String normalizedSender = normalizeTargetId(message.getSender());
            if (normalizedOperator != null && normalizedOperator.equals(normalizedSender)) {
                return true;
            }
            if (message.getConv() == null || message.getConv().getType() != ProtoConstants.ConversationType.ConversationType_Group) {
                return false;
            }
            String groupId = normalizeTargetId(message.getConv().getTarget());
            if (!StringUtils.hasText(groupId)) {
                return false;
            }
            IMResult<OutputGroupMemberList> membersResult = MeshAdmin.getGroupMembers(groupId);
            if (membersResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS || membersResult.getResult() == null
                || membersResult.getResult().getMembers() == null) {
                return false;
            }
            for (PojoGroupMember member : membersResult.getResult().getMembers()) {
                if (member == null) {
                    continue;
                }
                String memberId = normalizeTargetId(member.getMember_id());
                if (normalizedOperator != null && normalizedOperator.equals(memberId)
                    && (member.getType() == ProtoConstants.GroupMemberType.GroupMemberType_Owner
                    || member.getType() == ProtoConstants.GroupMemberType.GroupMemberType_Manager)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.warn("check recall permission failed, messageId={}", messageId, e);
            return false;
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
    MediaUrlMappingService mediaUrlMappingService;

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
                restoreCompositeMediaUrls(domainId, messageData.getPayload());
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
                restoreCompositeMediaUrls(domainId, messageData.getPayload());
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
                        restoreLocalMediaUrl(domainId, messageData.getPayload(), outMessageOptional.get().mediaUrl);
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
                    restoreLocalMediaUrl(domainId, messageData.getPayload(), outMessageOptional.map(outMessageIds -> outMessageIds.mediaUrl).orElse(null));
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

    //恢复组合消息里对端视角的媒体地址和子消息ID。恢复失败不影响消息投递(最多是地址指向对端存储)，所以这里只记录日志。
    private void restoreCompositeMediaUrls(String domainId, MessagePayload payload) {
        if(!CompositeMessageUtils.isComposite(payload) || !StringUtils.hasText(payload.getBase64edData())) {
            return;
        }
        try {
            JsonObject composite = CompositeMessageUtils.decode(payload.getBase64edData());
            if(restoreCompositeMessages(domainId, CompositeMessageUtils.messages(composite), 0)) {
                payload.setBase64edData(CompositeMessageUtils.encode(composite));
            }
        } catch (Exception e) {
            LOG.warn("restore composite message domain ids failure, keep payload unchanged. domain {}", domainId, e);
        }
    }

    //返回true表示有字段被改写，只有真的改写了才需要重新编码，避免每条组合消息都做一次无谓的 Base64 解码/编码
    private boolean restoreCompositeMessages(String domainId, JsonArray messages, int depth) {
        if(depth > CompositeMessageUtils.MAX_NEST_DEPTH) {
            throw new IllegalArgumentException("composite message nesting is too deep");
        }
        //整层子消息的 uid 和媒体映射一起预取，避免每个子消息查两次库
        Map<Long, Long> uidMappings = prefetchCompositeChildUids(domainId, messages);
        prefetchCompositeChildMediaMappings(domainId, messages);
        boolean changed = false;
        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                throw new IllegalArgumentException("composite child message is not a json object");
            }
            JsonObject message = element.getAsJsonObject();
            //uid 是本域为这条子消息分配的消息ID，publish 回本域时要换回本域的值，否则本域存储的消息会引用对端(或第三方域)的消息ID
            long uid = CompositeMessageUtils.getLong(message, "uid");
            if(uid > 0) {
                Long localUid = uidMappings == null ? null : uidMappings.get(uid);
                if(uidMappings == null) {
                    //批量查询失败时退回逐条查询
                    Optional<OutMessageIds> mapping = outMessageIdsRepository.findByToDomainIdAndToMessageId(domainId, uid);
                    localUid = mapping.map(outMessageIds -> outMessageIds.messageId).orElse(null);
                }
                if(localUid != null && localUid > 0 && localUid != uid) {
                    message.addProperty("uid", localUid);
                    changed = true;
                }
            }
            String remoteMediaUrl = CompositeMessageUtils.getString(message, "mru");
            if(StringUtils.hasText(remoteMediaUrl)) {
                Optional<String> localMediaUrl = mediaUrlMappingService.findLocalMediaUrl(domainId, remoteMediaUrl);
                if(localMediaUrl.isPresent() && !localMediaUrl.get().equals(remoteMediaUrl)) {
                    message.addProperty("mru", localMediaUrl.get());
                    changed = true;
                }
            }
            if(CompositeMessageUtils.getInt(message, "ctype") == CompositeMessageUtils.CONTENT_TYPE_COMPOSITE) {
                String base64edData = CompositeMessageUtils.getString(message, "cbc");
                if(StringUtils.hasText(base64edData)) {
                    JsonObject nested = CompositeMessageUtils.decode(base64edData);
                    if(restoreCompositeMessages(domainId, CompositeMessageUtils.messages(nested), depth + 1)) {
                        message.addProperty("cbc", CompositeMessageUtils.encode(nested));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    //批量查询子消息 uid 在本域对应的消息ID，返回 对端uid -> 本域uid。查询失败返回 null，由调用方退回逐条查询。
    private Map<Long, Long> prefetchCompositeChildUids(String domainId, JsonArray messages) {
        Set<Long> uids = new HashSet<>();
        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                continue;
            }
            long uid = CompositeMessageUtils.getLong(element.getAsJsonObject(), "uid");
            if(uid > 0) {
                uids.add(uid);
            }
        }
        if(uids.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<Long, Long> mappings = new HashMap<>();
            for (List<Long> chunk : partitionMessageIds(uids)) {
                for (OutMessageIds mapping : outMessageIdsRepository.findByToMessageIds(domainId, chunk)) {
                    if(mapping.toMessageId > 0) {
                        mappings.put(mapping.toMessageId, mapping.messageId);
                    }
                }
            }
            return mappings;
        } catch (Exception e) {
            LOG.warn("batch query composite child message ids failed, fallback to per-message query", e);
            return null;
        }
    }

    //批量预取子消息的媒体映射，后面 findLocalMediaUrl 直接命中缓存
    private void prefetchCompositeChildMediaMappings(String domainId, JsonArray messages) {
        Set<String> mediaUrls = new HashSet<>();
        for (JsonElement element : messages) {
            if(!element.isJsonObject()) {
                continue;
            }
            String remoteMediaUrl = CompositeMessageUtils.getString(element.getAsJsonObject(), "mru");
            if(StringUtils.hasText(remoteMediaUrl)) {
                mediaUrls.add(remoteMediaUrl);
            }
        }
        if(!mediaUrls.isEmpty()) {
            mediaUrlMappingService.prefetchLocalMediaUrls(domainId, mediaUrls);
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

    private void restoreLocalMediaUrl(String domainId, MessagePayload payload, String fallbackMediaUrl) {
        if(!StringUtils.hasText(payload.getRemoteMediaUrl())) {
            return;
        }
        Optional<String> localMediaUrl = mediaUrlMappingService.findLocalMediaUrl(domainId, payload.getRemoteMediaUrl());
        String restoredMediaUrl = localMediaUrl.orElse(fallbackMediaUrl);
        if(StringUtils.hasText(restoredMediaUrl) && !restoredMediaUrl.equals(payload.getRemoteMediaUrl())) {
            payload.setRemoteMediaUrl(restoredMediaUrl);
        }
    }

    public Object onRecallMessageRequest(String domainId, long messageId, String operator, boolean isSenderRecall) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        runAsync(deferredResult, () -> {
            MeshRestResult restResult;
            try {
                requireRequesterActor(domainId, operator, "operator");
                requireMessageBelongsToDomain(domainId, messageId);
                if (!canRecallMessage(messageId, operator)) {
                    restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_NO_RIGHT.code, "无权限撤回该消息");
                    deferredResult.setResult(restResult.toString());
                    return;
                }
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

    //接收对端传过来的文件流，不落地，边接收边上传到minio。
    //sourceMediaUrlHeader 是上传方带过来的原始媒体地址(header: x-source-media-url)，用于保存一条反向映射
    public Object onTransferFileRequest(String domainId, InputStream inputStream, String extension, String contentType, int mediaType, long contentLength, String sourceMediaUrlHeader) {
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
            saveSourceMediaMapping(domainId, url, sourceMediaUrlHeader);
            LOG.info("transfer file from domain {} success, uploaded to {}", domainId, url);
            restResult = MeshRestResult.ok(url);
        } catch (Exception e) {
            LOG.error("transfer file from domain {} failure", domainId, e);
            //不要把内部错误细节(如对象存储地址、桶名)返回给对端
            restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, "transfer file failure");
        }
        return restResult.toString();
    }

    //接收方也记录一条 本域地址 <-> 来源域地址 的映射：文件再发回来源域时可以直接复用来源域的原地址，不用重新上传。
    //映射只是优化，保存失败不能影响文件传输本身。
    private void saveSourceMediaMapping(String domainId, String localMediaUrl, String sourceMediaUrlHeader) {
        try {
            String sourceMediaUrl = MediaTransferUtils.decodeSourceUrl(sourceMediaUrlHeader);
            if(!StringUtils.hasText(sourceMediaUrl) || sourceMediaUrl.equals(localMediaUrl)) {
                return;
            }
            mediaUrlMappingService.saveMapping(domainId, localMediaUrl, sourceMediaUrl);
        } catch (Exception e) {
            LOG.warn("save source media url mapping failed, domain {}, local media url {}", domainId, localMediaUrl, e);
        }
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
                requireRequesterActor(domainId, userId, "userId");
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
