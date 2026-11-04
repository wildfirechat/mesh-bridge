package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.utilis.MediaTransferUtils;
import cn.wildfirechat.pojos.mesh.MeshRestResult;
import cn.wildfirechat.sdk.utilities.JsonUtils;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InServiceTransferFileTest {

    private InService inService;
    private MediaUrlMappingService mediaUrlMappingService;

    @BeforeEach
    void setUp() throws Exception {
        inService = new InService();
        mediaUrlMappingService = mock(MediaUrlMappingService.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("media.bucket_image_name")).thenReturn("image-bucket");
        when(environment.getProperty("media.bucket_image_domain")).thenReturn("https://b-media/");
        inService.mediaUrlMappingService = mediaUrlMappingService;
        inService.environment = environment;
        inService.minioClient = mock(MinioClient.class);
        inService.fileTransferMaxSizeMb = 100;
    }

    @Test
    void savesReverseMappingWhenSourceUrlProvided() throws Exception {
        String sourceUrl = "https://a-media.example.com/x.png?sign=abc&t=1";
        String header = MediaTransferUtils.encodeSourceUrl(sourceUrl);

        String uploadedUrl = transfer("A", header);

        assertTrue(uploadedUrl.startsWith("https://b-media/"));
        verify(mediaUrlMappingService).saveMapping("A", uploadedUrl, sourceUrl);
    }

    @Test
    void skipsMappingWhenSourceUrlMissing() throws Exception {
        transfer("A", null);

        verify(mediaUrlMappingService, never()).saveMapping(anyString(), anyString(), anyString());
    }

    @Test
    void skipsMappingWhenSourceUrlIsNotHttp() throws Exception {
        String header = java.net.URLEncoder.encode("ftp://a/x.png", StandardCharsets.UTF_8);

        transfer("A", header);

        verify(mediaUrlMappingService, never()).saveMapping(anyString(), anyString(), anyString());
    }

    @Test
    void keepsTransferSuccessWhenMappingFails() throws Exception {
        doThrow(new IllegalStateException("db down"))
            .when(mediaUrlMappingService).saveMapping(anyString(), anyString(), anyString());

        String uploadedUrl = transfer("A", MediaTransferUtils.encodeSourceUrl("https://a/x.png"));

        //映射只是优化，保存失败不能影响文件传输结果
        assertNotNull(uploadedUrl);
    }

    private String transfer(String domainId, String sourceMediaUrlHeader) throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Object result = inService.onTransferFileRequest(domainId, new ByteArrayInputStream(content), ".png",
            "image/png", 1, content.length, sourceMediaUrlHeader);
        MeshRestResult<String> meshRestResult = JsonUtils.fromJsonObject2(result.toString(), String.class);
        assertEquals(0, meshRestResult.getLocal_mesh_code(), result.toString());
        assertEquals(0, meshRestResult.getRemote_mesh_code(), result.toString());
        assertEquals(0, meshRestResult.getRemote_im_code(), result.toString());
        assertNotNull(meshRestResult.getResult());
        return meshRestResult.getResult();
    }
}
