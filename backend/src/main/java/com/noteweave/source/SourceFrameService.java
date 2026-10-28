package com.noteweave.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 关键帧归属与读取服务，供资料详情和 M3 的任务范围图片接口共用。 */
@Service
public class SourceFrameService {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;

    public SourceFrameService(JdbcTemplate jdbc, ObjectStorage storage, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.mapper = mapper;
    }

    /** 只读取当前快照、未删除资料的关键帧；调用者负责校验用户或任务的资料访问权限。 */
    public FrameImage readFrame(String workspaceId, String sourceId, String frameId) {
        List<FrameRef> rows = jdbc.query("""
                select f.object_key, f.media_type from source_media_frame f
                join source s on s.id = f.source_id and s.workspace_id = f.workspace_id
                join source_snapshot ss on ss.id = f.source_snapshot_id and ss.source_id = s.id
                where f.workspace_id = ? and f.source_id = ? and f.frame_id = ? and s.status <> 'DELETED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                """, (rs, row) -> new FrameRef(rs.getString(1), rs.getString(2)), workspaceId, sourceId, frameId);
        if (rows.size() != 1) throw new BusinessException("SOURCE_FRAME_NOT_FOUND", "关键帧不存在", HttpStatus.NOT_FOUND);
        return new FrameImage(storage.read(SourcePipelineStages.BUCKET_DERIVED, rows.get(0).key()), rows.get(0).type());
    }

    SourceMediaDocument save(String workspaceId, String sourceId, String snapshotId, SourceMediaDocument document) {
        jdbc.update("delete from source_media_frame where source_snapshot_id = ?", snapshotId);
        List<SourceMediaDocument.Segment> segments = new ArrayList<>();
        for (var segment : document.segments()) {
            List<SourceMediaDocument.Frame> frames = new ArrayList<>();
            for (var frame : segment.frames()) {
                String prefix = "data:image/png;base64,";
                String type = "image/png";
                if (frame.imageRef().startsWith("data:image/jpeg;base64,")) {
                    prefix = "data:image/jpeg;base64,";
                    type = "image/jpeg";
                }
                if (!frame.imageRef().startsWith(prefix)) throw new IllegalArgumentException("关键帧必须提供 PNG 或 JPEG 图片内容");
                byte[] content = Base64.getDecoder().decode(frame.imageRef().substring(prefix.length()));
                validateImage(content, type);
                String key = "workspace/%s/source/%s/snapshot/%s/media-frames/%s.%s".formatted(
                        workspaceId, sourceId, snapshotId, frame.frameId(), type.equals("image/png") ? "png" : "jpg");
                storage.write(SourcePipelineStages.BUCKET_DERIVED, key, content);
                jdbc.update("""
                        insert into source_media_frame(workspace_id, source_id, source_snapshot_id, frame_id,
                            part, at_ms, object_key, media_type) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, workspaceId, sourceId, snapshotId, frame.frameId(), segment.part(), frame.atMs(), key, type);
                String imageUrl = "/api/v2/workspaces/%s/sources/%s/frames/%s".formatted(workspaceId, sourceId, frame.frameId());
                frames.add(new SourceMediaDocument.Frame(frame.frameId(), frame.atMs(), imageUrl, frame.caption(), frame.ocrText()));
            }
            segments.add(new SourceMediaDocument.Segment(segment.part(), segment.startMs(), segment.endMs(), segment.text(), frames));
        }
        return new SourceMediaDocument(document.title(), document.sourceUrl(), segments, document.stats());
    }

    public SourceMediaDocument readDocument(String workspaceId, String sourceId) {
        List<String> rows = jdbc.queryForList("""
                select ss.media_document_json from source_snapshot ss join source s on s.id = ss.source_id
                where s.workspace_id = ? and s.id = ? and s.status <> 'DELETED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                  and ss.media_document_json is not null
                """, String.class, workspaceId, sourceId);
        if (rows.isEmpty()) throw new BusinessException("SOURCE_MEDIA_NOT_READY", "图文解析结果尚未就绪", HttpStatus.NOT_FOUND);
        try { return mapper.readValue(rows.get(0), SourceMediaDocument.class); }
        catch (java.io.IOException ex) { throw new BusinessException("SOURCE_MEDIA_INVALID", "图文解析结果不可读取"); }
    }

    /** 复用已有的对象清理任务，资料删除后由现有调度器删除 MinIO 中的图片。 */
    void enqueueDeletion(String workspaceId, String sourceId) {
        List<String> keys = jdbc.queryForList("select object_key from source_media_frame where workspace_id = ? and source_id = ?",
                String.class, workspaceId, sourceId);
        for (String key : keys) {
            String digest;
            try { digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            jdbc.update("""
                    insert into source_cleanup_task(id, workspace_id, source_id, cleanup_type, bucket_name,
                        object_key, object_key_sha256, status, next_attempt_at)
                    values (?, ?, ?, 'SOURCE_OBJECT', ?, ?, ?, 'READY', current_timestamp)
                    """, com.noteweave.common.Ids.newId(), workspaceId, sourceId, SourcePipelineStages.BUCKET_DERIVED, key, digest);
        }
    }

    private static void validateImage(byte[] content, String type) {
        if (content.length == 0 || content.length > 16_000_000) throw new IllegalArgumentException("关键帧图片超过大小上限");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("关键帧图片格式无效");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName();
                if (!(type.equals("image/png") && format.equalsIgnoreCase("png")
                        || type.equals("image/jpeg") && format.equalsIgnoreCase("jpeg"))
                        || (long) reader.getWidth(0) * reader.getHeight(0) > 50_000_000) {
                    throw new IllegalArgumentException("关键帧图片类型或像素数量无效");
                }
            } finally { reader.dispose(); }
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("关键帧图片无法解码", ex); }
    }

    public record FrameImage(byte[] content, String mediaType) { }
    private record FrameRef(String key, String type) { }
}
