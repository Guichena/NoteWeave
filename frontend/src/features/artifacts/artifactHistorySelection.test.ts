import { describe, expect, it } from "vitest";
import {
  resolveLatestArtifactVersionRequest,
  shouldReuseLatestArtifactVersion,
  type ArtifactHistoryVersionDetail,
  type ArtifactVersionRequestCandidate
} from "./artifactHistorySelection";

describe("artifactHistorySelection", () => {
  it("should resolve the latest completed artifact version by updated time instead of input order", () => {
    const jobs: ArtifactVersionRequestCandidate[] = [
      {
        artifact_job_id: "job-older",
        latest_version_no: 2,
        updated_at: "2026-07-07T10:00:00Z"
      },
      {
        artifact_job_id: "job-running",
        latest_version_no: 0,
        updated_at: "2026-07-07T12:00:00Z"
      },
      {
        artifact_job_id: "job-latest",
        latest_version_no: 5,
        updated_at: "2026-07-07T11:00:00Z"
      }
    ];

    expect(resolveLatestArtifactVersionRequest(jobs)).toEqual({
      artifactJobId: "job-latest",
      versionNo: 5
    });
  });

  it("should return null when no completed artifact version exists", () => {
    expect(resolveLatestArtifactVersionRequest([
      {
        artifact_job_id: "job-running",
        latest_version_no: 0,
        updated_at: "2026-07-07T12:00:00Z"
      }
    ])).toBeNull();
  });

  it("should reuse the latest loaded version when the selected history item points to the same version", () => {
    const selectedVersion: ArtifactHistoryVersionDetail = {
      artifact_job_id: "job-1",
      version_no: 3
    };

    expect(shouldReuseLatestArtifactVersion(
      { artifactJobId: "job-1", versionNo: 3 },
      selectedVersion
    )).toBe(true);
  });

  it("should request a separate version when the selected history item differs from the latest loaded version", () => {
    const selectedVersion: ArtifactHistoryVersionDetail = {
      artifact_job_id: "job-1",
      version_no: 3
    };

    expect(shouldReuseLatestArtifactVersion(
      { artifactJobId: "job-2", versionNo: 1 },
      selectedVersion
    )).toBe(false);
  });
});
