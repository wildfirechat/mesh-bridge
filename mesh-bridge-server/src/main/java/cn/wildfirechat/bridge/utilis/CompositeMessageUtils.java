package cn.wildfirechat.bridge.utilis;

import cn.wildfirechat.pojos.MessagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

//组合消息(type=11)的JSON结构读写工具。
//格式定义参考 android-chat/client/src/main/java/cn/wildfirechat/message/CompositeMessageContent.java：
//{"ms":[{uid,type,target,line,from,tos,direction,status,serverTime,ctype,csc,cpc,cpd,cc,cbc,cmt,cmts,ce,mt,mru}, ...]}
//其中 uid 是子消息的 messageUid，ctype/cc/cbc/cmt/cmts/ce/mt/mru 对应一个独立的 MessagePayload。
public final class CompositeMessageUtils {
    public static final int CONTENT_TYPE_COMPOSITE = 11;

    //组合消息最大嵌套层数，防止异常数据导致无限递归
    public static final int MAX_NEST_DEPTH = 32;

    private static final String KEY_MESSAGES = "ms";

    private CompositeMessageUtils() {
    }

    public static boolean isComposite(MessagePayload payload) {
        return payload != null && payload.getType() == CONTENT_TYPE_COMPOSITE;
    }

    public static JsonObject decode(String base64edData) {
        if(base64edData == null || base64edData.isEmpty()) {
            throw new IllegalArgumentException("composite message data is empty");
        }
        return decode(Base64.getDecoder().decode(base64edData));
    }

    public static JsonObject decode(byte[] data) {
        if(data == null || data.length == 0) {
            throw new IllegalArgumentException("composite message data is empty");
        }
        JsonElement element = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        if(!element.isJsonObject()) {
            throw new IllegalArgumentException("composite message is not a json object");
        }
        JsonObject composite = element.getAsJsonObject();
        if(!composite.has(KEY_MESSAGES) || !composite.get(KEY_MESSAGES).isJsonArray()) {
            throw new IllegalArgumentException("composite message has no messages");
        }
        return composite;
    }

    public static String encode(JsonObject composite) {
        return Base64.getEncoder().encodeToString(composite.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static JsonArray messages(JsonObject composite) {
        return composite.getAsJsonArray(KEY_MESSAGES);
    }

    public static String getString(JsonObject object, String key) {
        if(object == null || !object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if(element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static int getInt(JsonObject object, String key) {
        if(object == null || !object.has(key)) {
            return 0;
        }
        JsonElement element = object.get(key);
        if(element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long getLong(JsonObject object, String key) {
        if(object == null || !object.has(key)) {
            return 0;
        }
        JsonElement element = object.get(key);
        if(element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static List<String> getStringList(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        if(object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return result;
        }
        for (JsonElement element : object.getAsJsonArray(key)) {
            if(!element.isJsonNull()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    //把组合消息里的一个子消息转换成 MessagePayload，cbc 本身已经是 Base64 字符串，直接赋值，不能重复编码
    public static MessagePayload toPayload(JsonObject message) {
        MessagePayload payload = new MessagePayload();
        payload.setType(getInt(message, "ctype"));
        payload.setSearchableContent(getString(message, "csc"));
        payload.setPushContent(getString(message, "cpc"));
        payload.setPushData(getString(message, "cpd"));
        payload.setContent(getString(message, "cc"));
        payload.setBase64edData(getString(message, "cbc"));
        payload.setMentionedType(getInt(message, "cmt"));
        payload.setMentionedTarget(getStringList(message, "cmts"));
        payload.setExtra(getString(message, "ce"));
        payload.setMediaType(getInt(message, "mt"));
        payload.setRemoteMediaUrl(getString(message, "mru"));
        return payload;
    }

    //把转换后的 MessagePayload 写回子消息JSON。只写回真正变化的字段，避免给文本消息凭空加上 mt/cmt/cmts 等字段。
    public static void updatePayload(JsonObject message, MessagePayload payload) {
        setIntIfChanged(message, "ctype", payload.getType());
        setStringIfChanged(message, "csc", payload.getSearchableContent());
        setStringIfChanged(message, "cpc", payload.getPushContent());
        setStringIfChanged(message, "cpd", payload.getPushData());
        setStringIfChanged(message, "cc", payload.getContent());
        setStringIfChanged(message, "cbc", payload.getBase64edData());
        setIntIfChanged(message, "cmt", payload.getMentionedType());
        if(payload.getMentionedTarget() != null) {
            setStringListIfChanged(message, "cmts", payload.getMentionedTarget());
        }
        setStringIfChanged(message, "ce", payload.getExtra());
        setIntIfChanged(message, "mt", payload.getMediaType());
        setStringIfChanged(message, "mru", payload.getRemoteMediaUrl());
    }

    public static void setStringIfChanged(JsonObject object, String key, String value) {
        if(Objects.equals(getString(object, key), value)) {
            return;
        }
        if(value == null) {
            object.remove(key);
        } else {
            object.addProperty(key, value);
        }
    }

    private static void setIntIfChanged(JsonObject object, String key, int value) {
        boolean present = object.has(key) && object.get(key).isJsonPrimitive();
        if(present && getInt(object, key) == value) {
            return;
        }
        if(!present && value == 0) {
            return;
        }
        object.addProperty(key, value);
    }

    private static void setStringListIfChanged(JsonObject object, String key, List<String> values) {
        if(getStringList(object, key).equals(values)) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        object.add(key, array);
    }
}
