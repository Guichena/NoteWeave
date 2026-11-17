#!/bin/sh
set -eu
: "${NOTEWEAVE_REPLAY_ROOT:?需要项目目录}"
: "${NOTEWEAVE_REPLAY_CLASSPATH_FILE:?需要后端依赖 classpath 文件}"
exec java -cp "$(cat "$NOTEWEAVE_REPLAY_CLASSPATH_FILE")" \
    "$NOTEWEAVE_REPLAY_ROOT/scripts/deepresearch/replay/MySqlCompletionMigrationReplay.java"
