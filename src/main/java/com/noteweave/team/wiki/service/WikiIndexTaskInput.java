package com.noteweave.team.wiki.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WikiIndexTaskInput {
    private Long wikiPageId;
    private Long publishedVersionId;
    private Long spaceId;
}
