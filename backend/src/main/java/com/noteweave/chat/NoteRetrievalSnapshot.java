package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.NoteEntryMetadata;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.List;

public record NoteRetrievalSnapshot(
        NoteRecallPlan recallPlan,
        List<NoteEntryMetadata> metadataEntries,
        List<ReadingWindow> windows
) {
    public NoteRetrievalSnapshot {
        metadataEntries = metadataEntries == null ? List.of() : List.copyOf(metadataEntries);
        windows = windows == null ? List.of() : List.copyOf(windows);
    }
}
