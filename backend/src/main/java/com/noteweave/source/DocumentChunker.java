package com.noteweave.source;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.conversation.ContextTokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 结构感知的资料切片。
 * <p>
 * 先把文本解析成带章节路径、页码或时间戳的块（标题、段落、代码、表格、转写行、表格行），
 * 再在章节内按 Token 预算组装片段：段落放不下时按句子、再按分句递归切开，最后才硬切。
 * 片段之间的重叠只取上一片段末尾的完整句子，并且不跨章节；过小的章节会和后续内容合并，
 * 标题不会单独留在片段末尾。
 * <p>
 * 页码来自解析阶段写入的换页符 {@code \f}（PDF 每页之后一个），转写稿的时间来自每行开头的 [mm:ss]。
 */
@Component
public class DocumentChunker {

    /** 阅读窗口的 Token 上限，精读按窗口读取连续原文。 */
    static final int WINDOW_MAX_TOKENS = 220;
    /** 相邻窗口之间重叠的上限：上一窗口的最后一句不超过这个预算才带入下一窗口。 */
    static final int WINDOW_CARRY_TOKENS = 40;
    static final int HEADING_PATH_MAX_CHARS = 300;
    static final int LOCATION_MAX_CHARS = 300;
    private static final String PATH_SEPARATOR = " > ";

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern TIMESTAMP_LINE = Pattern.compile("^\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]");
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+[章篇部]\\s*\\S.*");
    private static final Pattern CHINESE_SECTION = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+节\\s*\\S.*");
    private static final Pattern CHINESE_ENUM = Pattern.compile("^[一二三四五六七八九十]+[、.．]\\s*\\S.*");
    private static final Pattern CHINESE_PAREN_ENUM = Pattern.compile("^[（(][一二三四五六七八九十]+[)）]\\s*\\S.*");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2}){0,3})[.、．]?\\s+\\S.*");
    private static final Pattern NAMED_SECTION = Pattern.compile(
            "^(摘要|引言|前言|背景|结论|总结|参考文献|附录|致谢|Abstract|Introduction|Background|Conclusions?|References|Appendix)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINAL_PUNCTUATION = Pattern.compile("[。！？；：，、.!?;:,]$");
    /** 句末切分点：中文句末标点之后，英文句号、问号、叹号后跟空白处，以及换行。 */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[。！？；!?;…])|(?<=[.!?])(?=\\s)|\\n");
    private static final Pattern CLAUSE_BREAK = Pattern.compile("(?<=[，、,：:])");

    private final int maxTokens;
    private final int overlapTokens;
    private final int minSectionTokens;

    @Autowired
    public DocumentChunker(NoteWeaveProperties properties) {
        this(properties.document().chunkMaxTokens(), properties.document().chunkOverlapTokens());
    }

    DocumentChunker(int maxTokens, int overlapTokens) {
        this.maxTokens = Math.max(64, maxTokens);
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.maxTokens / 4));
        this.minSectionTokens = Math.max(32, this.maxTokens / 8);
    }

    /** 片段：章节路径、正文、页码范围、时间范围和 Token 估算。页码与时间不可用时为 null。 */
    public record DocumentChunk(
            String headingPath,
            String content,
            Integer pageStart,
            Integer pageEnd,
            String timeStart,
            String timeEnd,
            int tokens
    ) {
        /** 人类可读的定位：页码、时间与章节，前缀保留 chunk 序号，长度受数据库列限制。 */
        public String location(int chunkNo) {
            StringBuilder builder = new StringBuilder("chunk:").append(chunkNo);
            appendDetails(builder);
            return truncate(builder.toString(), LOCATION_MAX_CHARS);
        }

        public String windowLocation(int chunkNo, int windowNo) {
            StringBuilder builder = new StringBuilder("chunk:").append(chunkNo).append("/window:").append(windowNo);
            appendDetails(builder);
            return truncate(builder.toString(), LOCATION_MAX_CHARS);
        }

        private void appendDetails(StringBuilder builder) {
            if (pageStart != null) {
                builder.append(" · 第 ").append(pageStart);
                if (pageEnd != null && !pageEnd.equals(pageStart)) {
                    builder.append("-").append(pageEnd);
                }
                builder.append(" 页");
            }
            if (timeStart != null) {
                builder.append(" · ").append(timeStart);
                if (timeEnd != null && !timeEnd.equals(timeStart)) {
                    builder.append("-").append(timeEnd);
                }
            }
            if (headingPath != null && !headingPath.isBlank()) {
                builder.append(" · ").append(headingPath);
            }
        }
    }

    private enum Kind { HEADING, PARAGRAPH, ATOMIC, LINE, ROW }

    private enum Mode { MARKDOWN, PLAIN, TRANSCRIPT, CSV }

    private record Block(Kind kind, String text, List<String> path, Integer page, String time, int tokens) {
    }

    private record Heading(int level, String title) {
    }

    public List<DocumentChunk> chunk(String text, String mimeType) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.replace("\f", "").isBlank()) {
            return List.of(new DocumentChunk("", "", null, null, null, null, 0));
        }
        Mode mode = detectMode(normalized, mimeType);
        List<Block> blocks = switch (mode) {
            case TRANSCRIPT -> transcriptBlocks(normalized);
            case CSV -> csvBlocks(normalized);
            default -> structuredBlocks(normalized, mode);
        };
        List<DocumentChunk> chunks = mode == Mode.CSV ? assembleCsv(blocks) : assemble(blocks, mode);
        return chunks.isEmpty() ? List.of(new DocumentChunk("", "", null, null, null, null, 0)) : chunks;
    }

    /** 把片段切成阅读窗口：按段落和句子边界组装，窗口之间重叠上一窗口的最后一句。 */
    public List<String> windows(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return List.of("");
        }
        if (tokens(normalized) <= WINDOW_MAX_TOKENS) {
            return List.of(normalized);
        }
        List<String> pieces = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            if (!paragraph.isBlank()) {
                // 预留重叠句的空间，否则装满的窗口放不下上一窗口带过来的句子
                pieces.addAll(splitToFit(paragraph.strip(), WINDOW_MAX_TOKENS - WINDOW_CARRY_TOKENS));
            }
        }
        List<String> windows = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int bufferTokens = 0;
        for (String piece : pieces) {
            int pieceTokens = tokens(piece);
            if (!buffer.isEmpty() && bufferTokens + pieceTokens > WINDOW_MAX_TOKENS) {
                windows.add(String.join("\n", buffer).strip());
                String carry = lastSentence(buffer.get(buffer.size() - 1));
                buffer = new ArrayList<>();
                bufferTokens = 0;
                if (!carry.isBlank() && tokens(carry) <= WINDOW_CARRY_TOKENS
                        && tokens(carry) + pieceTokens <= WINDOW_MAX_TOKENS) {
                    buffer.add(carry);
                    bufferTokens = tokens(carry);
                }
            }
            buffer.add(piece);
            bufferTokens += pieceTokens;
        }
        if (!buffer.isEmpty()) {
            windows.add(String.join("\n", buffer).strip());
        }
        return windows;
    }

    // ---------- 模式识别 ----------

    private Mode detectMode(String text, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if ("text/csv".equals(mime)) {
            return Mode.CSV;
        }
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        long stamped = lines.stream().filter(line -> TIMESTAMP_LINE.matcher(line).find()).count();
        if (SourceMediaTypes.isMedia(mime) || (!lines.isEmpty() && stamped * 10 >= lines.size() * 6L)) {
            return Mode.TRANSCRIPT;
        }
        if (mime.contains("markdown") || lines.stream().anyMatch(line -> MARKDOWN_HEADING.matcher(line).matches())) {
            return Mode.MARKDOWN;
        }
        return Mode.PLAIN;
    }

    // ---------- 解析成块 ----------

    private List<Block> structuredBlocks(String text, Mode mode) {
        List<Block> blocks = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        boolean paged = text.indexOf('\f') >= 0;
        String[] pages = text.split("\f", -1);
        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            Integer page = paged ? pageIndex + 1 : null;
            List<String> paragraphs = mode == Mode.MARKDOWN
                    ? markdownParagraphs(pages[pageIndex])
                    : plainParagraphs(pages[pageIndex]);
            for (int index = 0; index < paragraphs.size(); index++) {
                String paragraph = paragraphs.get(index);
                Heading heading = mode == Mode.MARKDOWN
                        ? markdownHeading(paragraph)
                        : plainHeading(paragraph,
                                index > 0 ? paragraphs.get(index - 1) : null,
                                index + 1 < paragraphs.size() ? paragraphs.get(index + 1) : null);
                if (heading != null) {
                    while (!levels.isEmpty() && levels.get(levels.size() - 1) >= heading.level()) {
                        levels.remove(levels.size() - 1);
                        path.remove(path.size() - 1);
                    }
                    levels.add(heading.level());
                    path.add(heading.title());
                    blocks.add(new Block(Kind.HEADING, paragraph, List.copyOf(path), page, null, tokens(paragraph)));
                    continue;
                }
                boolean atomic = mode == Mode.MARKDOWN && (FENCE.matcher(paragraph).find() || isTable(paragraph));
                blocks.add(new Block(atomic ? Kind.ATOMIC : Kind.PARAGRAPH, paragraph, List.copyOf(path), page,
                        null, tokens(paragraph)));
            }
        }
        return blocks;
    }

    /** Markdown 段落：空行分隔；围栏代码块整体保留，标题行单独成段。 */
    private List<String> markdownParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                if (!inFence) {
                    flush(paragraphs, current);
                }
                current.append(line).append('\n');
                inFence = !inFence;
                if (!inFence) {
                    flush(paragraphs, current);
                }
                continue;
            }
            if (inFence) {
                current.append(line).append('\n');
                continue;
            }
            if (line.isBlank()) {
                flush(paragraphs, current);
            } else if (MARKDOWN_HEADING.matcher(line.strip()).matches()) {
                flush(paragraphs, current);
                paragraphs.add(line.strip());
            } else {
                current.append(line).append('\n');
            }
        }
        flush(paragraphs, current);
        return paragraphs;
    }

    /** 纯文本与 PDF 段落：有空行时按空行分隔；没有空行时（PDF 常见）每行一个段落，解析阶段已合并断行。 */
    private List<String> plainParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text.contains("\n\n")) {
            for (String paragraph : text.split("\\n\\s*\\n")) {
                if (paragraph.isBlank()) continue;
                // 段落首行是标题时拆出来，避免标题和正文混在一个块里
                String[] lines = paragraph.strip().split("\n", 2);
                if (lines.length == 2 && looksLikeHeadingLine(lines[0].strip())) {
                    paragraphs.add(lines[0].strip());
                    paragraphs.add(lines[1].strip());
                } else {
                    paragraphs.add(paragraph.strip());
                }
            }
        } else {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    paragraphs.add(line.strip());
                }
            }
        }
        return paragraphs;
    }

    private static void flush(List<String> paragraphs, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            paragraphs.add(value);
        }
        current.setLength(0);
    }

    private Heading markdownHeading(String paragraph) {
        Matcher matcher = MARKDOWN_HEADING.matcher(paragraph);
        if (!matcher.matches()) {
            return null;
        }
        return new Heading(matcher.group(1).length(), clean(matcher.group(2)));
    }

    /**
     * 纯文本标题：短行、不以标点结尾，并符合章节编号或常见章节名。
     * 前后紧挨着同类编号短行时更可能是列表项，不当作标题。
     */
    private Heading plainHeading(String paragraph, String previous, String next) {
        if (paragraph.contains("\n") || !looksLikeHeadingLine(paragraph)) {
            return null;
        }
        if (next != null && looksLikeHeadingLine(next) && sameNumberingStyle(paragraph, next)) {
            return null;
        }
        if (previous != null && looksLikeHeadingLine(previous) && sameNumberingStyle(paragraph, previous)) {
            return null;
        }
        return new Heading(plainHeadingLevel(paragraph), clean(paragraph));
    }

    private boolean looksLikeHeadingLine(String line) {
        if (line.isEmpty() || line.length() > 40 || TERMINAL_PUNCTUATION.matcher(line).find()) {
            return false;
        }
        return CHINESE_CHAPTER.matcher(line).matches()
                || CHINESE_SECTION.matcher(line).matches()
                || CHINESE_ENUM.matcher(line).matches()
                || CHINESE_PAREN_ENUM.matcher(line).matches()
                || NUMBERED.matcher(line).matches()
                || NAMED_SECTION.matcher(line).matches();
    }

    private boolean sameNumberingStyle(String first, String second) {
        return plainHeadingLevel(first) == plainHeadingLevel(second)
                && numberingFamily(first).equals(numberingFamily(second));
    }

    private static String numberingFamily(String line) {
        if (CHINESE_CHAPTER.matcher(line).matches()) return "chapter";
        if (CHINESE_SECTION.matcher(line).matches()) return "section";
        if (CHINESE_ENUM.matcher(line).matches()) return "cn-enum";
        if (CHINESE_PAREN_ENUM.matcher(line).matches()) return "cn-paren";
        if (NUMBERED.matcher(line).matches()) return "numbered";
        return "named";
    }

    private static int plainHeadingLevel(String line) {
        if (CHINESE_CHAPTER.matcher(line).matches()) return 1;
        if (CHINESE_SECTION.matcher(line).matches()) return 2;
        if (CHINESE_ENUM.matcher(line).matches()) return 1;
        if (CHINESE_PAREN_ENUM.matcher(line).matches()) return 2;
        Matcher numbered = NUMBERED.matcher(line);
        if (numbered.matches()) {
            return Math.min(4, numbered.group(1).split("\\.").length);
        }
        return 1;
    }

    private static boolean isTable(String paragraph) {
        List<String> lines = paragraph.lines().toList();
        return lines.size() >= 2 && lines.stream().allMatch(line -> line.strip().startsWith("|"));
    }

    private List<Block> transcriptBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        for (String raw : text.replace("\f", "\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            Matcher matcher = TIMESTAMP_LINE.matcher(line);
            String time = matcher.find() ? matcher.group(1) : null;
            blocks.add(new Block(Kind.LINE, line, List.of(), null, time, tokens(line)));
        }
        return blocks;
    }

    private List<Block> csvBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> lines = text.replace("\f", "\n").lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return blocks;
        }
        String header = lines.get(0).strip();
        blocks.add(new Block(Kind.HEADING, header, List.of(), null, null, tokens(header)));
        for (int index = 1; index < lines.size(); index++) {
            String row = lines.get(index).strip();
            blocks.add(new Block(Kind.ROW, row, List.of(), null, null, tokens(row)));
        }
        return blocks;
    }

    // ---------- 组装片段 ----------

    private List<DocumentChunk> assemble(List<Block> blocks, Mode mode) {
        List<Block> parts = new ArrayList<>();
        for (Block block : blocks) {
            if (block.tokens() <= maxTokens || block.kind() == Kind.HEADING) {
                parts.add(block);
                continue;
            }
            // 预留重叠句的空间，切开后的段落仍能带上上一片段的末句
            for (String piece : splitToFit(block.text(), Math.max(32, maxTokens - overlapTokens))) {
                Kind kind = block.kind() == Kind.ATOMIC ? Kind.PARAGRAPH : block.kind();
                parts.add(new Block(kind, piece, block.path(), block.page(), block.time(), tokens(piece)));
            }
        }
        List<DocumentChunk> chunks = new ArrayList<>();
        List<Block> buffer = new ArrayList<>();
        int bufferTokens = 0;
        for (Block part : parts) {
            boolean sectionChanged = !buffer.isEmpty() && part.kind() == Kind.HEADING
                    && bufferTokens >= minSectionTokens;
            boolean overflow = !buffer.isEmpty() && bufferTokens + part.tokens() > maxTokens;
            if (sectionChanged || overflow) {
                List<Block> carried = trailingHeadings(buffer);
                List<Block> emitted = buffer.subList(0, buffer.size() - carried.size());
                List<Block> next = new ArrayList<>(carried);
                if (!emitted.isEmpty()) {
                    chunks.add(toChunk(emitted, mode));
                    if (overflow && !sectionChanged && carried.isEmpty()) {
                        Block overlap = overlapBlock(emitted.get(emitted.size() - 1), mode);
                        if (overlap != null && overlap.tokens() + part.tokens() <= maxTokens) {
                            next.add(overlap);
                        }
                    }
                }
                buffer = next;
                bufferTokens = buffer.stream().mapToInt(Block::tokens).sum();
            }
            buffer.add(part);
            bufferTokens += part.tokens();
        }
        if (!buffer.isEmpty()) {
            chunks.add(toChunk(buffer, mode));
        }
        return chunks;
    }

    /** 表格类资料：按行组装，每个片段都带上表头，片段之间不重叠。 */
    private List<DocumentChunk> assembleCsv(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        Block header = blocks.get(0);
        List<DocumentChunk> chunks = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        int rowTokens = header.tokens();
        for (Block row : blocks.subList(1, blocks.size())) {
            if (!rows.isEmpty() && rowTokens + row.tokens() > maxTokens) {
                chunks.add(csvChunk(header.text(), rows));
                rows = new ArrayList<>();
                rowTokens = header.tokens();
            }
            rows.add(row.text());
            rowTokens += row.tokens();
        }
        chunks.add(csvChunk(header.text(), rows));
        return chunks;
    }

    private static DocumentChunk csvChunk(String header, List<String> rows) {
        String content = rows.isEmpty() ? header : header + "\n" + String.join("\n", rows);
        return new DocumentChunk("", content, null, null, null, null, tokens(content));
    }

    private static List<Block> trailingHeadings(List<Block> buffer) {
        int index = buffer.size();
        while (index > 0 && buffer.get(index - 1).kind() == Kind.HEADING) {
            index--;
        }
        if (index == 0) {
            return List.of();
        }
        return new ArrayList<>(buffer.subList(index, buffer.size()));
    }

    /**
     * 重叠内容：转写稿取上一行；其他资料取上一片段最后一个正文块末尾、不超过重叠预算的完整句子。
     * 代码和表格不做重叠。
     */
    private Block overlapBlock(Block last, Mode mode) {
        if (overlapTokens == 0 || last.kind() == Kind.HEADING || last.kind() == Kind.ATOMIC) {
            return null;
        }
        if (mode == Mode.TRANSCRIPT) {
            return last.tokens() <= overlapTokens ? last : null;
        }
        List<String> sentences = sentences(last.text());
        String carry = "";
        for (int index = sentences.size() - 1; index >= 0; index--) {
            String candidate = sentences.get(index).strip() + carry;
            if (tokens(candidate) > overlapTokens) break;
            carry = candidate;
        }
        if (carry.isEmpty() || carry.equals(last.text().strip())) {
            return null;
        }
        return new Block(Kind.PARAGRAPH, carry, last.path(), last.page(), last.time(), tokens(carry));
    }

    private static DocumentChunk toChunk(List<Block> blocks, Mode mode) {
        String separator = mode == Mode.TRANSCRIPT ? "\n" : "\n\n";
        StringBuilder content = new StringBuilder();
        Integer pageStart = null;
        Integer pageEnd = null;
        String timeStart = null;
        String timeEnd = null;
        for (Block block : blocks) {
            if (!content.isEmpty()) content.append(separator);
            content.append(block.text());
            if (block.page() != null) {
                pageStart = pageStart == null ? block.page() : Math.min(pageStart, block.page());
                pageEnd = pageEnd == null ? block.page() : Math.max(pageEnd, block.page());
            }
            if (block.time() != null) {
                if (timeStart == null) timeStart = block.time();
                timeEnd = block.time();
            }
        }
        String text = content.toString().strip();
        return new DocumentChunk(headingPath(blocks), text, pageStart, pageEnd, timeStart, timeEnd, tokens(text));
    }

    /**
     * 片段的章节路径取所有块路径的公共前缀。没有公共前缀时（片段跨了几个同级章节，
     * 或包含第一个标题之前的前言），列出片段内出现的各个一级章节名。
     */
    private static String headingPath(List<Block> blocks) {
        List<String> common = null;
        for (Block block : blocks) {
            List<String> path = block.path();
            if (common == null) {
                common = new ArrayList<>(path);
                continue;
            }
            int length = 0;
            while (length < common.size() && length < path.size() && common.get(length).equals(path.get(length))) {
                length++;
            }
            common = new ArrayList<>(common.subList(0, length));
        }
        if (common == null || common.isEmpty()) {
            java.util.LinkedHashSet<String> sections = new java.util.LinkedHashSet<>();
            for (Block block : blocks) {
                if (!block.path().isEmpty()) sections.add(block.path().get(0));
            }
            return truncate(String.join(" / ", sections), HEADING_PATH_MAX_CHARS);
        }
        return truncate(String.join(PATH_SEPARATOR, common), HEADING_PATH_MAX_CHARS);
    }

    // ---------- 递归切分 ----------

    /** 超长文本先按句子、再按分句组装到预算以内，仍然过长的单句才按 Token 硬切。 */
    private static List<String> splitToFit(String text, int budget) {
        if (tokens(text) <= budget) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        for (String sentence : sentences(text)) {
            if (tokens(sentence) <= budget) {
                pieces.add(sentence);
                continue;
            }
            for (String clause : CLAUSE_BREAK.split(sentence)) {
                if (tokens(clause) <= budget) {
                    pieces.add(clause);
                } else {
                    pieces.addAll(hardCut(clause, budget));
                }
            }
        }
        return pack(pieces, budget);
    }

    private static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String piece : SENTENCE_BREAK.split(text)) {
            if (!piece.isBlank()) sentences.add(piece);
        }
        return sentences;
    }

    private static String lastSentence(String text) {
        List<String> sentences = sentences(text);
        return sentences.isEmpty() ? "" : sentences.get(sentences.size() - 1).strip();
    }

    private static List<String> pack(List<String> pieces, int budget) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (!current.isEmpty() && tokens(current + piece) > budget) {
                packed.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(piece);
        }
        if (!current.toString().isBlank()) {
            packed.add(current.toString().strip());
        }
        return packed;
    }

    private static List<String> hardCut(String text, int budget) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            current.appendCodePoint(codePoint);
            if (tokens(current.toString()) >= budget) {
                pieces.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) pieces.add(current.toString());
        return pieces;
    }

    // ---------- 工具 ----------

    static int tokens(String text) {
        return ContextTokenEstimator.estimate(text);
    }

    /** 标题文字去掉 Markdown 强调符号和多余空白。 */
    private static String clean(String title) {
        return title.replaceAll("[*_`]+", "").replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }
}
