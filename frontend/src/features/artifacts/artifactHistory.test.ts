import { describe, expect, it } from "vitest";
import { buildArtifactHistoryItems, type ArtifactHistoryJobSummary } from "./artifactHistory";

describe("artifactHistory", () => {
  it("should derive recent history items from completed artifact jobs only", () => {
    const jobs: ArtifactHistoryJobSummary[] = [
      {
        artifact_job_id: "job-3",
        skill_key: "study_guide",
        status: "SUCCEEDED",
        task_status: "SUCCEEDED",
        progress_phase: "COMPLETED",
        progress_message: "done",
        result_title: "  学习指南 V3  ",
        latest_version_no: 2,
        created_at: "2026-07-07T09:00:00Z",
        updated_at: "2026-07-07T11:00:00Z"
      },
      {
        artifact_job_id: "job-2",
        skill_key: "resume_highlight",
        status: "RUNNING",
        task_status: "RUNNING",
        progress_phase: "WRITING",
        progress_message: "still running",
        result_title: "",
        latest_version_no: 0,
        created_at: "2026-07-07T08:00:00Z",
        updated_at: "2026-07-07T12:00:00Z"
      },
      {
        artifact_job_id: "job-1",
        skill_key: "quiz_pack",
        status: "SUCCEEDED",
        task_status: "SUCCEEDED",
        progress_phase: "COMPLETED",
        progress_message: "",
        result_title: "",
        latest_version_no: 1,
        created_at: "2026-07-07T07:00:00Z",
        updated_at: "2026-07-07T10:00:00Z"
      }
    ];

    expect(buildArtifactHistoryItems(jobs)).toEqual([
      {
        key: "artifact-version-job-3-v2",
        artifactJobId: "job-3",
        skillKey: "study_guide",
        versionNo: 2,
        title: "学习指南 V3",
        detail: "done",
        updatedAt: "2026-07-07T11:00:00Z",
        status: "SUCCEEDED"
      },
      {
        key: "artifact-version-job-1-v1",
        artifactJobId: "job-1",
        skillKey: "quiz_pack",
        versionNo: 1,
        title: "",
        detail: "COMPLETED",
        updatedAt: "2026-07-07T10:00:00Z",
        status: "SUCCEEDED"
      }
    ]);
  });

  it("should cap recent history items to the configured limit", () => {
    const jobs = Array.from({ length: 8 }, (_, index) => ({
      artifact_job_id: `job-${index + 1}`,
      skill_key: "resume_highlight",
      status: "SUCCEEDED",
      task_status: "SUCCEEDED",
      progress_phase: "COMPLETED",
      progress_message: `done-${index + 1}`,
      result_title: `result-${index + 1}`,
      latest_version_no: index + 1,
      created_at: `2026-07-07T0${index}:00:00Z`,
      updated_at: `2026-07-${String(10 + index).padStart(2, "0")}T00:00:00Z`
    } satisfies ArtifactHistoryJobSummary));

    const items = buildArtifactHistoryItems(jobs, 3);

    expect(items).toHaveLength(3);
    expect(items.map((item) => item.artifactJobId)).toEqual(["job-8", "job-7", "job-6"]);
  });
});
