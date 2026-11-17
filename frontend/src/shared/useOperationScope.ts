import { useCallback, useEffect, useRef } from "react";

/** 异步操作只可更新发起时的作用域；切走后再切回也不能接收旧操作结果。 */
export function useOperationScope(key: string) {
  const scopeRef = useRef({ key, active: true });
  if (scopeRef.current.key !== key) scopeRef.current = { key, active: true };
  const scope = scopeRef.current;
  useEffect(() => {
    scope.active = true;
    return () => { scope.active = false; };
  }, [scope]);
  return useCallback(() => scopeRef.current === scope && scope.active, [scope]);
}
