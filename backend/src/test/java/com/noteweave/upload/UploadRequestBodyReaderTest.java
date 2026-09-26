package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class UploadRequestBodyReaderTest {

    @Test
    void rejectsOversizedContentLengthBeforeOpeningRequestBody() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn((long) UploadSecurityPolicy.MAX_CHUNK_SIZE + 1);

        assertThatThrownBy(() -> UploadRequestBodyReader.read(request))
                .hasMessageContaining("允许大小")
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo("UPLOAD_CHUNK_TOO_LARGE"));
        verify(request, never()).getInputStream();
    }

    @Test
    void readsBodyWhenItIsWithinTheBound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(UploadRequestBodyReader.read(request)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }
}
