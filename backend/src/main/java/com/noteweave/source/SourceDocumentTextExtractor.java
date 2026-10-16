package com.noteweave.source;

import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 从资料原件提取纯文本。
 * <ul>
 *   <li>文本类资料按 BOM 和严格 UTF-8 校验识别编码，不是合法 UTF-8 时按 GB18030 解码，避免 GBK 文件乱码。</li>
 *   <li>PDF 逐页提取，页与页之间用换页符 {@code \f} 分隔，切片阶段据此给片段标注页码；
 *       同时去掉每页重复出现的页眉页脚和页码，并把排版造成的句中断行合并回段落。</li>
 * </ul>
 */
@Component
public class SourceDocumentTextExtractor {

    static final char PAGE_BREAK = '\f';
    /** 页面最长行的显示宽度低于这个值时不合并断行。 */
    private static final int MIN_BODY_LINE_WIDTH = 50;
    private static final int ENCODING_PROBE_BYTES = 64 * 1024;
    private final SourceDocumentParserPort parserPort;
    private final SourceDocumentParserProperties properties;

    /** 保留独立使用时的本地解析构造，Spring 使用下面的注入构造。 */
    public SourceDocumentTextExtractor() {
        this(null, SourceDocumentParserProperties.localDefaults());
    }

    @Autowired
    public SourceDocumentTextExtractor(SourceDocumentParserPort parserPort, SourceDocumentParserProperties properties) {
        this.parserPort = parserPort;
        this.properties = properties;
    }

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "application/json",
            "text/csv"
    );
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile(
            "^(第\\s*)?[-–—]?\\s*\\d{1,4}\\s*[-–—]?(\\s*页)?$|^\\d{1,4}\\s*/\\s*\\d{1,4}$|^(page|p\\.)\\s*\\d{1,4}$",
            Pattern.CASE_INSENSITIVE);
    /** 句末字符；\u201D 是中文后引号，写成转义避免源码里出现弯引号。 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；：.!?;:」』\u201D）)]$");
    private static final Pattern BLOCK_START = Pattern.compile(
            "^([•·●○▪\\-*]\\s|\\d{1,2}(\\.\\d{1,2}){0,3}[.、．)）]?\\s|[（(][一二三四五六七八九十\\d]+[)）]|[一二三四五六七八九十]+[、.．]|第[一二三四五六七八九十百零〇\\d]+[章节篇部])");

    public ExtractedDocument extract(String fileName, String mimeType, byte[] content) {
        Path file = null;
        try {
            file = Files.createTempFile("noteweave-document-", ".bin");
            Files.write(file, content);
            return extract(fileName, mimeType, file);
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_DOCUMENT_READ_FAILED", "资料原件读取失败：" + safeName(fileName));
        } finally {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    /**
     * 从本地文件提取文本。PDF 按文件加载，PDFBox 的缓冲放在临时文件里，大文件不会整体进入堆内存；
     * 文本类资料只探测前 64KB 编码，再按字符预算流式读取。
     */
    public ExtractedDocument extract(String fileName, String mimeType, Path file) {
        return extract(new SourceDocumentParserPort.Request("local", "local", "local", safeName(fileName),
                normalizeMime(mimeType), sha256(file), properties.maxChars()), file);
    }

    /** 业务入口明确携带快照身份，原件摘要与快照不一致时拒绝解析。 */
    public ExtractedDocument extract(SourceDocumentParserPort.Request request, Path file) {
        if (request.maxChars() < 1) throw new BusinessException("SOURCE_DOCUMENT_BUDGET_INVALID", "资料解析字符预算无效");
        String actualHash = sha256(file);
        if (request.sha256() == null || !actualHash.equalsIgnoreCase(request.sha256())) {
            throw new BusinessException("SOURCE_DOCUMENT_HASH_MISMATCH", "资料原件摘要与快照不一致");
        }
        String normalizedMime = normalizeMime(request.mimeType());
        SourceDocumentParserPort.Request bounded = new SourceDocumentParserPort.Request(request.workspaceId(),
                request.sourceId(), request.snapshotId(), safeName(request.fileName()), normalizedMime,
                actualHash, Math.min(properties.maxChars(), request.maxChars()));
        String degradation = properties.enabled() ? "SOURCE_PARSER_UNAVAILABLE" : "SOURCE_PARSER_DISABLED";
        if (properties.enabled() && parserPort != null) {
            try {
                SourceDocumentParserPort.ParsedDocument parsed = parserPort.parse(bounded, file);
                if (parsed == null || parsed.schemaVersion() != 1 || !"DOCLING".equals(parsed.parser())
                        || !bounded.workspaceId().equals(parsed.workspaceId()) || !bounded.sourceId().equals(parsed.sourceId())
                        || !bounded.snapshotId().equals(parsed.snapshotId()) || !actualHash.equals(parsed.sha256())
                        || !normalizedMime.equals(parsed.mimeType()) || parsed.markdown() == null || parsed.markdown().isBlank()
                        || parsed.markdown().length() > bounded.maxChars()) {
                    throw new BusinessException("SOURCE_PARSER_RESPONSE_INVALID", "文档解析响应与请求不一致");
                }
                return new ExtractedDocument(parsed.markdown(), parsed.mimeType(), parsed.pageCount(),
                        parsed.elements(), parsed.parser(), parsed.truncated(), parsed.warnings(), null, parsed.pageSources());
            } catch (RuntimeException exception) {
                degradation = exception instanceof BusinessException business ? business.code() : "SOURCE_PARSER_UNAVAILABLE";
            }
        }
        return localExtract(bounded.fileName(), normalizedMime, file, bounded.maxChars(), degradation);
    }

    private ExtractedDocument localExtract(String fileName, String normalizedMime, Path file, int maxChars, String degradation) {
        if ("application/pdf".equals(normalizedMime)) {
            try (PDDocument document = PDDocument.load(file.toFile(),
                    org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
                return pdfText(fileName, document, maxChars, degradation);
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new BusinessException("SOURCE_PDF_PARSE_FAILED", "PDF 解析失败：" + safeName(fileName));
            }
        }
        if (!TEXT_MIME_TYPES.contains(normalizedMime)) {
            throw new BusinessException("SOURCE_DOCUMENT_PARSER_REQUIRED",
                    "该资料类型需要文档解析服务，当前服务无法完成解析：" + normalizedMime + "（" + degradation + "）");
        }
        try {
            byte[] probe;
            try (InputStream input = Files.newInputStream(file)) { probe = input.readNBytes(ENCODING_PROBE_BYTES); }
            Encoding encoding = detectEncoding(probe);
            TextRead read;
            try {
                read = readText(file, encoding, maxChars);
            } catch (CharacterCodingException invalidUtf8) {
                if (!encoding.charset().equals(StandardCharsets.UTF_8) || encoding.bomBytes() != 0) throw invalidUtf8;
                read = readText(file, new Encoding(GB18030, 0), maxChars);
            }
            return new ExtractedDocument(read.text(), normalizedMime, 0, List.of(), "PLAIN_TEXT", read.truncated(),
                    read.truncated() ? List.of("MAX_CHARS_REACHED") : List.of(), degradation);
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_DOCUMENT_READ_FAILED", "资料原件读取失败：" + safeName(fileName));
        }
    }

    private static Encoding detectEncoding(byte[] probe) {
        if (probe.length >= 3 && (probe[0] & 255) == 239 && (probe[1] & 255) == 187 && (probe[2] & 255) == 191)
            return new Encoding(StandardCharsets.UTF_8, 3);
        if (probe.length >= 2 && (probe[0] & 255) == 254 && (probe[1] & 255) == 255)
            return new Encoding(StandardCharsets.UTF_16BE, 2);
        if (probe.length >= 2 && (probe[0] & 255) == 255 && (probe[1] & 255) == 254)
            return new Encoding(StandardCharsets.UTF_16LE, 2);
        // 探测块尾部可能停在 UTF-8 字符中间，因此不把尚未读完的尾部当成编码错误。
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var result = decoder.decode(ByteBuffer.wrap(probe), CharBuffer.allocate(probe.length), false);
        return new Encoding(result.isError() ? GB18030 : StandardCharsets.UTF_8, 0);
    }

    private static TextRead readText(Path file, Encoding encoding, int maxChars) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            input.skipNBytes(encoding.bomBytes());
            try (Reader reader = new InputStreamReader(input, encoding.charset().newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                StringBuilder text = new StringBuilder(Math.min(maxChars, 64 * 1024));
                char[] buffer = new char[8192];
                while (text.length() < maxChars) {
                    int read = reader.read(buffer, 0, Math.min(buffer.length, maxChars - text.length()));
                    if (read < 0) return new TextRead(text.toString(), false);
                    text.append(buffer, 0, read);
                }
                boolean truncated = reader.read() >= 0;
                if (truncated && !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                    text.setLength(text.length() - 1);
                }
                return new TextRead(text.toString(), truncated);
            }
        }
    }

    static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new BusinessException("SOURCE_DOCUMENT_READ_FAILED", "资料原件摘要读取失败");
        }
    }

    private record Encoding(Charset charset, int bomBytes) {}
    private record TextRead(String text, boolean truncated) {}

    /** 文本解码：BOM 优先；无 BOM 时先按严格 UTF-8 解码，失败再按 GB18030（兼容 GBK、GB2312）。 */
    static String decodeText(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(content, GB18030);
        }
    }

    private ExtractedDocument pdfText(String fileName, PDDocument document, int maxChars, String degradation) throws IOException {
        int pageCount = document.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        List<String> pages = new ArrayList<>(pageCount);
        List<SourceDocumentParserPort.PageSource> pageSources = new ArrayList<>();
        int collectedChars = 0;
        boolean truncated = false;
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(document);
            int nativeCharacters = (int) pageText.codePoints().filter(character -> !Character.isWhitespace(character)).count();
            pageSources.add(new SourceDocumentParserPort.PageSource(page,
                    nativeCharacters == 0 ? null : DocumentElement.TextOrigin.TEXT_LAYER,
                    null, nativeCharacters, null, false));
            int remaining = Math.max(0, maxChars - collectedChars - (page > 1 ? 1 : 0));
            if (pageText.length() > remaining) {
                pages.add(pageText.substring(0, remaining));
                truncated = true;
                break;
            }
            pages.add(pageText);
            collectedChars += pageText.length() + (page > 1 ? 1 : 0);
            if (collectedChars >= maxChars && page < pageCount) { truncated = true; break; }
        }
        String text = normalizePdfPages(pages);
        if (text.replace(String.valueOf(PAGE_BREAK), "").isBlank()) {
            throw new BusinessException(
                    "SOURCE_PDF_TEXT_EMPTY",
                    "PDF 未提取到可检索文本，请先对扫描件执行 OCR：" + safeName(fileName)
            );
        }
        return new ExtractedDocument(text, "application/pdf", pageCount, List.of(), "PDFBOX", truncated,
                truncated ? List.of("MAX_CHARS_REACHED") : List.of(), degradation, pageSources);
    }

    /** 去掉页眉页脚和页码、合并句中断行，再用换页符拼接各页。 */
    static String normalizePdfPages(List<String> rawPages) {
        List<List<String>> pages = new ArrayList<>();
        for (String raw : rawPages) {
            List<String> lines = new ArrayList<>();
            for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty()) lines.add(stripped);
            }
            pages.add(lines);
        }
        Set<String> repeated = repeatedMarginLines(pages);
        List<String> output = new ArrayList<>();
        for (List<String> lines : pages) {
            List<String> kept = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                boolean margin = isMargin(index, lines.size());
                if (margin && (PAGE_NUMBER_LINE.matcher(line).matches() || repeated.contains(marginKey(line)))) {
                    continue;
                }
                kept.add(line);
            }
            output.add(String.join("\n", mergeBrokenLines(kept)));
        }
        // 不对整体 strip：换页符属于空白字符，去掉首尾空页会让后续页码整体错位
        return String.join(String.valueOf(PAGE_BREAK), output);
    }

    /** 至少三页、并在六成以上页面的页首或页尾出现的行视为页眉页脚；数字统一后比较，兼容带页码的页脚。 */
    private static Set<String> repeatedMarginLines(List<List<String>> pages) {
        if (pages.size() < 3) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> lines : pages) {
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < lines.size(); index++) {
                if (isMargin(index, lines.size())) {
                    seen.add(marginKey(lines.get(index)));
                }
            }
            seen.forEach(key -> counts.merge(key, 1, Integer::sum));
        }
        int threshold = (int) Math.ceil(pages.size() * 0.6);
        Set<String> repeated = new HashSet<>();
        counts.forEach((key, count) -> {
            if (count >= threshold && key.length() <= 80) repeated.add(key);
        });
        return repeated;
    }

    /** 页眉页脚候选：行数较多的页取首尾各两行，短页只取首尾各一行，避免把正文当成页眉页脚。 */
    private static boolean isMargin(int index, int size) {
        int band = size > 4 ? 2 : 1;
        return index < band || index >= size - band;
    }

    private static String marginKey(String line) {
        return line.replaceAll("\\d+", "#").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 合并排版断行：上一行写满一行（显示宽度不短于本页最长行的六成）且不以句末标点结尾，
     * 下一行也不是列表项或章节编号时，两行属于同一段落。中文直接相连，英文补空格并去掉断词连字符。
     */
    private static List<String> mergeBrokenLines(List<String> lines) {
        if (lines.size() < 2) {
            return lines;
        }
        int longest = lines.stream().mapToInt(SourceDocumentTextExtractor::displayWidth).max().orElse(0);
        if (longest < MIN_BODY_LINE_WIDTH) {
            // 整页都是短行（幻灯片、图片页、目录），不是被排版折断的正文
            return lines;
        }
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(lines.get(0));
        int currentLastLineWidth = displayWidth(lines.get(0));
        for (int index = 1; index < lines.size(); index++) {
            String next = lines.get(index);
            boolean fullLine = currentLastLineWidth >= longest * 0.6;
            boolean headingLike = BLOCK_START.matcher(current).find() && displayWidth(current.toString()) < 60;
            boolean continues = fullLine
                    && !headingLike
                    && !SENTENCE_END.matcher(current).find()
                    && !BLOCK_START.matcher(next).find();
            if (continues) {
                joinLine(current, next);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(next);
            }
            currentLastLineWidth = displayWidth(next);
        }
        merged.add(current.toString());
        return merged;
    }

    /** 显示宽度：中日韩字符和全角符号按两个半角字符计，用来判断一行是否排满。 */
    private static int displayWidth(String line) {
        int width = 0;
        for (int index = 0; index < line.length(); index++) {
            width += isCjk(line.charAt(index)) ? 2 : 1;
        }
        return width;
    }

    private static void joinLine(StringBuilder current, String next) {
        int lastIndex = current.length() - 1;
        char last = current.charAt(lastIndex);
        char first = next.charAt(0);
        if (last == '-' && lastIndex > 0 && Character.isLetter(current.charAt(lastIndex - 1))
                && Character.isLowerCase(first)) {
            current.setLength(lastIndex);
            current.append(next);
        } else if (isCjk(last) || isCjk(first)) {
            current.append(next);
        } else {
            current.append(' ').append(next);
        }
    }

    private static boolean isCjk(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN
                || Character.UnicodeBlock.of(value) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || Character.UnicodeBlock.of(value) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private String normalizeMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "text/markdown";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String safeName(String fileName) {
        return fileName == null || fileName.isBlank() ? "未命名资料" : fileName;
    }

    public record ExtractedDocument(String text, String mimeType, int pageCount, List<DocumentElement> elements,
                                    String parser, boolean truncated, List<String> warnings, String degradation,
                                    @JsonProperty("page_sources") List<SourceDocumentParserPort.PageSource> pageSources) {
        public ExtractedDocument {
            elements = elements == null ? List.of() : List.copyOf(elements);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            pageSources = pageSources == null ? List.of() : List.copyOf(pageSources);
        }
        public ExtractedDocument(String text, String mimeType, int pageCount, List<DocumentElement> elements,
                                  String parser, boolean truncated, List<String> warnings, String degradation) {
            this(text, mimeType, pageCount, elements, parser, truncated, warnings, degradation, List.of());
        }
        public ExtractedDocument(String text, String mimeType, int pageCount) {
            this(text, mimeType, pageCount, List.of(), "LEGACY", false, List.of(), null);
        }
    }
}
