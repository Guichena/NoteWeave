package com.noteweave.workspace;

import jakarta.validation.constraints.NotNull;

/**
 * 知识库开关是唯一字段，必须显式给出。
 * 此前用基本类型 boolean，请求里没有 wiki_enabled（字段拼错或多传别的字段）时默认成 false，知识库被静默关闭。
 */
public record UpdateWorkspaceWikiSettingsRequest(@NotNull(message = "wiki_enabled 不能为空") Boolean wikiEnabled) {
}
