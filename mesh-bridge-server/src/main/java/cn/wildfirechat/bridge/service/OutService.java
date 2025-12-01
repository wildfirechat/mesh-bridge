package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.*;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.bridge.utilis.HttpUtils;
import cn.wildfirechat.pojos.*;
import cn.wildfirechat.pojos.mesh.*;
import cn.wildfirechat.sdk.messagecontent.MessageContent;
import cn.wildfirechat.sdk.messagecontent.MessageContentFactory;
import cn.wildfirechat.sdk.messagecontent.TextMessageContent;
import cn.wildfirechat.sdk.utilities.JsonUtils;
import com.google.gson.*;
import org.apache.http.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.wildfirechat.pojos.mesh.MeshRestResult.MeshRestCode.*;

@Service
public class OutService {
    private static final Logger LOG = LoggerFactory.getLogger(OutService.class);

    @Value("${bridge.my_domain_id}")
    String myDomainId;

    @Value("${bridge.domain_divider}")
    String domainDivider;

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

        messageData.getConv().setTarget(DomainIdUtils.toExternalId(domainId, messageData.getConv().getTarget(), myDomainId));
        messageData.setSender(DomainIdUtils.toExternalId(domainId, messageData.getSender(), myDomainId));
        convertMessagePayloadDomainId(domainId, messageData.getPayload(), myDomainId);

        DeferredResult<String> deferredResult = new DeferredResult<>();
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
                    if ((messageData.getPayload().getPersistFlag() & 0x01) > 0) {
                        OutMessageIds outMessageIds = new OutMessageIds();
                        outMessageIds.messageId = messageId;
                        outMessageIds.toDomainId = domainId;
                        outMessageIds.toMessageId = meshRestResult.getResult().getMessageUid();
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

        return deferredResult;
    }

    private long convertMessageUid(String sender, long messageUid) {
        // 根据发送者能够确定是本域发送的还是其他域发送的。再根据记录找到消息的对应关系，替换为对应域的消息ID
        if(DomainIdUtils.isExternalId(sender)) {
            //收到的消息
            String senderDomainId = DomainIdUtils.getExternalId(sender);
            Optional<Long> optionalOutMessageIds = inMessageIdsRepository.findByDomainIdAndLocalMessageId(senderDomainId, messageUid);
            if(optionalOutMessageIds.isPresent()) {
                return optionalOutMessageIds.get();
            } else {
                LOG.error("Convert message uid {} failed. The message is from {} domain", messageUid, senderDomainId);
                return 0;
            }
        } else {
            //发出去的消息
            Optional<OutMessageIds> optionalOutMessageIds = outMessageIdsRepository.findById(messageUid);
            if(optionalOutMessageIds.isPresent()) {
                return optionalOutMessageIds.get().getToMessageId();
            } else {
                LOG.error("Convert message uid {} failed. The message is from local domain", messageUid);
                return 0;
            }
        }
    }

    //这里是出去的消息进行转换的地方，消息中包含的用户ID/群组ID/频道ID和消息ID等需要转换为对方视角的ID。
    //理论上所有二开只能局限于这个函数。如果有其他改动容易出问题。如果其他地方需要改动，请给我们提PR或者联系我们修改，确保其他部分保持一致。
    private void convertMessagePayloadDomainId(String remoteDomainId, MessagePayload messagePayload, String localDomainId) {
        if(!TextUtils.isEmpty(messagePayload.getRemoteMediaUrl())) {
            //如果本域的对象存储服务无法让其他域的用户直接访问，这里需要把文件异步传输到其他域中，且这里修改一下文件的地址。需要新写传输文件的方法。
        }

        if(!messagePayload.getMentionedTarget().isEmpty()) {
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
                    long targetMessageUid = convertMessageUid(txtContent.getQuoteInfo().getUserId(), txtContent.getQuoteInfo().getMessageUid());
                    txtContent.getQuoteInfo().setMessageUid(targetMessageUid);

                    // 发送者转换域地址。
                    txtContent.getQuoteInfo().setUserId(DomainIdUtils.toExternalId(remoteDomainId, txtContent.getQuoteInfo().getUserId(), myDomainId));

                    //设置到payload中
                    messagePayload.setBase64edData(Base64.getEncoder().encodeToString(txtContent.getQuoteInfo().encode().toJSONString().getBytes(StandardCharsets.UTF_8)));
                }
            }
        } else if(messagePayload.getType() == 10) {
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
            String jsonStr = new String(Base64.getDecoder().decode(messagePayload.getBase64edData()), StandardCharsets.UTF_8);
            JsonObject object = (JsonObject) JsonParser.parseString(jsonStr);
            if(replaceString(object, "f", remoteDomainId, localDomainId)) {
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
        } else if(messagePayload.getType() == 400) {
            String jsonStr = new String(Base64.getDecoder().decode(messagePayload.getBase64edData()), StandardCharsets.UTF_8);
            JsonObject object = (JsonObject) JsonParser.parseString(jsonStr);
            if(replaceStringList(object, "ts", remoteDomainId, localDomainId)) {
                messagePayload.setBase64edData(Base64.getEncoder().encodeToString(object.toString().getBytes(StandardCharsets.UTF_8)));
            }
        } else if(messagePayload.getType() == 406) {
            String jsonStr = new String(Base64.getDecoder().decode(messagePayload.getBase64edData()), StandardCharsets.UTF_8);
            JsonObject object = (JsonObject) JsonParser.parseString(jsonStr);
            replaceString(object, "initiator", remoteDomainId, localDomainId);
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
        //Call ID
        if(messagePayload.getType() >= 400 && messagePayload.getType() <= 407) {
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
        }

        //撤回或者删除消息
        if(messagePayload.getType() == 80 || messagePayload.getType() == 81) {
            String operatorId = messagePayload.getContent();
            String originalSender = operatorId;
            long messageUid = Long.parseLong(new String(Base64.getDecoder().decode(messagePayload.getBase64edData())));
            boolean isRealSender = false;
            if(!TextUtils.isEmpty(messagePayload.getExtra())) {
                JsonObject object = (JsonObject) JsonParser.parseString(messagePayload.getExtra());
                if(object != null) {
                    String sender = object.get("s").getAsString();
                    if(!StringUtils.isEmpty(sender)) {
                        originalSender = sender;
                        isRealSender = true;
                    }
                    replaceString(object, "s", remoteDomainId, localDomainId);
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
            long targetMessageUid = convertMessageUid(originalSender, messageUid);
            messagePayload.setBase64edData(Base64.getEncoder().encodeToString(String.valueOf(targetMessageUid).getBytes(StandardCharsets.UTF_8)));

            //替换操作者
            messagePayload.setContent(DomainIdUtils.toExternalId(remoteDomainId, messagePayload.getContent(), localDomainId));
        }

        if(messagePayload.getType() >= 104 && messagePayload.getType() <= 124) {
            String jsonStr = new String(Base64.getDecoder().decode(messagePayload.getBase64edData()), StandardCharsets.UTF_8);
            JsonObject object = (JsonObject) JsonParser.parseString(jsonStr);
            replaceString(object, "o", remoteDomainId, localDomainId);
            replaceString(object, "g", remoteDomainId, localDomainId);
            switch (messagePayload.getType()) {
                case 105:  //add group member
                case 106:  //kickoff group member
                case 120:  //kickoff group member visible
                case 117:  //change group manager
                case 118:  //change group member mute state
                case 119:  //change group member allow state
                    replaceStringList(object, "ms", remoteDomainId, localDomainId);
                    break;
                case 109:  //transfer group
                case 111:  //change group member alias
                case 123:  //change group member extra
                    replaceString(object, "m", remoteDomainId, localDomainId);
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

    private boolean replaceString(JsonObject object, String key, String remoteDomainId, String localDomainId) {
        if(object.has(key) && object.get(key) instanceof JsonPrimitive) {
            String o = object.get(key).getAsString();
            o = DomainIdUtils.toExternalId(remoteDomainId, o, localDomainId);
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

        messageData.getConv().setTarget(DomainIdUtils.toExternalId(domainId, messageData.getConv().getTarget(), myDomainId));
        messageData.setSender(DomainIdUtils.toExternalId(domainId, messageData.getSender(), myDomainId));
        convertMessagePayloadDomainId(domainId, messageData.getPayload(), myDomainId);

        List<String> receiverIds = new ArrayList<>();
        for (String receiver : receivers) {
            receiverIds.add(DomainIdUtils.toExternalId(domainId, receiver, myDomainId));
        }
        DeferredResult<String> deferredResult = new DeferredResult<>();
        PojoPublishMessageReq req = new PojoPublishMessageReq();
        req.messageData = messageData;
        req.receivers = receiverIds;
        req.domainId = myDomainId;
        req.republish = republish;
        req.messageId = messageId;

        HttpUtils.httpPostToDomain(domain, "/publish_message", req, new HttpUtils.HttpCallback() {
            @Override
            public void onSuccess(String content) {
                MeshRestResult<SendMessageResult> meshRestResult = JsonUtils.fromJsonObject2(content, SendMessageResult.class);
                if(meshRestResult.getLocal_mesh_code() == 0 && meshRestResult.getRemote_mesh_code() == 0 && meshRestResult.getRemote_im_code() == 0) {
                    OutMessageIds outMessageIds = new OutMessageIds();
                    outMessageIds.messageId = messageId;
                    outMessageIds.toDomainId = domainId;
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
            Optional<OutMessageIds> optionalOutMessageIds = outMessageIdsRepository.findById(messageId);
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
