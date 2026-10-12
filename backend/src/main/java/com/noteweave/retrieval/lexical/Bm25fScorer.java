package com.noteweave.retrieval.lexical;

import com.noteweave.common.text.LexicalTerms;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存 BM25F。字段各自做长度归一，按字段权重合成词频后再饱和。
 * 切词使用 {@link LexicalTerms}，与 Wiki 检索和本地降级打分保持同一套汉字二元切分。
 * 覆盖判定、链接传播和权威度由调用方处理。
 */
public final class Bm25fScorer {
    public static final double DEFAULT_K1 = 1.2d;
    public static final double DEFAULT_B = 0.75d;
    /** 字段加权词在对应字段上的乘数，与 Wiki 内存检索一致。 */
    public static final double FIELD_TERM_BOOST = 2.0d;

    public List<Hit> score(
            double k1,
            List<FieldSpec> fieldSpecs,
            List<Document> documents,
            Set<String> queryTerms,
            Map<String, Double> termBoosts,
            Map<String, Set<String>> fieldTermBoosts
    ) {
        if (fieldSpecs == null || fieldSpecs.isEmpty() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> terms = normalizeTerms(queryTerms);
        if (terms.isEmpty()) {
            return List.of();
        }
        double saturationK = k1 > 0.0d ? k1 : DEFAULT_K1;
        Map<String, Double> safeTermBoosts = termBoosts == null ? Map.of() : termBoosts;
        Map<String, Set<String>> safeFieldBoosts = fieldTermBoosts == null ? Map.of() : fieldTermBoosts;

        List<Field[]> tokenized = new ArrayList<>(documents.size());
        double[] totalLength = new double[fieldSpecs.size()];
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Document document : documents) {
            Field[] fields = tokenize(document, fieldSpecs);
            tokenized.add(fields);
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < fields.length; index++) {
                totalLength[index] += fields[index].length();
                for (String term : terms) {
                    if (fields[index].frequency(term) > 0) {
                        seen.add(term);
                    }
                }
            }
            seen.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        int count = documents.size();
        double[] averageLength = new double[fieldSpecs.size()];
        for (int index = 0; index < fieldSpecs.size(); index++) {
            averageLength[index] = Math.max(1.0d, totalLength[index] / count);
        }
        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            int df = documentFrequency.getOrDefault(term, 0);
            idf.put(term, Math.log(1.0d + (count - df + 0.5d) / (df + 0.5d)));
        }

        List<Hit> hits = new ArrayList<>();
        for (int documentIndex = 0; documentIndex < count; documentIndex++) {
            Field[] fields = tokenized.get(documentIndex);
            double score = 0.0d;
            Set<String> matched = new LinkedHashSet<>();
            List<String> matchedFields = new ArrayList<>();
            for (int index = 0; index < fields.length; index++) {
                boolean fieldMatched = false;
                for (String term : terms) {
                    if (fields[index].frequency(term) > 0) {
                        fieldMatched = true;
                        break;
                    }
                }
                if (fieldMatched) {
                    matchedFields.add(fieldSpecs.get(index).name());
                }
            }
            for (String term : terms) {
                double termBoost = safeTermBoosts.getOrDefault(term, 1.0d);
                double weighted = 0.0d;
                for (int index = 0; index < fields.length; index++) {
                    int frequency = fields[index].frequency(term);
                    if (frequency == 0) {
                        continue;
                    }
                    Set<String> boosted = safeFieldBoosts.getOrDefault(fieldSpecs.get(index).name(), Set.of());
                    double fieldBoost = boosted.contains(term) ? FIELD_TERM_BOOST : 1.0d;
                    double normalization = 1.0d - fields[index].b()
                            + fields[index].b() * fields[index].length() / averageLength[index];
                    weighted += fields[index].weight() * frequency / normalization * fieldBoost;
                }
                if (weighted == 0.0d) {
                    continue;
                }
                matched.add(term);
                score += idf.get(term) * termBoost * weighted / (saturationK + weighted);
            }
            if (score > 0.0d && documents.get(documentIndex).id() != null) {
                hits.add(new Hit(
                        documents.get(documentIndex).id(),
                        score,
                        Set.copyOf(matched),
                        List.copyOf(matchedFields)));
            }
        }
        return List.copyOf(hits);
    }

    private Field[] tokenize(Document document, List<FieldSpec> fieldSpecs) {
        Field[] fields = new Field[fieldSpecs.size()];
        Map<String, String> values = document.fields() == null ? Map.of() : document.fields();
        for (int index = 0; index < fieldSpecs.size(); index++) {
            FieldSpec spec = fieldSpecs.get(index);
            fields[index] = Field.of(values.get(spec.name()), spec.weight(), spec.b());
        }
        return fields;
    }

    private List<String> normalizeTerms(Set<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String term : queryTerms) {
            if (term != null && !term.isBlank()) {
                unique.add(term);
            }
        }
        return List.copyOf(unique);
    }

    public record FieldSpec(String name, double weight, double b) {
    }

    public record Document(String id, Map<String, String> fields) {
    }

    public record Hit(String id, double score, Set<String> matchedTerms, List<String> matchedFields) {
    }

    private record Field(Map<String, Integer> frequencies, int length, double weight, double b) {
        private static Field of(String text, double weight, double b) {
            List<String> tokens = LexicalTerms.tokens(text);
            Map<String, Integer> frequencies = new HashMap<>();
            tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
            return new Field(frequencies, tokens.size(), weight, b);
        }

        private int frequency(String term) {
            return frequencies.getOrDefault(term, 0);
        }
    }
}
