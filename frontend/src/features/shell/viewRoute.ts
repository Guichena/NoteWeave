export type AppView = "chat" | "wiki" | "memory" | "research";

export type ParsedAppLocation = {
  view: AppView;
  path: string;
};

export function pathForView(view: AppView, wikiPath?: string | null): string {
  switch (view) {
    case "research":
      return "/research";
    case "memory":
      return "/memory/reviews";
    case "wiki":
      return wikiPath && wikiPath.startsWith("/") ? wikiPath : "/wiki";
    case "chat":
    default:
      return "/";
  }
}

export function parseAppLocation(pathname: string): ParsedAppLocation {
  const path = pathname || "/";
  if (path === "/research" || path.startsWith("/research/")) {
    return { view: "research", path: "/research" };
  }
  if (path === "/memory/reviews" || path.startsWith("/memory/")) {
    return { view: "memory", path: "/memory/reviews" };
  }
  if (
    path === "/wiki"
    || path.startsWith("/wiki/")
    || path.includes("/wikis/")
    || /^\/workspaces\/[^/]+\/wiki(?:\/|$)/.test(path)
  ) {
    return { view: "wiki", path };
  }
  return { view: "chat", path: "/" };
}

export function navigateAppView(view: AppView, wikiPath?: string | null): string {
  const next = pathForView(view, wikiPath);
  if (typeof window !== "undefined" && window.location.pathname !== next) {
    window.history.pushState({}, "", next);
  }
  return next;
}
