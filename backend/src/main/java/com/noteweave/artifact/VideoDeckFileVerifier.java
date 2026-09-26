package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Checks the bytes inside each PPTX slide against the frozen picture and text IR. */
final class VideoDeckFileVerifier {
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private VideoDeckFileVerifier() {}

    static void validate(byte[] pptx, List<?> slides) {
        if (pptx.length > 100_000_000 || slides.isEmpty() || slides.size() > 32) throw invalid();
        Map<String, byte[]> entries = new HashMap<>();
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pptx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")
                        || entries.size() > 1000) throw invalid();
                byte[] content = zip.readNBytes(20_000_001);
                if (content.length > 20_000_000 || (expanded += content.length) > 200_000_000
                        || entries.putIfAbsent(name, content) != null) throw invalid();
            }
            if (!entries.containsKey("[Content_Types].xml")
                    || !entries.containsKey("ppt/presentation.xml")) throw invalid();
            Set<String> slideFiles = new HashSet<>();
            for (String name : entries.keySet()) {
                if (name.matches("ppt/slides/slide[0-9]+\\.xml")) slideFiles.add(name);
            }
            if (slideFiles.size() != slides.size()) throw invalid();
            for (int index = 0; index < slides.size(); index++) {
                String slidePath = "ppt/slides/slide" + (index + 1) + ".xml";
                String relationPath = "ppt/slides/_rels/slide" + (index + 1) + ".xml.rels";
                if (!slideFiles.contains(slidePath)) throw invalid();
                Map<?, ?> spec = map(slides.get(index));
                Document slide = xml(entries.get(slidePath));
                NodeList pictures = slide.getElementsByTagNameNS(P, "pic");
                if (pictures.getLength() != 1) throw invalid();
                Element picture = (Element) pictures.item(0);
                NodeList blips = picture.getElementsByTagNameNS(A, "blip");
                if (blips.getLength() != 1) throw invalid();
                String relationshipId = ((Element) blips.item(0)).getAttributeNS(R, "embed");
                if (relationshipId.isBlank()) throw invalid();
                NodeList crops = picture.getElementsByTagNameNS(A, "srcRect");
                for (int crop = 0; crop < crops.getLength(); crop++) {
                    Element rectangle = (Element) crops.item(crop);
                    for (String side : List.of("l", "r", "t", "b")) {
                        if (!rectangle.getAttribute(side).isBlank()
                                && !"0".equals(rectangle.getAttribute(side))) throw invalid();
                    }
                }
                Document relationships = xml(entries.get(relationPath));
                String target = "";
                NodeList links = relationships.getElementsByTagNameNS(REL, "Relationship");
                for (int link = 0; link < links.getLength(); link++) {
                    Element relation = (Element) links.item(link);
                    if (relationshipId.equals(relation.getAttribute("Id"))) {
                        if (!relation.getAttribute("Type").endsWith("/image")
                                || !relation.getAttribute("TargetMode").isBlank()) throw invalid();
                        target = relation.getAttribute("Target");
                    }
                }
                if (target.isBlank() || target.contains("\\")) throw invalid();
                String imagePath = Path.of("ppt/slides").resolve(target).normalize()
                        .toString().replace('\\', '/');
                if (!imagePath.startsWith("ppt/media/")) throw invalid();
                byte[] image = entries.get(imagePath);
                if (image == null || !sha256(image).equals(spec.get("image_checksum_sha256")))
                    throw invalid();
                List<String> words = new ArrayList<>();
                NodeList text = slide.getElementsByTagNameNS(A, "t");
                for (int line = 0; line < text.getLength(); line++) words.add(text.item(line).getTextContent());
                String editable = String.join("\n", words);
                if (!editable.contains(String.valueOf(spec.get("title")))) throw invalid();
                for (Object claim : list(spec.get("claims"))) {
                    if (!editable.contains(String.valueOf(claim))) throw invalid();
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static Document xml(byte[] content) throws Exception {
        if (content == null || content.length > 4_000_000) throw invalid();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
    }

    private static Map<?, ?> map(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        throw invalid();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static BusinessException invalid() {
        return new BusinessException("VIDEO_DECK_FILE_INVALID",
                "PPTX 内的原画面或文字与冻结页清单不一致", HttpStatus.CONFLICT);
    }
}
