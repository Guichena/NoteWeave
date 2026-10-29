package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.source.SourceFrameService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只允许读取任务冻结资料中的关键帧。 */
@Service
public class ArtifactTaskFrameService {
    private final ArtifactJobService jobs;
    private final JdbcTemplate jdbc;
    private final SourceFrameService frames;

    public ArtifactTaskFrameService(ArtifactJobService jobs, JdbcTemplate jdbc, SourceFrameService frames) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.frames = frames;
    }

    public SourceFrameService.FrameImage readFrame(String taskId, String frameId) {
        var input = jobs.getWorkerInput(taskId);
        if (!"FULL".equals(input.replayAvailability())) {
            throw new BusinessException("ARTIFACT_FRAME_REPLAY_DENIED", "任务冻结输入不可完整重放", HttpStatus.CONFLICT);
        }
        String sourceId = null;
        for (var source : input.sourceScope()) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from source_media_frame f
                    join source s on s.id = f.source_id and s.workspace_id = f.workspace_id
                    join source_snapshot ss on ss.id = f.source_snapshot_id and ss.source_id = s.id
                    where f.workspace_id = ? and f.source_id = ? and f.source_snapshot_id = ? and f.frame_id = ?
                      and s.status = 'READY' and ss.parse_status = 'PARSED'
                      and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                    """, Integer.class, input.workspaceId(), source.sourceId(), source.sourceSnapshotId(), frameId);
            if (count != null && count > 0) {
                if (sourceId != null) throw unavailable();
                sourceId = source.sourceId();
            }
        }
        if (sourceId == null) throw unavailable();
        var image = frames.readFrame(input.workspaceId(), sourceId, frameId);
        // 读取对象期间资料也可能被撤权，返回图片前再次检查任务资格。
        jobs.getWorkerInput(taskId);
        return image;
    }

    private BusinessException unavailable() {
        return new BusinessException("ARTIFACT_FRAME_UNAVAILABLE", "关键帧不属于任务冻结资料或已不可读取", HttpStatus.NOT_FOUND);
    }
}
