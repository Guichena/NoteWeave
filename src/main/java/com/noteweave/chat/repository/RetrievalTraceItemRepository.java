package com.noteweave.chat.repository;

import com.noteweave.chat.model.RetrievalTraceItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetrievalTraceItemRepository extends JpaRepository<RetrievalTraceItem, Long> {

    List<RetrievalTraceItem> findByTraceIdOrderByRankNoAscIdAsc(Long traceId);
}
