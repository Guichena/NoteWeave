package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 解析器输出的原始结构元素，页码从 1 开始，坐标原点按解析结果保存。 */
public record DocumentElement(
        @JsonProperty("element_type") ElementType elementType,
        String text,
        Integer page,
        BoundingBox bbox,
        @JsonProperty("heading_level") Integer headingLevel
) {
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
