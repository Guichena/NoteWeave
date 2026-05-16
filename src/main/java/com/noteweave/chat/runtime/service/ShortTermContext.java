package com.noteweave.chat.runtime.service;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortTermContext {

    private List<String> recentMessages;
    private List<String> evidenceTitles;
}
