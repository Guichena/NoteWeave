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
        @JsonProperty("ocr_confidence") Double ocrConfidence
) {
    public DocumentElement {
        validateTextSource(textOrigin, ocrConfidence);
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
