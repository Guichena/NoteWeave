package com.noteweave.source.eligibility;

/**
 * 待评估的来源引用。
 *
 * <p>内部来源与生成来源必须携带 sourceId；外部归档必须携带归档身份 archiveRef，
 * 不允许把内部 sourceId 标注为外部归档来绕过内部校验。</p>
 */
public record OriginRef(
        OriginKind kind,
        String sourceId,
        String snapshotId,
        String archiveRef
) {
    public OriginRef {
        kind = kind == null ? OriginKind.INTERNAL_SOURCE : kind;
        sourceId = normalize(sourceId);
        snapshotId = normalize(snapshotId);
        archiveRef = normalize(archiveRef);
    }

    /** 内部来源引用，可选携带快照。 */
    public static OriginRef internalSource(String sourceId) {
        return new OriginRef(OriginKind.INTERNAL_SOURCE, sourceId, null, null);
    }

    /** 内部来源的冻结快照引用。 */
    public static OriginRef internalSnapshot(String sourceId, String snapshotId) {
        return new OriginRef(OriginKind.INTERNAL_SOURCE, sourceId, snapshotId, null);
    }

    /** 生成来源引用（如 Research 报告来源）。 */
    public static OriginRef generatedSource(String sourceId) {
        return new OriginRef(OriginKind.GENERATED_SOURCE, sourceId, null, null);
    }

    /** 外部归档引用，归档身份来自可信采集记录。 */
    public static OriginRef externalArchive(String archiveRef) {
        return new OriginRef(OriginKind.EXTERNAL_ARCHIVE, null, null, archiveRef);
    }

    /** 评估结果的主键：内部与生成来源按 sourceId，外部归档按归档身份。 */
    public String originKey() {
        if (kind == OriginKind.EXTERNAL_ARCHIVE) {
            return "archive:" + archiveRef;
        }
        return "source:" + sourceId;
    }

    public boolean isValid() {
        if (kind == OriginKind.EXTERNAL_ARCHIVE) {
            return archiveRef != null;
        }
        return sourceId != null;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
