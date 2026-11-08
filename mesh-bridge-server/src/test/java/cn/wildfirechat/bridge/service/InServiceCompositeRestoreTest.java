package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.OutMessageIds;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.bridge.utilis.CompositeMessageUtils;
import cn.wildfirechat.pojos.MessagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InServiceCompositeRestoreTest {

    private InService inService;
    private MediaUrlMappingService mediaUrlMappingService;
    private OutMessageIdsRepository outMessageIdsRepository;

    @BeforeEach
    void setUp() {
        inService = new InService();
        mediaUrlMappingService = mock(MediaUrlMappingService.class);
        outMessageIdsRepository = mock(OutMessageIdsRepository.class);
        inService.mediaUrlMappingService = mediaUrlMappingService;
        inService.outMessageIdsRepository = outMessageIdsRepository;
    }

    @Test
    void restoresChildMediaUrl() throws Exception {
        when(mediaUrlMappingService.findLocalMediaUrl("B", "https://b/x.png")).thenReturn(Optional.of("https://a/x.png"));
        MessagePayload payload = composite(child(900, "https://b/x.png", 1));

        restore("B", payload);

        JsonObject message = childMessage(payload);
        assertEquals("https://a/x.png", CompositeMessageUtils.getString(message, "mru"));
    }

    @Test
    void restoresChildMediaUrlFromOutMessageMappingWhenMediaMappingMissing() throws Exception {
        //媒体映射表没有记录时，用 out_message_ids.media_url 里保存的本域原始地址兜底
        when(outMessageIdsRepository.findByToMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping(100L, "https://a/x.png")));
        MessagePayload payload = composite(child(900, "https://b/x.png", 1));

        restore("B", payload);

        JsonObject message = childMessage(payload);
        assertEquals(100L, CompositeMessageUtils.getLong(message, "uid"));
        assertEquals("https://a/x.png", CompositeMessageUtils.getString(message, "mru"));
    }

    @Test
    void restoresChildMediaUrlWhenUidIsAlreadyLocal() throws Exception {
        //发送方已经用 in_message_ids 把子消息 uid 换成了本域消息ID，此时按 id 查 out_message_ids 取原始地址
        when(outMessageIdsRepository.findByMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping(900L, "https://a/x.png")));
        MessagePayload payload = composite(child(900, "https://b/x.png", 1));

        restore("B", payload);

        assertEquals("https://a/x.png", CompositeMessageUtils.getString(childMessage(payload), "mru"));
    }

    @Test
    void restoresChildUid() throws Exception {
        when(outMessageIdsRepository.findByToMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping(100L)));
        MessagePayload payload = composite(child(900, null, 1));

        restore("B", payload);

        assertEquals(100L, CompositeMessageUtils.getLong(childMessage(payload), "uid"));
    }

    @Test
    void queriesChildUidsInOneBatch() throws Exception {
        MessagePayload payload = composite(child(900, null, 1), child(901, null, 1), child(902, null, 1));

        restore("B", payload);

        //整层子消息只查一次
        verify(outMessageIdsRepository, times(1)).findByToMessageIds(eq("B"), anyCollection());
        verify(outMessageIdsRepository, never()).findByToDomainIdAndToMessageId(anyString(), anyLong());
    }

    @Test
    void restoresNestedComposite() throws Exception {
        when(mediaUrlMappingService.findLocalMediaUrl("B", "https://b/inner.png")).thenReturn(Optional.of("https://a/inner.png"));
        JsonObject inner = child(1, "https://b/inner.png", 1);
        JsonArray innerMessages = new JsonArray();
        innerMessages.add(inner);
        JsonObject innerComposite = new JsonObject();
        innerComposite.add("ms", innerMessages);

        JsonObject nested = new JsonObject();
        nested.addProperty("uid", 2);
        nested.addProperty("ctype", 11);
        nested.addProperty("from", "u1");
        nested.addProperty("cbc", CompositeMessageUtils.encode(innerComposite));
        MessagePayload payload = composite(nested);

        restore("B", payload);

        JsonObject convertedNested = childMessage(payload);
        JsonObject convertedInner = CompositeMessageUtils.messages(
            CompositeMessageUtils.decode(CompositeMessageUtils.getString(convertedNested, "cbc"))).get(0).getAsJsonObject();
        assertEquals("https://a/inner.png", CompositeMessageUtils.getString(convertedInner, "mru"));
    }

    @Test
    void restoresNestedChildUid() throws Exception {
        when(outMessageIdsRepository.findByToMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping(100L)));
        MessagePayload payload = composite(nested(2, child(900, null, 1)));

        restore("B", payload);

        JsonObject inner = CompositeMessageUtils.messages(
            CompositeMessageUtils.decode(CompositeMessageUtils.getString(childMessage(payload), "cbc"))).get(0).getAsJsonObject();
        assertEquals(100L, CompositeMessageUtils.getLong(inner, "uid"));
    }

    @Test
    void restoresNestedChildMediaUrlFromOutMessageMapping() throws Exception {
        when(outMessageIdsRepository.findByMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping(100L, "https://a/x.png")));
        MessagePayload payload = composite(nested(2, child(100, "https://b/x.png", 1)));

        restore("B", payload);

        JsonObject inner = CompositeMessageUtils.messages(
            CompositeMessageUtils.decode(CompositeMessageUtils.getString(childMessage(payload), "cbc"))).get(0).getAsJsonObject();
        assertEquals("https://a/x.png", CompositeMessageUtils.getString(inner, "mru"));
    }

    @Test
    void keepsPayloadUnchangedWhenNestedTooDeep() throws Exception {
        MessagePayload payload = deeplyNestedComposite(40);
        String original = payload.getBase64edData();

        restore("B", payload);

        assertEquals(original, payload.getBase64edData());
    }

    @Test
    void keepsPayloadUnchangedWhenNothingMatched() throws Exception {
        MessagePayload payload = composite(child(900, "https://b/x.png", 1));
        String original = payload.getBase64edData();

        restore("B", payload);

        assertEquals(original, payload.getBase64edData());
    }

    @Test
    void keepsPayloadUnchangedWhenCompositeIsMalformed() throws Exception {
        MessagePayload payload = new MessagePayload();
        payload.setType(11);
        payload.setBase64edData(Base64.getEncoder().encodeToString("not json".getBytes()));

        //解析失败不能影响消息投递，这里要求不抛异常且内容不变
        restore("B", payload);

        assertEquals(Base64.getEncoder().encodeToString("not json".getBytes()), payload.getBase64edData());
    }

    @Test
    void ignoresNonCompositePayload() throws Exception {
        MessagePayload payload = new MessagePayload();
        payload.setType(1);
        payload.setBase64edData("abc");

        restore("B", payload);

        assertEquals("abc", payload.getBase64edData());
    }

    @Test
    void doesNotRewriteWhenMappingPointsToSameUrl() throws Exception {
        when(mediaUrlMappingService.findLocalMediaUrl("B", "https://b/x.png")).thenReturn(Optional.of("https://b/x.png"));
        MessagePayload payload = composite(child(900, "https://b/x.png", 1));
        String original = payload.getBase64edData();

        restore("B", payload);

        assertEquals(original, payload.getBase64edData());
        assertNotEquals(null, payload.getBase64edData());
    }

    private void restore(String domainId, MessagePayload payload) throws Exception {
        Method method = InService.class.getDeclaredMethod("restoreCompositeMediaUrls", String.class, MessagePayload.class);
        method.setAccessible(true);
        try {
            method.invoke(inService, domainId, payload);
        } catch (InvocationTargetException e) {
            throw new AssertionError("restore composite message should not throw", e.getCause());
        }
    }

    private static JsonObject childMessage(MessagePayload payload) {
        JsonArray messages = CompositeMessageUtils.messages(CompositeMessageUtils.decode(payload.getBase64edData()));
        assertNotNull(messages);
        return messages.get(0).getAsJsonObject();
    }

    private static MessagePayload composite(JsonObject... children) {
        JsonArray messages = new JsonArray();
        for (JsonObject child : children) {
            messages.add(child);
        }
        JsonObject composite = new JsonObject();
        composite.add("ms", messages);
        MessagePayload payload = new MessagePayload();
        payload.setType(11);
        payload.setBase64edData(CompositeMessageUtils.encode(composite));
        return payload;
    }

    private static MessagePayload deeplyNestedComposite(int levels) {
        JsonObject inner = child(1, null, 1);
        for (int i = 0; i < levels; i++) {
            JsonArray messages = new JsonArray();
            messages.add(inner);
            JsonObject innerComposite = new JsonObject();
            innerComposite.add("ms", messages);
            JsonObject nested = new JsonObject();
            nested.addProperty("uid", 100 + i);
            nested.addProperty("ctype", 11);
            nested.addProperty("from", "u1");
            nested.addProperty("cbc", CompositeMessageUtils.encode(innerComposite));
            inner = nested;
        }
        return composite(inner);
    }

    private static JsonObject nested(long uid, JsonObject... innerChildren) {
        JsonArray innerMessages = new JsonArray();
        for (JsonObject innerChild : innerChildren) {
            innerMessages.add(innerChild);
        }
        JsonObject innerComposite = new JsonObject();
        innerComposite.add("ms", innerMessages);

        JsonObject nested = new JsonObject();
        nested.addProperty("uid", uid);
        nested.addProperty("ctype", 11);
        nested.addProperty("from", "u1");
        nested.addProperty("cbc", CompositeMessageUtils.encode(innerComposite));
        return nested;
    }

    private static JsonObject child(long uid, String remoteMediaUrl, int ctype) {
        JsonObject message = new JsonObject();
        message.addProperty("uid", uid);
        message.addProperty("ctype", ctype);
        message.addProperty("from", "u1");
        message.addProperty("target", "g1");
        if (remoteMediaUrl != null) {
            message.addProperty("mru", remoteMediaUrl);
            message.addProperty("mt", 1);
        }
        return message;
    }

    private static OutMessageIds mapping(long messageId) {
        return mapping(messageId, null);
    }

    private static OutMessageIds mapping(long messageId, String mediaUrl) {
        OutMessageIds ids = new OutMessageIds();
        ids.messageId = messageId;
        ids.toDomainId = "B";
        ids.toMessageId = 900;
        ids.mediaUrl = mediaUrl;
        return ids;
    }
}
