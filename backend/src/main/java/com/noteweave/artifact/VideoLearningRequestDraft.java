package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Immutable parent selection and acquisition policy, before any child Job exists. */
public record VideoLearningRequestDraft(
        String videoUrl,
        int part,
        String language,
        String frameDensity,
        String asrFallback,
        String templateVersion,
        String userRequirement,
        List<String> selectedSkills
) {
    private static final Set<String> SKILLS = Set.of("knowledge_blog", "interview_qa",
            "video_learning_deck", "bilibili_course_note_pdf");
    public static List<String> supportedSkills() {
        return SKILLS.stream().sorted().toList();
    }
    private static final Pattern BVID_PATH = Pattern.compile("/video/BV[0-9A-Za-z]{10}/?");

    public VideoLearningRequestDraft {
        if (videoUrl == null || videoUrl.length() > 500 || part < 1 || part > 1000
                || language == null || !Set.of("zh-CN", "en", "zh-EN").contains(language)
                || frameDensity == null || !Set.of("LOW", "STANDARD", "HIGH").contains(frameDensity)
                || asrFallback == null || !Set.of("ALLOW", "DENY").contains(asrFallback)
                || !"original-v1".equals(templateVersion)
                || userRequirement == null || userRequirement.isBlank()
                || userRequirement.length() > 4000 || selectedSkills == null
                || selectedSkills.isEmpty() || selectedSkills.size() > 4
                || selectedSkills.stream().anyMatch(skill -> skill == null || !SKILLS.contains(skill))
                || selectedSkills.size() != Set.copyOf(selectedSkills).size()) {
            throw invalid();
        }
        videoUrl = normalizeUrl(videoUrl, part);
        userRequirement = userRequirement.trim();
        selectedSkills = selectedSkills.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public String digest() {
        StringBuilder canonical = new StringBuilder();
        for (String value : List.of(videoUrl, String.valueOf(part), language, frameDensity,
                asrFallback, templateVersion, userRequirement)) {
            append(canonical, value);
        }
        selectedSkills.forEach(skill -> append(canonical, skill));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String normalizeUrl(String url, int part) {
        try {
            URI parsed = URI.create(url.trim());
            if (!Set.of("http", "https").contains(parsed.getScheme())
                    || !Set.of("bilibili.com", "www.bilibili.com").contains(parsed.getHost())
                    || !BVID_PATH.matcher(parsed.getPath()).matches()
                    || parsed.getRawFragment() != null || parsed.getUserInfo() != null) throw invalid();
            String query = parsed.getRawQuery() == null ? "" : parsed.getRawQuery();
            Matcher explicitPart = Pattern.compile("(?:^|&)p=([^&]*)").matcher(query);
            if (explicitPart.find()) {
                if (!String.valueOf(part).equals(explicitPart.group(1)) || explicitPart.find()) throw invalid();
            } else if (part > 1) {
                query = query.isEmpty() ? "p=" + part : query + "&p=" + part;
            }
            String path = parsed.getPath().endsWith("/")
                    ? parsed.getPath().substring(0, parsed.getPath().length() - 1)
                    : parsed.getPath();
            return "https://www.bilibili.com" + path + (query.isEmpty() ? "" : "?" + query);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException("VIDEO_LEARNING_REQUEST_INVALID",
                "视频学习请求的链接、策略或产物选择无效", HttpStatus.BAD_REQUEST);
    }
}
