package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** media-mcp 的图文文档；入库后 image_ref 替换为工作台内的图片读取地址。 */
public record SourceMediaDocument(
        String title,
        @JsonProperty("source_url") String sourceUrl,
        List<Segment> segments,
        Stats stats
) {
    public SourceMediaDocument {
        if (title == null || title.length() > 300 || sourceUrl == null || sourceUrl.length() > 1024
                || segments == null || segments.isEmpty() || segments.size() > 200_000 || stats == null) {
            throw new IllegalArgumentException("音视频图文文档的标题、来源、片段或统计无效");
        }
        segments = List.copyOf(segments);
        long characters = segments.stream().mapToLong(segment -> segment.text().length()
                + segment.frames().stream().mapToLong(frame -> frame.caption().length() + frame.ocrText().length()).sum()).sum();
        long pictures = segments.stream().mapToLong(segment -> segment.frames().size()).sum();
        long imageCharacters = segments.stream().flatMap(segment -> segment.frames().stream()).mapToLong(frame -> frame.imageRef().length()).sum();
        long distinctFrames = segments.stream().flatMap(segment -> segment.frames().stream()).map(Frame::frameId).distinct().count();
        if (characters > 20_000_000 || pictures > 512 || imageCharacters > 64_000_000
                || pictures != distinctFrames || pictures != stats.keptFrames()) {
            throw new IllegalArgumentException("图文文档正文或关键帧超过上限，或关键帧统计不一致");
        }
    }

    public record Segment(int part, @JsonProperty("start_ms") long startMs,
                          @JsonProperty("end_ms") long endMs, String text, List<Frame> frames) {
        public Segment {
            if (part < 1 || part > 1000 || startMs < 0 || endMs <= startMs || endMs > 86_400_000
                    || text == null || frames == null) throw new IllegalArgumentException("音视频片段无效");
            frames = List.copyOf(frames);
            for (Frame frame : frames) {
                if (frame.atMs() < startMs || frame.atMs() >= endMs) throw new IllegalArgumentException("关键帧不在片段的时间范围内");
            }
        }

        public String body() {
            StringBuilder body = new StringBuilder(text);
            for (Frame frame : frames) {
                if (!frame.caption().isBlank()) body.append("\n画面说明：").append(frame.caption());
                if (!frame.ocrText().isBlank()) body.append("\n画面文字：").append(frame.ocrText());
            }
            return body.isEmpty() ? "关键帧" : body.toString();
        }
    }

    public record Frame(@JsonProperty("frame_id") String frameId, @JsonProperty("at_ms") long atMs,
                        @JsonProperty("image_ref") String imageRef, String caption,
                        @JsonProperty("ocr_text") String ocrText) {
        public Frame {
            if (frameId == null || !frameId.matches("[A-Za-z0-9_-]{1,100}") || atMs < 0
                    || imageRef == null || imageRef.length() > 22_000_000 || caption == null || ocrText == null) {
                throw new IllegalArgumentException("关键帧信息无效");
            }
        }
    }

    public record Stats(@JsonProperty("candidate_frames") int candidateFrames,
                        @JsonProperty("kept_frames") int keptFrames,
                        @JsonProperty("transcript_origin") String transcriptOrigin,
                        @JsonProperty("duration_ms") long durationMs,
                        @JsonProperty("vision_status") String visionStatus) {
        public Stats {
            if (candidateFrames < 0 || keptFrames < 0 || keptFrames > candidateFrames || keptFrames > 512
                    || durationMs <= 0 || durationMs > 86_400_000
                    || !List.of("SUBTITLE", "ASR", "MIXED").contains(transcriptOrigin)
                    || !List.of("ENABLED", "NOT_CONFIGURED").contains(visionStatus)) {
                throw new IllegalArgumentException("音视频解析统计无效");
            }
        }
    }
}
