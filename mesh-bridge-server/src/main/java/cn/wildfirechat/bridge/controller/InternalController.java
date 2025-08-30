package cn.wildfirechat.bridge.controller;

import cn.wildfirechat.bridge.service.OutService;
import cn.wildfirechat.pojos.mesh.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class InternalController {
    private static final Logger LOG = LoggerFactory.getLogger(InternalController.class);

    @Autowired
    OutService outService;

    @GetMapping("/hello")
    public Object hello() {
        return "Hello from internal controller";
    }

    @PostMapping(value = "/ping", produces = "application/json;charset=UTF-8")
    public Object ping(@RequestBody PojoDomainPingRequest request) {
        return outService.onPing(request.domainId);
    }

    @PostMapping(value = "/search_user", produces = "application/json;charset=UTF-8")
    public Object searchUser(@RequestBody PojoSearchUserReq searchUserReq) {
        return outService.onSearchUser(searchUserReq.domainId, searchUserReq.keyword, searchUserReq.searchType, searchUserReq.userType, searchUserReq.page);
    }

    @PostMapping(value = "/add_friend_request", produces = "application/json;charset=UTF-8")
    public Object addFriendRequest(@RequestBody PojoAddFriendReq addFriendReq) {
        return outService.onAddFriendRequest(addFriendReq.domainId, addFriendReq.fromUserId, addFriendReq.reason, addFriendReq.targetUserId);
    }

    @PostMapping(value = "/handle_friend_request", produces = "application/json;charset=UTF-8")
    public Object handleFriendRequest(@RequestBody PojoHandleFriendRequestReq handleFriendReq) {
        return outService.onHandleFriendRequest(handleFriendReq.domainId, handleFriendReq.userId, handleFriendReq.status, handleFriendReq.targetUserId);
    }

    @PostMapping(value = "/delete_friend", produces = "application/json;charset=UTF-8")
    public Object deleteFriend(@RequestBody PojoDeleteFriend deleteFriend) {
        return outService.onDeleteFriend(deleteFriend.domainId, deleteFriend.operator, deleteFriend.friendUid);
    }

    @PostMapping(value = "/send_message", produces = "application/json;charset=UTF-8")
    public Object sendMessage(@RequestBody PojoSendMessageReq sendMessageReq) {
        return outService.onSendMessageRequest(sendMessageReq.domainId, sendMessageReq.messageId, sendMessageReq.messageData, sendMessageReq.clientId);
    }

    @PostMapping(value = "/publish_message", produces = "application/json;charset=UTF-8")
    public Object publishMessage(@RequestBody PojoPublishMessageReq publishMessageReq) {
        return outService.onPublishMessageRequest(publishMessageReq.domainId, publishMessageReq.messageData, publishMessageReq.receivers, publishMessageReq.republish, publishMessageReq.messageId);
    }

    @PostMapping(value = "/recall_message", produces = "application/json;charset=UTF-8")
    public Object recallMessage(@RequestBody PojoRecallMessageReq recallMessageReq) {
        return outService.onRecallMessageRequest(recallMessageReq.domainId, recallMessageReq.messageId, recallMessageReq.operator, recallMessageReq.isSenderRecall);
    }

    @PostMapping(value = "/batch_user_infos", produces = "application/json;charset=UTF-8")
    public Object batchGetUserInfoRequest(@RequestBody PojoStringList stringList) {
        return outService.onBatchGetUserInfoRequest(stringList.domainId, stringList.stringList);
    }

    @PostMapping(value = "/batch_group_infos", produces = "application/json;charset=UTF-8")
    public Object batchGetGroupInfoRequest(@RequestBody PojoStringList stringList) {
        return outService.onBatchGetGroupInfoRequest(stringList.domainId, stringList.stringList);
    }

    @PostMapping(value = "/get_group_member", produces = "application/json;charset=UTF-8")
    public Object getGroupMemberRequest(@RequestBody PojoString string) {
        return outService.getGroupMemberRequest(string.domainId, string.string);
    }

    @PostMapping(value = "/add_group_member", produces = "application/json;charset=UTF-8")
    public Object addGroupMemberRequest(@RequestBody PojoAddGroupMember pojoAddGroupMember) {
        return outService.addGroupMemberRequest(pojoAddGroupMember.domainId, pojoAddGroupMember.operator, pojoAddGroupMember.group_id, pojoAddGroupMember.members);
    }

    @PostMapping(value = "/quit_group", produces = "application/json;charset=UTF-8")
    public Object quitGroupRequest(@RequestBody PojoQuitGroup pojoQuitGroup) {
        return outService.quitGroupRequest(pojoQuitGroup.domainId, pojoQuitGroup.operator, pojoQuitGroup.group_id);
    }

    @PostMapping(value = "/dismiss_group", produces = "application/json;charset=UTF-8")
    public Object dismissGroupRequest(@RequestBody PojoDismissGroup pojoDismissGroup) {
        return outService.dismissGroupRequest(pojoDismissGroup.domainId, pojoDismissGroup.operator, pojoDismissGroup.group_id);
    }

    @PostMapping(value = "/kickoff_group_member", produces = "application/json;charset=UTF-8")
    public Object kickoffGroupMemberRequest(@RequestBody PojoKickoffGroupMember pojoKickoffGroupMember) {
        return outService.kickoffGroupMemberRequest(pojoKickoffGroupMember.domainId, pojoKickoffGroupMember.operator, pojoKickoffGroupMember.group_id, pojoKickoffGroupMember.members);
    }

    @PostMapping(value = "/transfer_group", produces = "application/json;charset=UTF-8")
    public Object transferGroupRequest(@RequestBody PojoTransferGroup pojoTransferGroup) {
        return outService.transferGroupRequest(pojoTransferGroup.domainId, pojoTransferGroup.operator, pojoTransferGroup.newOwner, pojoTransferGroup.group_id);
    }

    @PostMapping(value = "/modify_group_info", produces = "application/json;charset=UTF-8")
    public Object modifyGroupInfoRequest(@RequestBody PojoModifyGroupInfo pojoModifyGroupInfo) {
        return outService.modifyGroupInfoRequest(pojoModifyGroupInfo.domainId, pojoModifyGroupInfo.operator, pojoModifyGroupInfo.group_id, pojoModifyGroupInfo.type, pojoModifyGroupInfo.value);
    }

    @PostMapping(value = "/group_updated", produces = "application/json;charset=UTF-8")
    public Object groupUpdated(@RequestBody PojoGroupUpdated pojoGroupUpdated) {
        return outService.groupUpdated(pojoGroupUpdated.domainIds, pojoGroupUpdated.groupInfo, pojoGroupUpdated.members);
    }

    @PostMapping(value = "/conference_request", produces = "application/json;charset=UTF-8")
    public Object conferenceRequest(@RequestBody PojoUserConferenceRequest pojoUserConferenceRequest) {
        return outService.conferenceRequest(pojoUserConferenceRequest.domainId, pojoUserConferenceRequest.clientID, pojoUserConferenceRequest.fromUser, pojoUserConferenceRequest.request, pojoUserConferenceRequest.sessionId, pojoUserConferenceRequest.roomId, pojoUserConferenceRequest.data, pojoUserConferenceRequest.advanced);
    }

    @PostMapping(value = "/conference_event", produces = "application/json;charset=UTF-8")
    public Object conferenceEvent(@RequestBody PojoUserConferenceEvent pojoUserConferenceEvent) {
        return outService.conferenceEvent(pojoUserConferenceEvent.domainId, pojoUserConferenceEvent.data, pojoUserConferenceEvent.userId, pojoUserConferenceEvent.clientId, pojoUserConferenceEvent.isRobot);
    }
}
