package com.noteweave.retrieval.provider;

import java.util.function.Supplier;

/**
 * 标记当前线程正在为交互式回答（问答、精读、Wiki）做检索准备。
 * <p>
 * 重排是可选的排序增强：重排服务卡住时，交互式回答按配置的重试次数逐次等满超时
 * （线上配置 20 秒 × 3 次，精读两次重排共等了 121 秒）不值得。带这个标记时重排客户端
 * 按交互预算限时、超时后短暂跳过重排，走已有的重排不可用降级；研究、产物、索引等后台调用
 * 不带标记，行为不变。
 */
public final class InteractiveRerankBudget {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private InteractiveRerankBudget() {
    }

    public static <T> T run(Supplier<T> work) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** 并行检索分支在其他线程执行：提交前捕获标记，在工作线程里恢复。 */
    public static <T> Supplier<T> propagate(Supplier<T> work) {
        return active() ? () -> run(work) : work;
    }
}
