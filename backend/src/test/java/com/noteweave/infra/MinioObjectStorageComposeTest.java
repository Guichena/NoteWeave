package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import io.minio.ComposeObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MinioObjectStorageComposeTest {
    private final MinioClient client = mock(MinioClient.class);
    private final MinioObjectStorage storage;

    MinioObjectStorageComposeTest() {
        var config = new NoteWeaveProperties.Minio("http://localhost:9000", "fixture", "fixture-secret", "us-east-1", "source", "derived", "export");
        storage = new MinioObjectStorage(new NoteWeaveProperties(
                new NoteWeaveProperties.Storage("minio", Path.of("target/unused"), config), null, null, null, null, null));
        ReflectionTestUtils.setField(storage, "client", client);
    }

    @Test
    void largeNonFinalPartUsesServerSideComposeWithoutDownloadingContent() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenAnswer(call -> {
            var response = mock(StatObjectResponse.class);
            when(response.size()).thenReturn("part-0".equals(((StatObjectArgs) call.getArgument(0)).object()) ? 6L * 1024 * 1024 : 3L);
            return response;
        });
        storage.compose("source", List.of("part-0", "part-1"), "source", "merged");
        verify(client).composeObject(any(ComposeObjectArgs.class));
        verify(client, never()).getObject(any(GetObjectArgs.class));
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void oldSmallPartsAreReadInOrderThroughOneOpenStreamAtATime() throws Exception {
        Map<String, byte[]> bytes = Map.of("part-0", new byte[]{'a', 'b'}, "part-1", new byte[]{'c'});
        AtomicInteger activeStreams = new AtomicInteger();
        AtomicInteger openedStreams = new AtomicInteger();
        when(client.statObject(any(StatObjectArgs.class))).thenAnswer(call -> {
            var response = mock(StatObjectResponse.class);
            when(response.size()).thenReturn((long) bytes.get(((StatObjectArgs) call.getArgument(0)).object()).length);
            return response;
        });
        when(client.getObject(any(GetObjectArgs.class))).thenAnswer(call -> {
            assertThat(activeStreams.incrementAndGet()).isEqualTo(1);
            openedStreams.incrementAndGet();
            var response = mock(GetObjectResponse.class);
            var input = new ByteArrayInputStream(bytes.get(((GetObjectArgs) call.getArgument(0)).object()));
            when(response.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(read -> input.read(read.getArgument(0), read.getArgument(1), read.getArgument(2)));
            doAnswer(close -> { activeStreams.decrementAndGet(); return null; }).when(response).close();
            return response;
        });
        doAnswer(call -> {
            assertThat(((PutObjectArgs) call.getArgument(0)).stream().readNBytes(3)).containsExactly((byte) 'a', (byte) 'b', (byte) 'c');
            return null;
        }).when(client).putObject(any(PutObjectArgs.class));
        storage.compose("source", List.of("part-0", "part-1"), "source", "merged");
        assertThat(openedStreams.get()).isEqualTo(2);
        assertThat(activeStreams.get()).isZero();
        verify(client, never()).composeObject(any(ComposeObjectArgs.class));
        verify(client).putObject(any(PutObjectArgs.class));
    }

    @Test
    void composeRejectsEmptySourcesAndInputOverwrite() {
        assertThatThrownBy(() -> storage.compose("source", List.of(), "source", "merged")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.compose("source", List.of("merged"), "source", "merged")).isInstanceOf(IllegalArgumentException.class);
    }
}
