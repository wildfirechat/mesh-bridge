package cn.wildfirechat.bridge.utilis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaTransferUtilsTest {

    @Test
    void roundTripKeepsQueryAndNonAscii() {
        String url = "https://a-media.example.com/图片/1.png?sign=abc&t=1+2";
        String encoded = MediaTransferUtils.encodeSourceUrl(url);

        assertEquals(url, MediaTransferUtils.decodeSourceUrl(encoded));
    }

    @Test
    void roundTripKeepsPlusAndPercent() {
        String url = "https://a/x.png?expr=1+2&name=%E4%B8%AD%E6%96%87";
        assertEquals(url, MediaTransferUtils.decodeSourceUrl(MediaTransferUtils.encodeSourceUrl(url)));
    }

    @Test
    void rejectsUnsupportedInput() {
        assertNull(MediaTransferUtils.encodeSourceUrl(null));
        assertNull(MediaTransferUtils.encodeSourceUrl(""));
        assertNull(MediaTransferUtils.encodeSourceUrl("ftp://a/x.png"));
        assertNull(MediaTransferUtils.encodeSourceUrl("file:///tmp/x.png"));
        assertNull(MediaTransferUtils.decodeSourceUrl(null));
        assertNull(MediaTransferUtils.decodeSourceUrl(""));
        assertNull(MediaTransferUtils.decodeSourceUrl("%zz"));
        //不是http/https的地址即使编码正确也要丢掉
        assertNull(MediaTransferUtils.decodeSourceUrl(java.net.URLEncoder.encode("ftp://a/x.png", java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsTooLongUrl() {
        StringBuilder builder = new StringBuilder("https://a/x.png?");
        while (builder.length() <= 4096) {
            builder.append('a');
        }
        assertNull(MediaTransferUtils.encodeSourceUrl(builder.toString()));
    }
}
