import { MarkdownSurface } from "../../../shared/ui/MarkdownSurface";
import { CardsView } from "./CardsView";
import { DeckView, type DeckViewProps } from "./DeckView";
import { QuizView } from "./QuizView";
import { TableView } from "./TableView";
import { readInteractiveIr } from "./ir";
import "./artifactViews.css";

type ArtifactKindViewProps = DeckViewProps & { kind: string; ir: unknown };

export function ArtifactKindView({ kind, ir, ...props }: ArtifactKindViewProps) {
  if (kind === "DECK") return <DeckView {...props} />;
  const content = readInteractiveIr(ir, kind);
  if (content?.kind === "QUIZ") return <QuizView ir={content} />;
  if (content?.kind === "CARDS") return <CardsView ir={content} />;
  if (content?.kind === "TABLE") return <TableView ir={content} />;
  return <MarkdownSurface className="artifact-reader-markdown" content={props.markdown} />;
}
