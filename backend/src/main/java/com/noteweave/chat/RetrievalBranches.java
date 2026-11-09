package com.noteweave.chat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 检索里互不依赖的分支（向量召回、关键词召回等）并行执行的小工具。
 * 没有线程池时在调用线程内顺序执行；分支抛出的异常原样交回调用方，
 * 让调用方保持和串行写法相同的降级与报错语义。
 */
final class RetrievalBranches {
    private RetrievalBranches() {
    }

    /** 已提交的分支。join 时把工作线程里的异常还原成原始异常抛出。 */
    static final class Branch<T> {
        private final CompletableFuture<T> future;
        private final T direct;
        private final RuntimeException directFailure;

        private Branch(CompletableFuture<T> future, T direct, RuntimeException directFailure) {
            this.future = future;
            this.direct = direct;
            this.directFailure = directFailure;
        }

        T join() {
            if (future == null) {
                if (directFailure != null) {
                    throw directFailure;
                }
                return direct;
            }
            try {
                return future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("retrieval branch interrupted", ex);
            } catch (ExecutionException ex) {
                throw unwrap(ex.getCause());
            }
        }
    }

    static <T> Branch<T> start(Executor executor, Supplier<T> work) {
        if (executor == null) {
            // 没有线程池时立即在当前线程执行，保持旧的串行顺序
            try {
                return new Branch<>(null, work.get(), null);
            } catch (RuntimeException ex) {
                return new Branch<>(null, null, ex);
            }
        }
        // 交互式重排预算标记跟着分支进入工作线程
        return new Branch<>(CompletableFuture.supplyAsync(
                com.noteweave.retrieval.provider.InteractiveRerankBudget.propagate(work), executor), null, null);
    }

    private static RuntimeException unwrap(Throwable cause) {
        Throwable current = cause;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            return runtime;
        }
        if (current instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(current);
    }
}
