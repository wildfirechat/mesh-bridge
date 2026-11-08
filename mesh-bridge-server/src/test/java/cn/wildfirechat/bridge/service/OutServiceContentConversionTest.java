package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.OutMessageIds;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.pojos.MessagePayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//覆盖 convertMessagePayloadDomainId 里按消息类型转换用户/群/消息ID的分支
class OutServiceContentConversionTest {

    private OutService outService;
    private OutMessageIdsRepository outMessageIdsRepository;

    @BeforeEach
    void setUp() {
        DomainIdUtils.setDomainDivider("@");
        outService = new OutService();
        outService.mediaUrlMappingService = mock(MediaUrlMappingService.class);
        outMessageIdsRepository = mock(OutMessageIdsRepository.class);
        outService.outMessageIdsRepository = outMessageIdsRepository;
    }

    @Test
    void convertsRejectJoinGroupIds() throws Exception {
        JsonObject object = convert(125, "{\"o\":\"op\",\"g\":\"g1\",\"mi\":\"u1\"}");
        assertEquals("op@A", object.get("o").getAsString());
        assertEquals("g1@A", object.get("g").getAsString());
        assertEquals("u1@A", object.get("mi").getAsString());
    }

    @Test
    void convertsAddGroupMemberApprover() throws Exception {
        JsonObject object = convert(105, "{\"o\":\"op\",\"n\":\"approver\",\"ms\":[\"u1\",\"u2\"],\"g\":\"g1\"}");
        assertEquals("approver@A", object.get("n").getAsString());
        assertEquals("u1@A", object.getAsJsonArray("ms").get(0).getAsString());
        assertEquals("u2@A", object.getAsJsonArray("ms").get(1).getAsString());
    }

    @Test
    void convertsCallStartBothTargetFields() throws Exception {
        JsonObject object = convert(400, "{\"ts\":[\"u1\"],\"t\":\"u1\"}");
        assertEquals("u1@A", object.getAsJsonArray("ts").get(0).getAsString());
        assertEquals("u1@A", object.get("t").getAsString());
    }

    @Test
    void convertsCallByeInviteMessageUid() throws Exception {
        OutMessageIds mapping = new OutMessageIds();
        mapping.messageId = 123;
        mapping.toDomainId = "B";
        mapping.toMessageId = 456;
        when(outMessageIdsRepository.findByMessageIdAndToDomainId(123L, "B")).thenReturn(Optional.of(mapping));

        JsonObject object = convert(402, "{\"r\":0,\"u\":123}", "call1");
        assertEquals(456, object.get("u").getAsLong());
    }

    @Test
    void convertsConferenceInviteHost() throws Exception {
        JsonObject object = convert(408, "{\"h\":\"host\",\"t\":\"title\"}", "call1");
        assertEquals("host@A", object.get("h").getAsString());
        assertEquals("title", object.get("t").getAsString());
    }

    @Test
    void convertsMultiCallOngoingIds() throws Exception {
        JsonObject object = convert(416, "{\"initiator\":\"u1\",\"targetIds\":[\"u2\"],\"targets\":[\"u2\"]}", "call1");
        assertEquals("u1@A", object.get("initiator").getAsString());
        assertEquals("u2@A", object.getAsJsonArray("targetIds").get(0).getAsString());
        assertEquals("u2@A", object.getAsJsonArray("targets").get(0).getAsString());
    }

    @Test
    void convertsPttInviteHost() throws Exception {
        JsonObject object = convert(420, "{\"h\":\"host\",\"t\":\"title\"}", "call1");
        assertEquals("host@A", object.get("h").getAsString());
        assertEquals("title", object.get("t").getAsString());
    }

    @Test
    void convertsCollectionIds() throws Exception {
        JsonObject object = convert(17, "{\"groupId\":\"g1\",\"creatorId\":\"u1\",\"entries\":[{\"userId\":\"u2\",\"content\":\"x\"}]}");
        assertEquals("g1@A", object.get("groupId").getAsString());
        assertEquals("u1@A", object.get("creatorId").getAsString());
        assertEquals("u2@A", object.getAsJsonArray("entries").get(0).getAsJsonObject().get("userId").getAsString());
        assertEquals("x", object.getAsJsonArray("entries").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void convertsPollIds() throws Exception {
        JsonObject poll = convert(18, "{\"groupId\":\"g1\",\"creatorId\":\"u1\",\"pollId\":\"p1\"}");
        assertEquals("g1@A", poll.get("groupId").getAsString());
        assertEquals("u1@A", poll.get("creatorId").getAsString());

        JsonObject result = convert(19, "{\"groupId\":\"g1\",\"creatorId\":\"u1\",\"pollId\":\"p1\"}");
        assertEquals("g1@A", result.get("groupId").getAsString());
        assertEquals("u1@A", result.get("creatorId").getAsString());
    }

    @Test
    void convertsCallStartPushDataParticipants() throws Exception {
        MessagePayload payload = payload(400, "{\"ts\":[\"u1\"],\"t\":\"u1\"}", "call1");
        payload.setPushData("{\"callId\":\"call1\",\"participants\":[\"u1\"]}");

        invoke(payload, "call1");

        JsonObject pushData = JsonParser.parseString(payload.getPushData()).getAsJsonObject();
        assertEquals("u1@A", pushData.getAsJsonArray("participants").get(0).getAsString());
    }

    @Test
    void convertsCallAddParticipantPushData() throws Exception {
        MessagePayload payload = payload(406, "{\"initiator\":\"u1\",\"participants\":[\"u1\"]}", "call1");
        payload.setPushData("{\"participants\":[\"u1\"],\"existParticipants\":[\"u2\"]}");

        invoke(payload, "call1");

        JsonObject pushData = JsonParser.parseString(payload.getPushData()).getAsJsonObject();
        assertEquals("u1@A", pushData.getAsJsonArray("participants").get(0).getAsString());
        assertEquals("u2@A", pushData.getAsJsonArray("existParticipants").get(0).getAsString());
    }

    @Test
    void convertsCallByePushDataInviteUid() throws Exception {
        OutMessageIds mapping = new OutMessageIds();
        mapping.messageId = 123;
        mapping.toDomainId = "B";
        mapping.toMessageId = 456;
        when(outMessageIdsRepository.findByMessageIdAndToDomainId(123L, "B")).thenReturn(Optional.of(mapping));
        MessagePayload payload = payload(402, "{\"r\":0,\"u\":123}", "call1");
        payload.setPushData("{\"r\":0,\"u\":123}");

        invoke(payload, "call1");

        JsonObject pushData = JsonParser.parseString(payload.getPushData()).getAsJsonObject();
        assertEquals(456, pushData.get("u").getAsLong());
    }

    @Test
    void convertsTranscriptionUserId() throws Exception {
        MessagePayload payload = new MessagePayload();
        payload.setType(26);
        payload.setContent("{\"userId\":\"u1\",\"content\":\"hello\"}");

        invoke(payload, "call1");

        JsonObject object = JsonParser.parseString(payload.getContent()).getAsJsonObject();
        assertEquals("u1@A", object.get("userId").getAsString());
        assertEquals("hello", object.get("content").getAsString());
    }

    private MessagePayload payload(int type, String json, String content) {
        MessagePayload payload = new MessagePayload();
        payload.setType(type);
        payload.setContent(content);
        payload.setBase64edData(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        return payload;
    }

    private JsonObject convert(int type, String json) throws Exception {
        return convert(type, json, null);
    }

    private JsonObject convert(int type, String json, String content) throws Exception {
        MessagePayload payload = new MessagePayload();
        payload.setType(type);
        payload.setContent(content);
        payload.setBase64edData(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));

        invoke(payload, content);

        return JsonParser.parseString(new String(Base64.getDecoder().decode(payload.getBase64edData()), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void invoke(MessagePayload payload, String content) throws Exception {
        Method method = OutService.class.getDeclaredMethod("convertMessagePayloadDomainId", String.class, MessagePayload.class,
            String.class, boolean.class, boolean.class, String.class, OutService.ConvertCallback.class);
        method.setAccessible(true);
        method.invoke(outService, "B", payload, "A", false, false, "userA", new OutService.ConvertCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int errorCode) {
                throw new AssertionError("convert failed: " + errorCode);
            }
        });
    }
}
