package cn.wildfirechat.bridge.service;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.bridge.jpa.InMessageIds;
import cn.wildfirechat.bridge.jpa.InMessageIdsKey;
import cn.wildfirechat.bridge.jpa.InMessageIdsRepository;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.pojos.*;
import cn.wildfirechat.pojos.mesh.*;
import cn.wildfirechat.sdk.MeshAdmin;
import cn.wildfirechat.sdk.MessageAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class InService {
    @Autowired
    OutMessageIdsRepository outMessageIdsRepository;

    @Autowired
    InMessageIdsRepository inMessageIdsRepository;
    
    public Object onSearchUser(String keyword, int searchType, int page) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<PojoSearchUserRes> imResult = MeshAdmin.searchUser(keyword, searchType, page);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onAddFriendRequest(String domainId, String fromUserId, String reason, String targetUid) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.sendFriendRequest(fromUserId, targetUid, reason);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok();
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onHandleFriendRequest(String domainId, String fromUserId, int status, String targetUid) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.handleFriendRequest(fromUserId, targetUid, status);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok();
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onSendMessageRequest(String domainId, SendMessageData messageData, String sendClientId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<SendMessageResult> imResult = MeshAdmin.sendMessage(messageData.getSender(), messageData.getConv(), messageData.getPayload(), null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onPublishMessageRequest(String domainId, long messageId, SendMessageData messageData, List<String> receivers, boolean republish) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
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

                    Optional<Long> outMessageIdOptional = outMessageIdsRepository.findByDomainIdAndToMessageId(domainId, messageId);
                    if(outMessageIdOptional.isPresent()) {
                        if(inExist) {
                            IMResult<Void> imResult = MessageAdmin.deleteMessage(outMessageIdOptional.get());
                            if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {

                            } else {
                                restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                            }
                        } else {
                            IMResult<Void> imResult = MeshAdmin.updateMessageContent(messageData.getSender(), outMessageIdOptional.get(), messageData.getPayload(), true);
                            if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                                SendMessageResult sendMessageResult = new SendMessageResult();
                                sendMessageResult.setMessageUid(outMessageIdOptional.get());
                                sendMessageResult.setTimestamp(System.currentTimeMillis());
                                restResult = MeshRestResult.ok(sendMessageResult);
                            } else {
                                restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                            }
                        }
                    } else {
                        if(!inExist) {
                            restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_NOT_EXIST.code, "消息不存在");
                        }
                    }
                } else {
                    IMResult<SendMessageResult> imResult = MeshAdmin.publishMessage(messageData, receivers);
                    if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                        restResult = MeshRestResult.ok(imResult.result);
                        if ((messageData.getPayload().getPersistFlag() & 0x01) > 0) {
                            InMessageIdsKey key = new InMessageIdsKey(messageId, domainId);
                            InMessageIds inMessageIds = new InMessageIds();
                            inMessageIds.id = key;
                            inMessageIds.localMessageId = imResult.getResult().getMessageUid();
                            inMessageIdsRepository.save(inMessageIds);
                        }
                    } else {
                        restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onRecallMessageRequest(String domainId, long messageId, String operator, boolean isSenderRecall) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<String> imResult = MeshAdmin.recallMessage(operator, messageId);
                if (imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onBatchGetUserInfoRequest(String domainId, List<String> userIds) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<OutputUserInfoList> imResult = MeshAdmin.getBatchUserInfos(userIds);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object onBatchGetGroupInfoRequest(String domainId, List<String> groupIds) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<PojoGroupInfoList> imResult = MeshAdmin.batchGroupInfos(groupIds);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object getGroupMemberRequest(String domainId, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<OutputGroupMemberList> imResult = MeshAdmin.getGroupMembers(groupId);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }


    public Object addGroupMemberRequest(String domainId, String operator, String groupId, List<PojoGroupMember> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.addGroupMembers(operator, groupId, members, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object quitGroupRequest(String domainId, String operator, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.quitGroup(operator, groupId, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object dismissGroupRequest(String domainId, String operator, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.dismissGroup(operator, groupId, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object kickoffGroupMemberRequest(String domainId, String operator, String groupId, List<String> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.kickoffGroupMembers(operator, groupId, members, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object transferGroupRequest(String domainId, String operator, String newOwner, String groupId) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.transferGroup(operator, groupId, newOwner, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object modifyGroupInfoRequest(String domainId, String operator, String groupId, int type, String value) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.modifyGroupInfo(operator, groupId, type, value, null, null);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object groupUpdated(String domainId, PojoGroupInfo groupInfo, List<PojoGroupMember> members) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.syncGroup(groupInfo, members);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object conferenceRequest(String domainId, String clientID, String fromUser, String request, long sessionId, String roomId, String data, boolean advanced) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<PojoUserConferenceResponse> imResult = MeshAdmin.userConferenceRequest(clientID, fromUser, request, sessionId, roomId, data, advanced);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result.data);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }

    public Object conferenceEvent(String domainId, String data, String userId, String clientId, boolean isRobot) {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        CompletableFuture.runAsync(()->{
            MeshRestResult restResult;
            try {
                IMResult<Void> imResult = MeshAdmin.userConferenceEvent(data, userId, clientId, isRobot);
                if(imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                    restResult = MeshRestResult.ok(imResult.result);
                } else {
                    restResult = MeshRestResult.remoteIMError(imResult.code, imResult.msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                restResult = MeshRestResult.remoteMeshError(MeshRestResult.MeshRestCode.ERROR_SERVER_ERROR.code, e.getLocalizedMessage());
            }
            deferredResult.setResult(restResult.toString());
        });
        return deferredResult;
    }


}
