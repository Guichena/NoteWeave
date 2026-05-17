package com.noteweave.embedding.service;

import java.util.List;

public interface EmbeddingClient {

    List<float[]> embedTexts(List<String> texts);
}
