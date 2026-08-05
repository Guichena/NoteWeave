export type BusyScope = "shell" | "chat" | "upload" | "artifact" | "research" | "wiki";

export type BusyMap = Partial<Record<BusyScope, boolean>>;

export function setBusyScope(current: BusyMap, scope: BusyScope, value: boolean): BusyMap {
  if (!value) {
    const next = { ...current };
    delete next[scope];
    return next;
  }
  return { ...current, [scope]: true };
}

export function isScopeBusy(busy: BusyMap, scope: BusyScope): boolean {
  return Boolean(busy[scope]);
}

export function isAnyBusy(busy: BusyMap): boolean {
  return Object.values(busy).some(Boolean);
}
