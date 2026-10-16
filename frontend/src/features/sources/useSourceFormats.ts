import { useEffect, useState } from "react";
import { sourcesApi, type SourcesApi } from "./api";
import {
  buildSourceAccept,
  cacheSourceFormats,
  cachedSourceFormats,
  sourceFormatNames,
  validateSourceFile,
  type SourceFormat
} from "./sourceFiles";

/** 读取后端支持的资料格式；接口不可用时使用内置列表。 */
export function useSourceFormats(workspaceId: string | undefined, api: Pick<SourcesApi, "listFormats"> = sourcesApi) {
  const [formats, setFormats] = useState<SourceFormat[]>(() => cachedSourceFormats(workspaceId ?? ""));

  useEffect(() => {
    if (!workspaceId) return undefined;
    let active = true;
    setFormats(cachedSourceFormats(workspaceId));
    api.listFormats().then(
      (loaded) => {
        const valid = loaded.filter((format) => format.extension && format.mime_type)
          .map((format) => ({ extension: format.extension.replace(/^\./, "").toLowerCase(), mime_type: format.mime_type }));
        if (!active || valid.length === 0) return;
        cacheSourceFormats(workspaceId, valid);
        setFormats(valid);
      },
      () => undefined
    );
    return () => {
      active = false;
    };
  }, [api, workspaceId]);

  return {
    formats,
    accept: buildSourceAccept(formats),
    names: sourceFormatNames(formats),
    validate: (file: File) => validateSourceFile(file, formats)
  };
}
