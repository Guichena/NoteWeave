package com.noteweave.personal.compiler.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.service.SourceStorageSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvidenceBacktraceService {

    private final SourceStorageSupport sourceStorageSupport;

    public SourceTextSnapshot loadReadableText(Source source) {
        if (source.getParsedTextObjectKey() != null && sourceStorageSupport.objectExists(source.getParsedTextObjectKey())) {
            return new SourceTextSnapshot(
                    sourceStorageSupport.readTextObject(source.getParsedTextObjectKey()),
                    source.getParsedTextObjectKey()
            );
        }
        if (source.getRawTextObjectKey() != null && sourceStorageSupport.objectExists(source.getRawTextObjectKey())) {
            return new SourceTextSnapshot(
                    sourceStorageSupport.readTextObject(source.getRawTextObjectKey()),
                    source.getRawTextObjectKey()
            );
        }
        throw new BusinessException(ErrorCode.SOURCE_NOT_READY, "Source has no readable raw or parsed text");
    }

    public EvidenceBacktrace backtrace(Source source, String quote) {
        SourceTextSnapshot snapshot = loadReadableText(source);
        String normalizedQuote = quote == null ? "" : quote.trim();
        if (normalizedQuote.isEmpty()) {
            throw new BusinessException(ErrorCode.EVIDENCE_BACKTRACE_FAILED, "Evidence quote is empty");
        }
        int start = snapshot.text().indexOf(normalizedQuote);
        if (start < 0) {
            return new EvidenceBacktrace(false, null, null, snapshot.objectKey());
        }
        return new EvidenceBacktrace(true, start, start + normalizedQuote.length(), snapshot.objectKey());
    }

    public boolean quoteExistsInSource(Source source, String quote) {
        return backtrace(source, quote).exists();
    }

    public record SourceTextSnapshot(String text, String objectKey) {
    }

    public record EvidenceBacktrace(boolean exists, Integer startOffset, Integer endOffset, String sourceVersion) {
    }
}
