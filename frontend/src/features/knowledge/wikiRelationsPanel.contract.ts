import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";

export type WikiRelationsPanelProps = Pick<WikiWorkbenchProps, "isBusy" | "workspace">
  & WikiWorkbenchProps["filters"]
  & WikiWorkbenchProps["draft"]
  & WikiWorkbenchProps["selection"]
  & WikiWorkbenchProps["data"]
  & WikiWorkbenchProps["actions"]
  & WikiWorkbenchProps["helpers"]
  & WikiWorkbenchProps["derived"]
  & {
    onCloseRelations?: () => void;
  };
