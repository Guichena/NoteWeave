import { MarkdownSurface } from "../../../shared/ui/MarkdownSurface";
import { ArtifactSlidePreview } from "../ArtifactSlidePreview";
import type { ArtifactsApi } from "../api";
import type { ArtifactFileMetadata } from "../model";

export type DeckViewProps = {
  workspaceId: string;
  artifactJobId: string;
  versionNo: number;
  files: ArtifactFileMetadata[];
  markdown: string;
  api: Pick<ArtifactsApi, "downloadFile">;
};

/** DECK 沿用已鉴权的文件下载和逐页预览组件，不绑定具体 Skill 名称。 */
export function DeckView(props: DeckViewProps) {
  const hasPreview = props.files.some((file) => file.file_format === "PNG" && file.status === "READY");
  return (
    <section className="artifact-kind-view artifact-deck-view" aria-label="幻灯片阅读视图">
      {hasPreview ? <ArtifactSlidePreview {...props} /> : <p role="status">预览图片暂未就绪，可以先阅读文字内容。</p>}
      <MarkdownSurface className="artifact-reader-markdown" content={props.markdown} />
    </section>
  );
}
