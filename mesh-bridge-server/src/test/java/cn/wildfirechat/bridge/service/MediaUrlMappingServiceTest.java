package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.MediaUrlMapping;
import cn.wildfirechat.bridge.jpa.MediaUrlMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUrlMappingServiceTest {

    @Test
    void cachesSavedMapping() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);

        service.saveMapping("B", "https://a/x.png", "https://b/x.png");

        assertEquals(Optional.of("https://b/x.png"), service.findRemoteMediaUrl("B", "https://a/x.png"));
        assertEquals(Optional.of("https://a/x.png"), service.findLocalMediaUrl("B", "https://b/x.png"));
        //写入用 upsert 一次完成，之后两个方向的查询都走缓存，不再查库
        verify(repository, times(1)).insertIgnore(eq("B"), anyString(), eq("https://a/x.png"),
            anyString(), eq("https://b/x.png"), anyLong());
        verify(repository, never()).findByDomainIdAndLocalUrlHash(anyString(), anyString());
        verify(repository, never()).findByDomainIdAndRemoteUrlHash(anyString(), anyString());
    }

    @Test
    void ignoresEmptyUrl() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);

        assertEquals(Optional.empty(), service.findRemoteMediaUrl("B", null));
        assertEquals(Optional.empty(), service.findLocalMediaUrl(null, "https://b/x.png"));
        service.saveMapping("B", "", "https://b/x.png");

        verify(repository, never()).insertIgnore(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void prefetchLoadsMappingsIntoCache() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        MediaUrlMapping mapping = new MediaUrlMapping();
        mapping.domainId = "B";
        mapping.localMediaUrl = "https://a/1.png";
        mapping.remoteMediaUrl = "https://b/1.png";
        when(repository.findByDomainIdAndLocalUrlHashIn(eq("B"), org.mockito.ArgumentMatchers.anyCollection()))
            .thenReturn(Collections.singletonList(mapping));

        service.prefetchRemoteMediaUrls("B", Arrays.asList("https://a/1.png", "https://a/2.png"));

        //命中预取的走缓存，未命中的走负缓存，都不再单独查库
        assertEquals(Optional.of("https://b/1.png"), service.findRemoteMediaUrl("B", "https://a/1.png"));
        assertEquals(Optional.empty(), service.findRemoteMediaUrl("B", "https://a/2.png"));
        verify(repository, never()).findByDomainIdAndLocalUrlHash(anyString(), anyString());
        verify(repository, times(1)).findByDomainIdAndLocalUrlHashIn(eq("B"), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void negativeResultIsCachedForAWhile() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);

        assertEquals(Optional.empty(), service.findRemoteMediaUrl("B", "https://a/none.png"));
        assertEquals(Optional.empty(), service.findRemoteMediaUrl("B", "https://a/none.png"));

        //第二次走负缓存，不再查库
        verify(repository, times(1)).findByDomainIdAndLocalUrlHash(anyString(), anyString());
    }

    @Test
    void cleanupRespectsRetentionAndFlag() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        service.cleanupBatchSize = 1000;

        service.retentionDays = 0;
        service.cleanupExpiredMappings();
        verify(repository, never()).deleteExpired(anyLong(), anyInt());

        service.retentionDays = 180;
        service.cleanupEnabled = false;
        service.cleanupExpiredMappings();
        verify(repository, never()).deleteExpired(anyLong(), anyInt());

        service.cleanupEnabled = true;
        when(repository.deleteExpired(anyLong(), anyInt())).thenReturn(3);
        service.cleanupExpiredMappings();
        verify(repository, times(1)).deleteExpired(anyLong(), anyInt());
    }

    @Test
    void cleanupDeletesInBatchesUntilDone() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        service.retentionDays = 180;
        service.cleanupEnabled = true;
        service.cleanupBatchSize = 1000;
        when(repository.deleteExpired(anyLong(), anyInt())).thenReturn(1000, 200);

        service.cleanupExpiredMappings();

        verify(repository, times(2)).deleteExpired(anyLong(), eq(1000));
    }
}
