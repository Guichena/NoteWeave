// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ArtifactsApi } from "./api";
import { VideoLearningPanel } from "./VideoLearningPanel";
import type { CreateVideoLearningInput, VideoLearningRequest } from "./model";

const parent: VideoLearningRequest = {
  request_id: "parent-1", material_state: "QUEUED", material_task_id: "task-1",
  material_bundle_id: null, knowledge_plan_id: null, cancellation_requested: false,
  video_url: "https://www.bilibili.com/video/BV1234567890?p=2", part: 2,
  choices: [
    { skill_key: "knowledge_blog", artifact_job_id: null, status: "NOT_STARTED", task_id: null, latest_version_no: 0 },
    { skill_key: "interview_qa", artifact_job_id: null, status: "NOT_STARTED", task_id: null, latest_version_no: 0 }
  ]
};

describe("VideoLearningPanel", () => {
  afterEach(() => cleanup());
  it("hides creation when the Host disables rollout and no parent exists", async () => {
    const api = { listVideoLearning: vi.fn(async () => ({ enabled: false, requests: [] })) };
    const { container } = render(<VideoLearningPanel workspaceId="workspace" api={api as unknown as ArtifactsApi} />);
    await waitFor(() => expect(api.listVideoLearning).toHaveBeenCalled());
    expect(container.querySelector(".video-learning-panel")).toBeNull();
  });

  it("creates selected independent outputs from one material request", async () => {
    const api = {
      listVideoLearning: vi.fn(async () => ({ enabled: true, requests: [] })),
      createVideoLearning: vi.fn(async (_workspaceId: string, _input: CreateVideoLearningInput) => parent)
    };
    render(<VideoLearningPanel workspaceId="workspace" api={api as unknown as ArtifactsApi} />);
    fireEvent.click(await screen.findByRole("button", { name: /一个视频，生成多种产物/ }));
    fireEvent.change(screen.getByLabelText("B站视频链接"), {
      target: { value: "https://www.bilibili.com/video/BV1234567890?p=2" }
    });
    fireEvent.change(screen.getByLabelText("分集"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("学习重点"), { target: { value: "理解关键概念" } });
    fireEvent.click(screen.getByLabelText(/面试问答/));
    fireEvent.click(screen.getByRole("button", { name: "创建 2 种产物" }));
    await waitFor(() => expect(api.createVideoLearning).toHaveBeenCalledOnce());
    expect(api.createVideoLearning.mock.calls[0][1]).toMatchObject({
      part: 2, selected_skills: ["knowledge_blog", "interview_qa"],
      user_requirement: "理解关键概念"
    });
    expect(await screen.findByText("BV1234567890 · 第 2 集")).toBeTruthy();
    expect(screen.getByText("资料 排队中")).toBeTruthy();
  });

  it("keeps accepted parents visible after creation is disabled", async () => {
    const api = {
      listVideoLearning: vi.fn(async () => ({ enabled: false, requests: [parent] })),
      cancelVideoLearning: vi.fn(async () => ({ ...parent, cancellation_requested: true,
        material_state: "CANCELLED" }))
    };
    render(<VideoLearningPanel workspaceId="workspace" api={api as unknown as ArtifactsApi} />);
    expect(await screen.findByText("BV1234567890 · 第 2 集")).toBeTruthy();
    expect(screen.queryByText("一个视频，生成多种产物")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "取消未开始任务" }));
    await waitFor(() => expect(api.cancelVideoLearning).toHaveBeenCalledWith("workspace", "parent-1"));
    expect(await screen.findByText("资料 已取消")).toBeTruthy();
  });

  it("disables a blocked output version while keeping other choices available", async () => {
    const api = {
      listVideoLearning: vi.fn(async () => ({ enabled: true,
        available_skills: ["interview_qa"], requests: [] })),
      createVideoLearning: vi.fn(async (_workspaceId: string, _input: CreateVideoLearningInput) => parent)
    };
    render(<VideoLearningPanel workspaceId="workspace" api={api as unknown as ArtifactsApi} />);
    fireEvent.click(await screen.findByRole("button", { name: /一个视频，生成多种产物/ }));
    expect(screen.getByLabelText(/知识博客/)).toHaveProperty("disabled", true);
    fireEvent.change(screen.getByLabelText("B站视频链接"), {
      target: { value: "https://www.bilibili.com/video/BV1234567890" }
    });
    fireEvent.change(screen.getByLabelText("学习重点"), { target: { value: "练习问答" } });
    fireEvent.click(screen.getByLabelText(/面试问答/));
    fireEvent.click(screen.getByRole("button", { name: "创建 1 种产物" }));
    await waitFor(() => expect(api.createVideoLearning).toHaveBeenCalledOnce());
    expect(api.createVideoLearning.mock.calls[0][1].selected_skills).toEqual(["interview_qa"]);
  });

  it("opens the matching ready child version directly", async () => {
    const onOpenVersion = vi.fn();
    const ready = { ...parent, material_state: "READY", choices: [
      { skill_key: "video_learning_deck", artifact_job_id: "deck-job", status: "COMPLETED",
        task_id: "deck-task", latest_version_no: 2 }
    ] };
    const api = { listVideoLearning: vi.fn(async () => ({ enabled: false, requests: [ready] })) };
    render(<VideoLearningPanel workspaceId="workspace" onOpenVersion={onOpenVersion}
      api={api as unknown as ArtifactsApi} />);
    fireEvent.click(await screen.findByRole("button", { name: "查看 v2" }));
    expect(onOpenVersion).toHaveBeenCalledWith("deck-job", 2);
  });
});
