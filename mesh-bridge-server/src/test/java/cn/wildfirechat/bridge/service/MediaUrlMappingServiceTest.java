package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.MediaUrlMapping;
import cn.wildfirechat.bridge.jpa.MediaUrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
        //写入一次插入完成，之后两个方向的查询都走缓存，不再查库
        verify(repository, times(1)).save(argThat((MediaUrlMapping m) -> "B".equals(m.domainId)
            && "https://a/x.png".equals(m.localMediaUrl) && "https://b/x.png".equals(m.remoteMediaUrl)
            && m.localUrlHash != null && m.remoteUrlHash != null && m.createdAt != null));
        verify(repository, never()).findByDomainIdAndLocalUrlHash(anyString(), anyString());
        verify(repository, never()).findByDomainIdAndRemoteUrlHash(anyString(), anyString());
    }

    @Test
    void duplicateMappingIsTreatedAsExisting() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        when(repository.save(any(MediaUrlMapping.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        service.saveMapping("B", "https://a/x.png", "https://b/x.png");

        //唯一键冲突说明映射已经存在，照常写缓存，不再查库
        assertEquals(Optional.of("https://b/x.png"), service.findRemoteMediaUrl("B", "https://a/x.png"));
        assertEquals(Optional.of("https://a/x.png"), service.findLocalMediaUrl("B", "https://b/x.png"));
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

        verify(repository, never()).save(any(MediaUrlMapping.class));
    }

    @Test
    void prefetchLoadsMappingsIntoCache() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        MediaUrlMapping mapping = new MediaUrlMapping();
        mapping.domainId = "B";
        mapping.localMediaUrl = "https://a/1.png";
        mapping.remoteMediaUrl = "https://b/1.png";
        when(repository.findByDomainIdAndLocalUrlHashIn(eq("B"), anyCollection()))
            .thenReturn(Collections.singletonList(mapping));

        service.prefetchRemoteMediaUrls("B", Arrays.asList("https://a/1.png", "https://a/2.png"));

        //命中预取的走缓存，未命中的走负缓存，都不再单独查库
        assertEquals(Optional.of("https://b/1.png"), service.findRemoteMediaUrl("B", "https://a/1.png"));
        assertEquals(Optional.empty(), service.findRemoteMediaUrl("B", "https://a/2.png"));
        verify(repository, never()).findByDomainIdAndLocalUrlHash(anyString(), anyString());
        verify(repository, times(1)).findByDomainIdAndLocalUrlHashIn(eq("B"), anyCollection());
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
        verify(repository, never()).findExpiredIds(anyLong(), any(Pageable.class));

        service.retentionDays = 180;
        service.cleanupEnabled = false;
        service.cleanupExpiredMappings();
        verify(repository, never()).findExpiredIds(anyLong(), any(Pageable.class));

        service.cleanupEnabled = true;
        when(repository.findExpiredIds(anyLong(), any(Pageable.class))).thenReturn(Arrays.asList(1L, 2L, 3L));
        when(repository.deleteByIds(anyCollection())).thenReturn(3);
        service.cleanupExpiredMappings();
        verify(repository, times(1)).findExpiredIds(anyLong(), any(Pageable.class));
        verify(repository, times(1)).deleteByIds(Arrays.asList(1L, 2L, 3L));
    }

    @Test
    void cleanupDeletesInBatchesUntilDone() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        service.retentionDays = 180;
        service.cleanupEnabled = true;
        service.cleanupBatchSize = 1000;
        when(repository.findExpiredIds(anyLong(), any(Pageable.class))).thenReturn(ids(1, 1000), ids(1001, 200));
        when(repository.deleteByIds(anyCollection())).thenReturn(1000, 200);

        service.cleanupExpiredMappings();

        verify(repository, times(2)).findExpiredIds(anyLong(), argThat((Pageable p) -> p.getPageSize() == 1000));
        verify(repository, times(2)).deleteByIds(anyCollection());
    }

    @Test
    void cleanupStopsWhenNothingExpired() {
        MediaUrlMappingRepository repository = mock(MediaUrlMappingRepository.class);
        MediaUrlMappingService service = new MediaUrlMappingService(repository);
        service.retentionDays = 180;
        service.cleanupEnabled = true;
        service.cleanupBatchSize = 1000;
        when(repository.findExpiredIds(anyLong(), any(Pageable.class))).thenReturn(Collections.emptyList());

        service.cleanupExpiredMappings();

        verify(repository, times(1)).findExpiredIds(anyLong(), any(Pageable.class));
        verify(repository, never()).deleteByIds(anyCollection());
    }

    private static List<Long> ids(long start, int count) {
        return LongStream.range(start, start + count).boxed().collect(Collectors.toList());
    }
}
