package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.proto.ProtoConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InServiceValidationTest {

    private InService inService;

    @BeforeEach
    void setUp() {
        DomainIdUtils.setDomainDivider("@");
        inService = new InService();
        inService.myDomainId = "A";
    }

    @Test
    void acceptsLocalGroupId() {
        //对端调用本域接口时，本域的群是以裸 ID 传过来的
        assertDoesNotThrow(() -> inService.requireGroupDomain("B", "groupA"));
    }

    @Test
    void acceptsOwnGroupWithSuffix() {
        assertDoesNotThrow(() -> inService.requireGroupDomain("B", "groupA@A"));
    }

    @Test
    void acceptsRequesterGroup() {
        assertDoesNotThrow(() -> inService.requireGroupDomain("B", "groupB@B"));
    }

    @Test
    void rejectsThirdDomainGroup() {
        assertThrows(IllegalArgumentException.class, () -> inService.requireGroupDomain("B", "groupC@C"));
    }

    @Test
    void rejectsEmptyGroup() {
        assertThrows(IllegalArgumentException.class, () -> inService.requireGroupDomain("B", ""));
    }

    @Test
    void acceptsExternalSender() {
        assertEquals("userB@B", inService.requirePublishSender("B", "userB@B", false));
    }

    @Test
    void rejectsLocalSenderWithoutPublishBack() {
        //对端不能凭空伪造本域用户的新消息
        assertThrows(IllegalArgumentException.class, () -> inService.requirePublishSender("B", "userA", false));
    }

    @Test
    void acceptsLocalSenderForPublishBack() {
        //本域发出的消息被对端 publish 回来时，发送者是本域裸 ID
        assertEquals("userA", inService.requirePublishSender("B", "userA", true));
    }

    @Test
    void normalizesSuffixedLocalSenderForPublishBack() {
        assertEquals("userA", inService.requirePublishSender("B", "userA@A", true));
    }

    @Test
    void rejectsSuffixedLocalSenderWithoutPublishBack() {
        assertThrows(IllegalArgumentException.class, () -> inService.requirePublishSender("B", "userA@A", false));
    }

    @Test
    void allowsLocalSenderForPublishBackRepublishOrGroupNotification() {
        int group = ProtoConstants.ConversationType.ConversationType_Group;
        int privateChat = ProtoConstants.ConversationType.ConversationType_Private;

        assertTrue(InService.allowsLocalPublishSender(true, false, 1, privateChat));
        assertTrue(InService.allowsLocalPublishSender(false, true, 80, privateChat));
        assertTrue(InService.allowsLocalPublishSender(false, false, 104, group));
        assertTrue(InService.allowsLocalPublishSender(false, false, 100, group));

        //普通消息、单聊里的通知、非通知类型都不允许本域发送者
        assertFalse(InService.allowsLocalPublishSender(false, false, 1, group));
        assertFalse(InService.allowsLocalPublishSender(false, false, 104, privateChat));
        assertFalse(InService.allowsLocalPublishSender(false, false, 400, group));
        assertFalse(InService.allowsLocalPublishSender(false, false, 200, group));
    }
}
