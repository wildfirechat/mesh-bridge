package cn.wildfirechat.bridge.utilis;

import cn.wildfirechat.pojos.MessagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeMessageUtilsTest {

    @Test
    void decodeAndEncodeComposite() {
        JsonArray messages = new JsonArray();
        messages.add(message("hello"));
        JsonObject composite = new JsonObject();
        composite.add("ms", messages);

        String encoded = CompositeMessageUtils.encode(composite);
        JsonObject decoded = CompositeMessageUtils.decode(encoded);

        assertEquals(1, CompositeMessageUtils.messages(decoded).size());
        assertEquals("hello", CompositeMessageUtils.getString(CompositeMessageUtils.messages(decoded).get(0).getAsJsonObject(), "csc"));
    }

    @Test
    void decodeRejectsInvalidData() {
        assertThrows(IllegalArgumentException.class, () -> CompositeMessageUtils.decode(""));
        assertThrows(IllegalArgumentException.class, () -> CompositeMessageUtils.decode((String) null));
        String noMessages = Base64.getEncoder().encodeToString("{\"a\":1}".getBytes());
        assertThrows(IllegalArgumentException.class, () -> CompositeMessageUtils.decode(noMessages));
        String arrayRoot = Base64.getEncoder().encodeToString("[]".getBytes());
        assertThrows(IllegalArgumentException.class, () -> CompositeMessageUtils.decode(arrayRoot));
        String brokenJson = Base64.getEncoder().encodeToString("not json".getBytes());
        assertThrows(RuntimeException.class, () -> CompositeMessageUtils.decode(brokenJson));
    }

    @Test
    void payloadRoundTripKeepsAllFields() {
        JsonObject message = message("hello");
        message.addProperty("cbc", "eyJhIjoxfQ==");
        message.addProperty("mt", 4);
        message.addProperty("mru", "https://a/x.data");
        message.addProperty("cmt", 1);
        JsonArray cmts = new JsonArray();
        cmts.add("u1");
        cmts.add("u2");
        message.add("cmts", cmts);

        MessagePayload payload = CompositeMessageUtils.toPayload(message);
        assertEquals(1, payload.getType());
        assertEquals("hello", payload.getSearchableContent());
        assertEquals("eyJhIjoxfQ==", payload.getBase64edData());
        assertEquals(4, payload.getMediaType());
        assertEquals("https://a/x.data", payload.getRemoteMediaUrl());
        assertEquals(Arrays.asList("u1", "u2"), payload.getMentionedTarget());

        JsonObject target = new JsonObject();
        CompositeMessageUtils.updatePayload(target, payload);
        assertEquals("hello", CompositeMessageUtils.getString(target, "csc"));
        assertEquals("eyJhIjoxfQ==", CompositeMessageUtils.getString(target, "cbc"));
        assertEquals(4, CompositeMessageUtils.getInt(target, "mt"));
        assertEquals(Arrays.asList("u1", "u2"), CompositeMessageUtils.getStringList(target, "cmts"));
    }

    @Test
    void updatePayloadOnlyWritesChangedFields() {
        JsonObject message = message("hello");
        MessagePayload payload = CompositeMessageUtils.toPayload(message);
        payload.setSearchableContent("hello");

        CompositeMessageUtils.updatePayload(message, payload);

        //文本子消息不应该被凭空加上 mt/cmt/cmts 字段
        assertFalse(message.has("mt"));
        assertFalse(message.has("cmt"));
        assertFalse(message.has("cmts"));
        assertFalse(message.has("mru"));
    }

    @Test
    void updatePayloadRemovesClearedFields() {
        JsonObject message = message("hello");
        message.addProperty("mru", "https://a/x.png");
        message.addProperty("ce", "extra");
        MessagePayload payload = CompositeMessageUtils.toPayload(message);
        payload.setRemoteMediaUrl(null);
        payload.setExtra(null);

        CompositeMessageUtils.updatePayload(message, payload);

        assertFalse(message.has("mru"));
        assertFalse(message.has("ce"));
    }

    @Test
    void unknownFieldsArePreserved() {
        JsonObject message = message("hello");
        message.addProperty("le", "local-extra");
        message.addProperty("serverTime", 123456789L);

        MessagePayload payload = CompositeMessageUtils.toPayload(message);
        CompositeMessageUtils.updatePayload(message, payload);

        assertEquals("local-extra", CompositeMessageUtils.getString(message, "le"));
        assertEquals(123456789L, CompositeMessageUtils.getLong(message, "serverTime"));
    }

    @Test
    void gettersAreTolerant() {
        JsonObject message = new JsonObject();
        message.add("bad", new JsonArray());
        message.addProperty("uid", "123");
        assertEquals(0, CompositeMessageUtils.getInt(message, "bad"));
        assertEquals(0, CompositeMessageUtils.getInt(message, "missing"));
        assertEquals(123L, CompositeMessageUtils.getLong(message, "uid"));
        assertNull(CompositeMessageUtils.getString(message, "bad"));
        assertTrue(CompositeMessageUtils.getStringList(message, "missing").isEmpty());
    }

    private static JsonObject message(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("uid", 100);
        message.addProperty("type", 1);
        message.addProperty("target", "u2");
        message.addProperty("from", "u1");
        message.addProperty("ctype", 1);
        message.addProperty("csc", text);
        return message;
    }
}
