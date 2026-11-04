package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.Domain;
import cn.wildfirechat.bridge.jpa.DomainRepository;
import cn.wildfirechat.bridge.jpa.InMessageIds;
import cn.wildfirechat.bridge.jpa.InMessageIdsKey;
import cn.wildfirechat.bridge.jpa.InMessageIdsRepository;
import cn.wildfirechat.bridge.jpa.OutMessageIds;
import cn.wildfirechat.bridge.jpa.OutMessageIdsRepository;
import cn.wildfirechat.bridge.utilis.CompositeMessageUtils;
import cn.wildfirechat.bridge.utilis.DomainIdUtils;
import cn.wildfirechat.pojos.MessagePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutServiceCompositeConversionTest {

    //让 transferFile 里的线程池任务同步执行，测试里好断言
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private TestOutService outService;

    @BeforeEach
    void setUp() {
        DomainIdUtils.setDomainDivider("@");
        outService = new TestOutService();
        outService.myDomainId = "A";
        outService.mediaPublicAccessible = false;
        outService.mediaUrlMappingService = mock(MediaUrlMappingService.class);
        outService.outMessageIdsRepository = mock(OutMessageIdsRepository.class);
        outService.inMessageIdsRepository = mock(InMessageIdsRepository.class);
        //让 transferFile/transferCompositeFile 里的地址校验和域校验通过，真正的上传由 TestOutService 替换
        outService.domainRepository = mock(DomainRepository.class);
        Domain domain = new Domain();
        domain.domainId = "B";
        domain.url = "https://b";
        domain.secret = "secret";
        when(outService.domainRepository.findById(anyString())).thenReturn(Optional.of(domain));
        outService.transferFileExecutor = DIRECT_EXECUTOR;
        outService.environment = mock(Environment.class);
        outService.mediaDownloadHostWhitelist = "a";
        outService.allowPrivateMediaHosts = true;
    }

    @Test
    void convertsChildUserAndGroupIds() throws Exception {
        MessagePayload payload = composite(text("a", "u1", 100), text("b", "u2", 101));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        JsonArray messages = messages(payload);
        assertEquals("u1@A", CompositeMessageUtils.getString(messages.get(0).getAsJsonObject(), "from"));
        assertEquals("g1@A", CompositeMessageUtils.getString(messages.get(0).getAsJsonObject(), "target"));
        assertEquals("u2@A", CompositeMessageUtils.getString(messages.get(1).getAsJsonObject(), "from"));
    }

    @Test
    void convertsChildUidWhenMappingExists() throws Exception {
        when(outService.outMessageIdsRepository.findByMessageIds(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(outMessageIds(100L, "B", 900L)));

        MessagePayload payload = composite(text("a", "u1", 100));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        assertEquals(900L, CompositeMessageUtils.getLong(messages(payload).get(0).getAsJsonObject(), "uid"));
    }

    @Test
    void queriesChildUidsInOneBatch() throws Exception {
        MessagePayload payload = composite(text("a", "u1", 100), text("b", "u1", 101), text("c", "u1", 102));

        run("B", payload, false, false);

        //整层子消息只查一次，不再逐个子消息查
        verify(outService.outMessageIdsRepository, times(1)).findByMessageIds(eq("B"), anyCollection());
        verify(outService.outMessageIdsRepository, never()).findByMessageIdAndToDomainId(anyLong(), anyString());
    }

    @Test
    void setsChildUidToZeroWhenNoMappingFound() throws Exception {
        MessagePayload payload = composite(text("a", "u1", 100));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        assertEquals(0L, CompositeMessageUtils.getLong(messages(payload).get(0).getAsJsonObject(), "uid"));
    }

    @Test
    void doesNotUseOriginDomainUidWhenPublishingToThirdDomain() throws Exception {
        //C域用户发的消息，发布到B域时不能把C域的uid写进去，也不保留(会被误当成本域uid)，一律置0
        MessagePayload payload = composite(text("a", "u9@C", 555));

        RecordingCallback callback = run("B", payload, true, false);

        assertEquals(1, callback.success.get());
        assertEquals(0L, CompositeMessageUtils.getLong(messages(payload).get(0).getAsJsonObject(), "uid"));
        verify(outService.inMessageIdsRepository, never()).findByDomainIdAndLocalMessageId(anyString(), anyLong());
    }

    @Test
    void convertsChildUidBackWhenPublishingToOriginDomain() throws Exception {
        when(outService.inMessageIdsRepository.findByLocalMessageIds(eq("C"), anyCollection()))
            .thenReturn(Collections.singletonList(inMessageIds(777L, "C", 555L)));

        MessagePayload payload = composite(text("a", "u9@C", 555));

        RecordingCallback callback = run("C", payload, true, false);

        assertEquals(1, callback.success.get());
        assertEquals(777L, CompositeMessageUtils.getLong(messages(payload).get(0).getAsJsonObject(), "uid"));
    }

    @Test
    void convertsNestedCompositeMessages() throws Exception {
        JsonObject inner = text("inner", "u2", 200);
        JsonArray innerMessages = new JsonArray();
        innerMessages.add(inner);
        JsonObject innerComposite = new JsonObject();
        innerComposite.add("ms", innerMessages);

        JsonObject nested = new JsonObject();
        nested.addProperty("uid", 300);
        nested.addProperty("ctype", 11);
        nested.addProperty("from", "u1");
        nested.addProperty("target", "g1");
        nested.addProperty("mt", 4);
        nested.addProperty("cbc", CompositeMessageUtils.encode(innerComposite));

        MessagePayload payload = composite(nested);

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        JsonObject convertedNested = messages(payload).get(0).getAsJsonObject();
        JsonObject convertedInner = CompositeMessageUtils.messages(
            CompositeMessageUtils.decode(CompositeMessageUtils.getString(convertedNested, "cbc"))).get(0).getAsJsonObject();
        assertEquals("u2@A", CompositeMessageUtils.getString(convertedInner, "from"));
        assertEquals("g1@A", CompositeMessageUtils.getString(convertedInner, "target"));
    }

    @Test
    void waitsForAsyncMediaBeforeConvertingNextChild() throws Exception {
        outService.delayMs = 30;
        MessagePayload payload = composite(
            text("a", "u1", 1),
            image("https://a/1.png", "u1", 2),
            text("b", "u1", 3),
            image("https://a/3.png", "u1", 4),
            text("c", "u1", 5));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        assertEquals(0, callback.failures.get());
        assertEquals("https://target/1.png", CompositeMessageUtils.getString(messages(payload).get(1).getAsJsonObject(), "mru"));
        assertEquals("https://target/3.png", CompositeMessageUtils.getString(messages(payload).get(3).getAsJsonObject(), "mru"));
        //串行处理：第二个媒体要在第一个媒体传完之后才开始
        assertEquals(2, outService.transferred.size());
    }

    @Test
    void propagatesAsyncMediaFailureAndStops() throws Exception {
        outService.delayMs = 20;
        outService.failOnUrl = "https://a/3.png";
        outService.failCode = 9001;
        MessagePayload payload = composite(
            text("a", "u1", 1),
            image("https://a/1.png", "u1", 2),
            text("b", "u1", 3),
            image("https://a/3.png", "u1", 4),
            text("c", "u1", 5));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(0, callback.success.get());
        assertEquals(1, callback.failures.get());
        assertEquals(9001, callback.errorCode);
        //失败后不再处理后面的子消息
        assertEquals(2, outService.transferred.size());
    }

    @Test
    void failsOnMalformedComposite() throws Exception {
        MessagePayload payload = new MessagePayload();
        payload.setType(11);
        payload.setBase64edData(java.util.Base64.getEncoder().encodeToString("not json".getBytes()));

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(0, callback.success.get());
        assertEquals(1, callback.failures.get());
        assertNotNull(payload.getBase64edData());
    }

    @Test
    void transfersCompositeFileEvenWhenMediaIsPubliclyAccessible() throws Exception {
        outService.mediaPublicAccessible = true;
        MessagePayload payload = composite(text("a", "u1", 1));
        payload.setMediaType(4);
        payload.setRemoteMediaUrl("https://a/chat.data");

        RecordingCallback callback = run("B", payload, false, false);

        assertEquals(1, callback.success.get());
        assertEquals("https://target/chat.data", payload.getRemoteMediaUrl());
    }

    @Test
    void skipsCompositeFileTransferWhenPublishingBackToOrigin() throws Exception {
        MessagePayload payload = composite(text("a", "u1", 1));
        payload.setMediaType(4);
        payload.setRemoteMediaUrl("https://a/chat.data");

        RecordingCallback callback = run("B", payload, true, true);

        assertEquals(1, callback.success.get());
        assertEquals("https://a/chat.data", payload.getRemoteMediaUrl());
        assertTrue(outService.transferred.isEmpty());
    }

    private RecordingCallback run(String remoteDomainId, MessagePayload payload, boolean isPublish, boolean isPublish2OriginalDomain) throws Exception {
        RecordingCallback callback = new RecordingCallback();
        Method method = OutService.class.getDeclaredMethod("convertMessagePayloadDomainId", String.class, MessagePayload.class,
            String.class, boolean.class, boolean.class, OutService.ConvertCallback.class);
        method.setAccessible(true);
        try {
            method.invoke(outService, remoteDomainId, payload, "A", isPublish, isPublish2OriginalDomain, callback);
        } catch (InvocationTargetException e) {
            fail("convert message payload failed", e.getCause());
        }
        callback.await();
        return callback;
    }

    private static JsonArray messages(MessagePayload payload) {
        return CompositeMessageUtils.messages(CompositeMessageUtils.decode(payload.getBase64edData()));
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

    @Test
    void reusesSourceDomainUrlFromReverseMappingWhenSendingBack() throws Exception {
        //接收方在收文件时记录了 (domain=A, local=B_url, remote=A_url) 的反向映射，
        //B 再把这个文件发回 A 时应该直接复用 A 的原地址，不再重新上传
        when(outService.mediaUrlMappingService.findRemoteMediaUrl("A", "https://b/x.png"))
            .thenReturn(Optional.of("https://a/x.png"));
        MessagePayload payload = composite(image("https://b/x.png", "userA@A", 2000));

        RecordingCallback callback = run("A", payload, false, false);

        assertEquals(1, callback.success.get());
        assertEquals("https://a/x.png", CompositeMessageUtils.getString(messages(payload).get(0).getAsJsonObject(), "mru"));
        assertTrue(outService.transferred.isEmpty());
    }

    private static JsonObject text(String content, String from, long uid) {
        JsonObject message = new JsonObject();
        message.addProperty("uid", uid);
        message.addProperty("type", 1);
        message.addProperty("target", "g1");
        message.addProperty("from", from);
        message.addProperty("ctype", 1);
        message.addProperty("csc", content);
        message.addProperty("cc", content);
        return message;
    }

    private static JsonObject image(String mru, String from, long uid) {
        JsonObject message = new JsonObject();
        message.addProperty("uid", uid);
        message.addProperty("type", 1);
        message.addProperty("target", "g1");
        message.addProperty("from", from);
        message.addProperty("ctype", 3);
        message.addProperty("mru", mru);
        message.addProperty("mt", 1);
        return message;
    }

    private static InMessageIds inMessageIds(long messageId, String domainId, long localMessageId) {
        InMessageIds ids = new InMessageIds();
        ids.id = new InMessageIdsKey(messageId, domainId);
        ids.localMessageId = localMessageId;
        return ids;
    }

    private static OutMessageIds outMessageIds(long messageId, String domainId, long toMessageId) {
        OutMessageIds ids = new OutMessageIds();
        ids.messageId = messageId;
        ids.toDomainId = domainId;
        ids.toMessageId = toMessageId;
        return ids;
    }

    private static class RecordingCallback implements OutService.ConvertCallback {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        volatile int errorCode;

        @Override
        public void onSuccess() {
            success.incrementAndGet();
            latch.countDown();
        }

        @Override
        public void onFailure(int errorCode) {
            this.errorCode = errorCode;
            failures.incrementAndGet();
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "convert callback timeout");
            //留一点时间给可能出现的重复回调
            Thread.sleep(20);
        }
    }

    private static class TestOutService extends OutService {
        final List<String> transferred = Collections.synchronizedList(new ArrayList<>());
        volatile long delayMs;
        volatile String failOnUrl;
        volatile int failCode;

        //替换真正的"下载+上传"步骤，前面的映射查询、地址校验等逻辑仍然走生产代码
        @Override
        void doTransferFile(Domain domain, String remoteDomainId, String mediaUrl, int mediaType, TransferFileCallback callback) {
            transferred.add(mediaUrl);
            complete(delayMs, mediaUrl, callback);
        }

        @Override
        void downloadCompositeFile(Domain domain, String remoteDomainId, String mediaUrl, int mediaType, String localDomainId, boolean isPublish, int depth, TransferFileCallback callback) {
            transferred.add(mediaUrl);
            complete(delayMs, mediaUrl, callback);
        }

        private void complete(long delay, String mediaUrl, TransferFileCallback callback) {
            Runnable task = () -> {
                if (mediaUrl.equals(failOnUrl)) {
                    callback.onFailure(failCode, "mock transfer failure");
                    return;
                }
                int index = mediaUrl.lastIndexOf('/');
                callback.onSuccess("https://target/" + mediaUrl.substring(index + 1));
            };
            if (delay <= 0) {
                task.run();
                return;
            }
            new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            }).start();
        }
    }
}
