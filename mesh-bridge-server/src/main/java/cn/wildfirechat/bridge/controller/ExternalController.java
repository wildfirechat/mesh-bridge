package cn.wildfirechat.bridge.controller;

import cn.wildfirechat.bridge.service.InService;
import cn.wildfirechat.pojos.mesh.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ExternalController {
    private static final Logger LOG = LoggerFactory.getLogger(ExternalController.class);
    @Autowired
    InService inService;

    @GetMapping("/hello")
    public Object hello() {
        return "Hello from external controller";
    }

    @PostMapping(value = "/ping", produces = "application/json;charset=UTF-8")
    public Object ping(@RequestBody PojoDomainPingRequest request) {
        return inService.onPing(request.domainId);
    }

    @PostMapping(value = "/search_user", produces = "application/json;charset=UTF-8")
    public Object searchUser(@RequestBody PojoSearchUserReq searchUserReq) {
        return inService.onSearchUser(searchUserReq.keyword, searchUserReq.searchType, searchUserReq.userType, searchUserReq.page);
    }

    @PostMapping(value = "/add_friend_request", produces = "application/json;charset=UTF-8")
    public Object addFriendRequest(@RequestBody PojoAddFriendReq addFriendReq) {
        return inService.onAddFriendRequest(addFriendReq.domainId, addFriendReq.fromUserId, addFriendReq.reason, addFriendReq.targetUserId);
    }

    @PostMapping(value = "/handle_friend_request", produces = "application/json;charset=UTF-8")
    public Object handleFriendRequest(@RequestBody PojoHandleFriendRequestReq handleFriendReq) {
        return inService.onHandleFriendRequest(handleFriendReq.domainId, handleFriendReq.userId, handleFriendReq.status, handleFriendReq.targetUserId);
    }

    @PostMapping(value = "/delete_friend", produces = "application/json;charset=UTF-8")
    public Object deleteFriend(@RequestBody PojoDeleteFriend deleteFriend) {
        return inService.onDeleteFriend(deleteFriend.domainId, deleteFriend.operator, deleteFriend.friendUid);
    }

    @PostMapping(value = "/send_message", produces = "application/json;charset=UTF-8")
    public Object sendMessage(@RequestBody PojoSendMessageReq sendMessageReq) {
        return inService.onSendMessageRequest(sendMessageReq.domainId, sendMessageReq.messageId, sendMessageReq.messageData, sendMessageReq.clientId);
    }

    @PostMapping(value = "/publish_message", produces = "application/json;charset=UTF-8")
    public Object publishMessage(@RequestBody PojoPublishMessageReq publishMessageReq) {
        return inService.onPublishMessageRequest(publishMessageReq.domainId, publishMessageReq.messageId, publishMessageReq.messageData, publishMessageReq.receivers, publishMessageReq.republish);
    }

    @PostMapping(value = "/recall_message", produces = "application/json;charset=UTF-8")
    public Object recallMessage(@RequestBody PojoRecallMessageReq recallMessageReq) {
        return inService.onRecallMessageRequest(recallMessageReq.domainId, recallMessageReq.messageId, recallMessageReq.operator, recallMessageReq.isSenderRecall);
    }

    @PostMapping(value = "/batch_user_infos", produces = "application/json;charset=UTF-8")
    public Object batchGetUserInfoRequest(@RequestBody PojoStringList stringList) {
        return inService.onBatchGetUserInfoRequest(stringList.domainId, stringList.stringList);
    }

    @PostMapping(value = "/batch_group_infos", produces = "application/json;charset=UTF-8")
    public Object batchGetGroupInfoRequest(@RequestBody PojoStringList stringList) {
        return inService.onBatchGetGroupInfoRequest(stringList.domainId, stringList.stringList);
    }

    @PostMapping(value = "/get_group_member", produces = "application/json;charset=UTF-8")
    public Object getGroupMemberRequest(@RequestBody PojoString string) {
        return inService.getGroupMemberRequest(string.domainId, string.string);
    }

    @PostMapping(value = "/add_group_member", produces = "application/json;charset=UTF-8")
    public Object addGroupMemberRequest(@RequestBody PojoAddGroupMember pojoAddGroupMember) {
        return inService.addGroupMemberRequest(pojoAddGroupMember.domainId, pojoAddGroupMember.operator, pojoAddGroupMember.group_id, pojoAddGroupMember.members);
    }

    @PostMapping(value = "/quit_group", produces = "application/json;charset=UTF-8")
    public Object quitGroupRequest(@RequestBody PojoQuitGroup pojoQuitGroup) {
        return inService.quitGroupRequest(pojoQuitGroup.domainId, pojoQuitGroup.operator, pojoQuitGroup.group_id);
    }

    @PostMapping(value = "/dismiss_group", produces = "application/json;charset=UTF-8")
    public Object dismissGroupRequest(@RequestBody PojoDismissGroup pojoDismissGroup) {
        return inService.dismissGroupRequest(pojoDismissGroup.domainId, pojoDismissGroup.operator, pojoDismissGroup.group_id);
    }

    @PostMapping(value = "/kickoff_group_member", produces = "application/json;charset=UTF-8")
    public Object kickoffGroupMemberRequest(@RequestBody PojoKickoffGroupMember pojoKickoffGroupMember) {
        return inService.kickoffGroupMemberRequest(pojoKickoffGroupMember.domainId, pojoKickoffGroupMember.operator, pojoKickoffGroupMember.group_id, pojoKickoffGroupMember.members);
    }

    @PostMapping(value = "/transfer_group", produces = "application/json;charset=UTF-8")
    public Object transferGroupRequest(@RequestBody PojoTransferGroup pojoTransferGroup) {
        return inService.transferGroupRequest(pojoTransferGroup.domainId, pojoTransferGroup.operator, pojoTransferGroup.newOwner, pojoTransferGroup.group_id);
    }

    @PostMapping(value = "/modify_group_info", produces = "application/json;charset=UTF-8")
    public Object modifyGroupInfoRequest(@RequestBody PojoModifyGroupInfo pojoModifyGroupInfo) {
        return inService.modifyGroupInfoRequest(pojoModifyGroupInfo.domainId, pojoModifyGroupInfo.operator, pojoModifyGroupInfo.group_id, pojoModifyGroupInfo.type, pojoModifyGroupInfo.value);
    }


    @PostMapping(value = "/group_updated", produces = "application/json;charset=UTF-8")
    public Object groupUpdated(@RequestBody PojoGroupUpdated pojoGroupUpdated) {
        return inService.groupUpdated(pojoGroupUpdated.domainIds.get(0), pojoGroupUpdated.groupInfo, pojoGroupUpdated.members);
    }

    @PostMapping(value = "/conference_request", produces = "application/json;charset=UTF-8")
    public Object conferenceRequest(@RequestBody PojoUserConferenceRequest pojoUserConferenceRequest) {
        return inService.conferenceRequest(pojoUserConferenceRequest.domainId, pojoUserConferenceRequest.clientID, pojoUserConferenceRequest.fromUser, pojoUserConferenceRequest.request, pojoUserConferenceRequest.sessionId, pojoUserConferenceRequest.roomId, pojoUserConferenceRequest.data, pojoUserConferenceRequest.advanced);
    }

    @PostMapping(value = "/conference_event", produces = "application/json;charset=UTF-8")
    public Object conferenceEvent(@RequestBody PojoUserConferenceEvent pojoUserConferenceEvent) {
        return inService.conferenceEvent(pojoUserConferenceEvent.domainId, pojoUserConferenceEvent.data, pojoUserConferenceEvent.userId, pojoUserConferenceEvent.clientId, pojoUserConferenceEvent.isRobot);
    }
}
