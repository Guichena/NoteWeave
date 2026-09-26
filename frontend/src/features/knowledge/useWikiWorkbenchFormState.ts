import { useState } from "react";
import type { WikiGraphMode } from "./model";

export function useWikiWorkbenchFormState() {
  const [wikiTitle, setWikiTitle] = useState("工作台知识页");
  const [wikiDraft, setWikiDraft] = useState("");
  const [wikiAppendDraft, setWikiAppendDraft] = useState("");
  const [wikiSearch, setWikiSearch] = useState("");
  const [wikiRenameTitle, setWikiRenameTitle] = useState("");
  const [wikiGraphMode, setWikiGraphMode] = useState<WikiGraphMode>("overview");
  const [wikiKindFilter, setWikiKindFilter] = useState("ALL");
  const [wikiGraphKindFilters, setWikiGraphKindFilters] = useState<string[]>([]);
  const [wikiGraphSearch, setWikiGraphSearch] = useState("");
  const [wikiIssueTypeFilter, setWikiIssueTypeFilter] = useState("ALL");
  const [wikiIssueScopeFilter, setWikiIssueScopeFilter] = useState<"ALL" | "AUTO" | "MANUAL">("ALL");
  const [wikiIssueSeverityFilter, setWikiIssueSeverityFilter] = useState("ALL");
  const [wikiIssuePageFilter, setWikiIssuePageFilter] = useState<"ALL" | "CURRENT">("ALL");

  return {
    wikiTitle,
    setWikiTitle,
    wikiDraft,
    setWikiDraft,
    wikiAppendDraft,
    setWikiAppendDraft,
    wikiSearch,
    setWikiSearch,
    wikiRenameTitle,
    setWikiRenameTitle,
    wikiGraphMode,
    setWikiGraphMode,
    wikiKindFilter,
    setWikiKindFilter,
    wikiGraphKindFilters,
    setWikiGraphKindFilters,
    wikiGraphSearch,
    setWikiGraphSearch,
    wikiIssueTypeFilter,
    setWikiIssueTypeFilter,
    wikiIssueScopeFilter,
    setWikiIssueScopeFilter,
    wikiIssueSeverityFilter,
    setWikiIssueSeverityFilter,
    wikiIssuePageFilter,
    setWikiIssuePageFilter
  };
}
