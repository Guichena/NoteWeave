package com.noteweave.chat;

import jakarta.validation.constraints.Size;

public record NoteSourceDraftRequest(
        @Size(max = 300) String title
) {
}
