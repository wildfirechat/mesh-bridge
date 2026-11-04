package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.utilis.MediaTransferUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OutServiceTransferHeaderTest {

    @Test
    void carriesSourceMediaUrlInHeader() {
        OutService outService = new OutService();

        Map<String, String> headers = outService.transferFileHeaders("https://a-media/x.png?sign=1&t=2", 1);

        assertEquals("1", headers.get("x-media-type"));
        assertEquals(".png", headers.get("x-file-extension"));
        assertEquals("https://a-media/x.png?sign=1&t=2",
            MediaTransferUtils.decodeSourceUrl(headers.get(MediaTransferUtils.SOURCE_MEDIA_URL_HEADER)));
    }

    @Test
    void skipsHeaderForUnsupportedSourceUrl() {
        OutService outService = new OutService();

        Map<String, String> headers = outService.transferFileHeaders("file:///tmp/x.png", 1);

        assertFalse(headers.containsKey(MediaTransferUtils.SOURCE_MEDIA_URL_HEADER));
    }
}
