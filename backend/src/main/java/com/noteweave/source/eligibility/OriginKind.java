package com.noteweave.source.eligibility;

/**
 * 资格评估时来源的持久化类别。
 *
 * <p>类别只能依据可信持久记录判定：source 行存在且 generated_by 非空即为生成来源，
 * source 行存在且无生成方即为内部来源。查询不到 source 行不能推断为外部归档，
 * 否则内部 ID 缺失会被当成外部证据放行。</p>
 */
public enum OriginKind {
    /** 工作台内上传或写入的普通资料。 */
    INTERNAL_SOURCE,
    /** 经过采集器校验的外部归档身份，只允许携带可信归档引用。 */
    EXTERNAL_ARCHIVE,
    /** 由系统生成（如 Research 报告、Artifact 另存）的来源，依赖需递归解析。 */
    GENERATED_SOURCE
}
