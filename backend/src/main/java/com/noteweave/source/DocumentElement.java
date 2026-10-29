package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 解析器输出的原始结构元素，页码从 1 开始，坐标原点按解析结果保存。 */
public record DocumentElement(
        @JsonProperty("element_type") ElementType elementType,
        String text,
        Integer page,
        BoundingBox bbox,
        @JsonProperty("heading_level") Integer headingLevel,
        @JsonProperty("text_origin") TextOrigin textOrigin,
        @JsonProperty("ocr_confidence") Double ocrConfidence,
        Integer part,
        @JsonProperty("start_ms") Long startMs,
        @JsonProperty("end_ms") Long endMs,
        @JsonProperty("frame_ids") java.util.List<String> frameIds,
        @JsonProperty("source_url") String sourceUrl
) {
    public DocumentElement {
        validateTextSource(textOrigin, ocrConfidence);
        frameIds = frameIds == null ? java.util.List.of() : java.util.List.copyOf(frameIds);
        if (part != null && (part < 1 || part > 1000 || startMs == null || endMs == null || startMs < 0 || endMs <= startMs)
                || part == null && (startMs != null || endMs != null || !frameIds.isEmpty())) {
            throw new IllegalArgumentException("音视频元素的分 P、时间范围或帧 ID 无效");
        }
    }

    public DocumentElement(ElementType type, String text, Integer page, BoundingBox bbox, Integer level,
                           TextOrigin origin, Double confidence) {
        this(type, text, page, bbox, level, origin, confidence, null, null, null, java.util.List.of(), null);
    }

    public DocumentElement(ElementType elementType, String text, Integer page, BoundingBox bbox, Integer headingLevel) {
        this(elementType, text, page, bbox, headingLevel, null, null);
    }

    public enum TextOrigin { TEXT_LAYER, OCR }

    static void validateTextSource(TextOrigin origin, Double confidence) {
        if (confidence != null && (origin != TextOrigin.OCR || !Double.isFinite(confidence)
                || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("识别置信度只能用于 OCR 来源，并且必须是 0 到 1 的有限数值");
        }
    }

    public enum ElementType {
        TITLE, SECTION_HEADER, TEXT, TABLE, CAPTION, FORMULA, CODE, LIST_ITEM, FOOTNOTE, PICTURE
    }

    public enum CoordOrigin { TOPLEFT, BOTTOMLEFT }

    public record BoundingBox(double left, double top, double right, double bottom,
                              @JsonProperty("coord_origin") CoordOrigin coordOrigin) {}

    public boolean indivisible() {
        return elementType == ElementType.TABLE || elementType == ElementType.CAPTION
                || elementType == ElementType.FORMULA;
    }
}
