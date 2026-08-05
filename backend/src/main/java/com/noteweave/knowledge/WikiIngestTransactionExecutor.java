package com.noteweave.knowledge;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiIngestTransactionExecutor {

    @Transactional
    public <T> T execute(Supplier<T> operation) {
        return operation.get();
    }
}
