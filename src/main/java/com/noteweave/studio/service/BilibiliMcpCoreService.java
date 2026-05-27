package com.noteweave.studio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.source.fetch.UrlContentFetcher;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class BilibiliMcpCoreService {

    private static final DateTimeFormatter PUBLISH_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final UrlContentFetcher urlContentFetcher;
    private final ObjectMapper objectMapper;

    public BilibiliMcpCoreService(UrlContentFetcher urlContentFetcher, ObjectMapper objectMapper) {
        this.urlContentFetcher = urlContentFetcher;
        this.objectMapper = objectMapper;
    }

    public BilibiliMcpContext fetch(String url) {
        String normalizedUrl = normalizeUrl(url);
        urlContentFetcher.validate(normalizedUrl);
        ensureSupported(normalizedUrl);

        String html = new String(urlContentFetcher.fetch(normalizedUrl).body(), StandardCharsets.UTF_8);
        JsonNode initialState = extractAssignedJson(html, "window.__INITIAL_STATE__=");
        if (initialState == null) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Bilibili MCP tool could not parse page metadata");
        }
        JsonNode videoData = initialState.path("videoData");
        if (!videoData.isObject()) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Bilibili MCP tool could not load video data");
        }

        String title = text(videoData.path("title"));
        String bvid = text(videoData.path("bvid"));
        Long cid = longValue(videoData.path("cid"));
        if (cid == null) {
            cid = firstPageCid(videoData.path("pages"));
        }

        SubtitleResult subtitles = loadSubtitles(bvid, cid);
        String promptContext = buildPromptContext(normalizedUrl, videoData, subtitles);
        return new BilibiliMcpContext(
                normalizedUrl,
                title == null ? "Bilibili Video" : title,
                promptContext,
                subtitles.lineCount(),
                videoData.path("pages").isArray() ? videoData.path("pages").size() : 0
        );
    }

    private String buildPromptContext(String url, JsonNode videoData, SubtitleResult subtitles) {
        StringBuilder builder = new StringBuilder();
        builder.append("Bilibili MCP Tool Result").append('\n');
        builder.append("Video URL: ").append(url).append('\n');
        appendField(builder, "Title", text(videoData.path("title")));
        appendField(builder, "Uploader", text(videoData.path("owner").path("name")));
        appendField(builder, "BVID", text(videoData.path("bvid")));
        appendField(builder, "Published At", resolvePublishTime(videoData.path("pubdate")));

        String description = text(videoData.path("desc"));
        if (description != null) {
            builder.append('\n').append("Description:").append('\n');
            builder.append(description).append('\n');
        }

        JsonNode pagesNode = videoData.path("pages");
        if (pagesNode.isArray() && !pagesNode.isEmpty()) {
            builder.append('\n').append("Pages:").append('\n');
            int index = 1;
            for (JsonNode pageNode : pagesNode) {
                builder.append(index++)
                        .append(". ")
                        .append(text(pageNode.path("part")) == null ? "P" + (index - 1) : text(pageNode.path("part")))
                        .append('\n');
            }
        }

        builder.append('\n').append("Subtitles:").append('\n');
        if (subtitles.trackLabel() != null) {
            builder.append("Selected Track: ").append(subtitles.trackLabel()).append('\n');
        }
        if (subtitles.lines().isEmpty()) {
            builder.append("No CC subtitles available.").append('\n');
        } else {
            subtitles.lines().forEach(line -> builder.append(line).append('\n'));
        }
        builder.append('\n').append("Constraint: Use metadata and CC subtitles only. Ignore danmaku.");
        return builder.toString();
    }

    private SubtitleResult loadSubtitles(String bvid, Long cid) {
        if (bvid == null || cid == null) {
            return new SubtitleResult(null, List.of());
        }
        try {
            String apiUrl = "https://api.bilibili.com/x/player/v2?bvid="
                    + URLEncoder.encode(bvid, StandardCharsets.UTF_8)
                    + "&cid="
                    + cid;
            JsonNode subtitleInfo = objectMapper.readTree(new String(urlContentFetcher.fetch(apiUrl).body(), StandardCharsets.UTF_8));
            JsonNode tracksNode = subtitleInfo.path("data").path("subtitle").path("subtitles");
            if (!tracksNode.isArray() || tracksNode.isEmpty()) {
                return new SubtitleResult(null, List.of());
            }
            SubtitleTrack selectedTrack = tracksNodeToTracks(tracksNode).stream()
                    .min(Comparator
                            .comparingInt((SubtitleTrack track) -> languagePriority(track.language()))
                            .thenComparing(SubtitleTrack::label))
                    .orElse(null);
            if (selectedTrack == null) {
                return new SubtitleResult(null, List.of());
            }
            JsonNode subtitleBody = objectMapper.readTree(new String(
                    urlContentFetcher.fetch(normalizeSubtitleUrl(selectedTrack.url())).body(),
                    StandardCharsets.UTF_8
            ));
            List<String> lines = new ArrayList<>();
            JsonNode bodyNode = subtitleBody.path("body");
            if (bodyNode.isArray()) {
                for (JsonNode lineNode : bodyNode) {
                    String content = text(lineNode.path("content"));
                    if (content == null) {
                        continue;
                    }
                    lines.add("[" + formatTime(lineNode.path("from").asDouble(0D)) + "] " + content);
                }
            }
            return new SubtitleResult(selectedTrack.label(), lines);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Bilibili MCP tool failed to load subtitles: " + safeMessage(ex));
        }
    }

    private List<SubtitleTrack> tracksNodeToTracks(JsonNode tracksNode) {
        List<SubtitleTrack> tracks = new ArrayList<>();
        for (JsonNode node : tracksNode) {
            String url = text(node.path("subtitle_url"));
            if (url == null) {
                continue;
            }
            String lan = text(node.path("lan"));
            String label = text(node.path("lan_doc"));
            tracks.add(new SubtitleTrack(
                    url,
                    lan == null ? "" : lan,
                    label == null ? (lan == null ? "unknown" : lan) : label
            ));
        }
        return tracks;
    }

    private int languagePriority(String language) {
        String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zh-hans", "zh-cn", "zh", "ai-zh" -> 0;
            case "zh-hant", "zh-tw" -> 1;
            case "en", "en-us" -> 2;
            default -> 10;
        };
    }

    private void ensureSupported(String url) {
        String host = URI.create(url).getHost();
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!(normalized.endsWith("bilibili.com") || normalized.equals("b23.tv") || normalized.endsWith(".b23.tv"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bilibiliUrl must be a bilibili.com or b23.tv link");
        }
    }

    private String normalizeUrl(String url) {
        try {
            URI parsed = URI.create(url == null ? "" : url.trim());
            String scheme = parsed.getScheme() == null ? "https" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null ? null : parsed.getHost().toLowerCase(Locale.ROOT);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            return new URI(scheme, parsed.getUserInfo(), host, parsed.getPort(), path, parsed.getQuery(), null)
                    .normalize()
                    .toString();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bilibiliUrl: invalid url");
        }
    }

    private String normalizeSubtitleUrl(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return "https://api.bilibili.com" + url;
        }
        return url;
    }

    private JsonNode extractAssignedJson(String html, String marker) {
        int markerIndex = html.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int start = html.indexOf('{', markerIndex + marker.length());
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < html.length(); index++) {
            char ch = html.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                depth++;
                continue;
            }
            if (ch == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return objectMapper.readTree(html.substring(start, index + 1));
                    } catch (Exception ex) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private Long firstPageCid(JsonNode pagesNode) {
        if (!pagesNode.isArray() || pagesNode.isEmpty()) {
            return null;
        }
        return longValue(pagesNode.get(0).path("cid"));
    }

    private void appendField(StringBuilder builder, String label, String value) {
        if (value != null) {
            builder.append(label).append(": ").append(value).append('\n');
        }
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String resolvePublishTime(JsonNode node) {
        Long epochSecond = longValue(node);
        if (epochSecond == null || epochSecond <= 0) {
            return null;
        }
        return PUBLISH_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSecond));
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String formatTime(double seconds) {
        int rounded = Math.max((int) Math.floor(seconds), 0);
        int hours = rounded / 3600;
        int minutes = (rounded % 3600) / 60;
        int secs = rounded % 60;
        if (hours > 0) {
            return "%02d:%02d:%02d".formatted(hours, minutes, secs);
        }
        return "%02d:%02d".formatted(minutes, secs);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private record SubtitleTrack(String url, String language, String label) {
    }

    private record SubtitleResult(String trackLabel, List<String> lines) {
        private int lineCount() {
            return lines == null ? 0 : lines.size();
        }
    }

    public record BilibiliMcpContext(
            String url,
            String title,
            String promptContext,
            int subtitleLineCount,
            int pageCount
    ) {
    }
}
