import { formatDateTime } from "../../shared/util/datetime";
import { buildKnowledgeCitationLabel } from "../research/presentation";
import type { Workspace } from "../workspace/model";
import { formatWikiRelationType } from "./wikiUtils";
import type { WikiWorkbenchController } from "./useWikiWorkbenchController";

export type WikiWorkbenchProps = NonNullable<ReturnType<typeof buildWikiWorkbenchProps>>;

export function buildWikiWorkbenchProps(
  wiki: WikiWorkbenchController,
  workspace: Workspace | null,
  isBusy: boolean
) {
  return {
    isBusy,
    workspace,
    filters: wiki.filters,
    draft: wiki.draft,
    selection: {
      selectedWikiItemId: wiki.selectedWikiItemId,
      selectedWikiPage: wiki.selectedWikiPage,
      selectedWikiDetail: wiki.selectedWikiDetail,
      selectedWikiVersions: wiki.selectedWikiVersions,
      selectedWikiVersionDetail: wiki.selectedWikiVersionDetail,
      selectedWikiLog: wiki.selectedWikiLog,
      selectedWikiIssues: wiki.selectedWikiIssues
    },
    data: {
      wikiHome: wiki.wikiHome,
      wikiIndex: wiki.wikiIndex,
      wikiStats: wiki.wikiStats,
      wikiLog: wiki.wikiLog,
      wikiGraph: wiki.wikiGraph,
      wikiRebuildAdvice: wiki.wikiRebuildAdvice,
      wikiIssues: wiki.wikiIssues,
      filteredWikiIssues: wiki.filteredWikiIssues
    },
    actions: {
      refreshWikiFromServer: wiki.refreshWikiFromServer,
      openWikiIndex: wiki.openWikiIndex,
      selectWikiPage: wiki.selectWikiPage,
      createWikiPage: wiki.createWikiPage,
      appendWikiVersion: wiki.appendWikiVersion,
      renameSelectedWikiPage: wiki.renameSelectedWikiPage,
      deleteSelectedWikiPage: wiki.deleteSelectedWikiPage,
      rebuildWikiLinks: wiki.rebuildWikiLinks,
      autoFixWiki: wiki.autoFixWiki,
      clearWikiRepairDraft: wiki.clearWikiRepairDraft,
      prepareWikiLinkRepair: wiki.prepareWikiLinkRepair,
      openWikiPageById: wiki.openWikiPageById,
      loadWikiVersion: wiki.loadWikiVersion,
      restoreLatestWikiVersion: wiki.restoreLatestWikiVersion,
      switchWikiGraphMode: wiki.switchWikiGraphMode,
      toggleWikiGraphKind: wiki.toggleWikiGraphKind,
      resetWikiGraphKinds: wiki.resetWikiGraphKinds,
      getRecentSourceAction: wiki.getRecentSourceAction,
      focusWikiIssue: wiki.focusWikiIssue,
      openWikiGraphPage: wiki.openWikiGraphPage
    },
    helpers: {
      formatDateTime,
      formatWikiRelationType,
      buildKnowledgeCitationLabel,
      getWikiIssuePrimaryAction: wiki.getWikiIssuePrimaryAction,
      renderWikiTaskCard: wiki.renderWikiTaskCard,
      renderWikiRecentUpdateCard: wiki.renderWikiRecentUpdateCard
    },
    derived: {
      availableWikiKinds: wiki.availableWikiKinds,
      groupedWikiPages: wiki.groupedWikiPages,
      visibleWikiPages: wiki.visibleWikiPages,
      autoFixableIssues: wiki.autoFixableIssues,
      reviewRequiredIssues: wiki.reviewRequiredIssues,
      graphFilterLabel: wiki.graphFilterLabel,
      graphSearchHits: wiki.graphSearchHits,
      wikiAdviceAction: wiki.wikiAdviceAction,
      wikiIssueTypes: wiki.wikiIssueTypes,
      wikiIssueSeverities: wiki.wikiIssueSeverities
    }
  };
}
