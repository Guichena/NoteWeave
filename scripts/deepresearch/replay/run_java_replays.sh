#!/bin/sh
set -eu
: "${NOTEWEAVE_REPLAY_ROOT:?需要项目目录}"
: "${NOTEWEAVE_REPLAY_OUT:?需要先生成 Python 回放材料}"
: "${NOTEWEAVE_REPLAY_BACKEND_CLASSES:?需要已编译的 Java 主代码}"
: "${NOTEWEAVE_REPLAY_CLASSPATH_FILE:?需要后端依赖 classpath 文件}"
replay_classpath="$NOTEWEAVE_REPLAY_BACKEND_CLASSES:$(cat "$NOTEWEAVE_REPLAY_CLASSPATH_FILE")"
replay_classes="$NOTEWEAVE_REPLAY_OUT/java-classes"
mkdir -p "$replay_classes"
javac -encoding UTF-8 -cp "$replay_classpath" -d "$replay_classes" "$NOTEWEAVE_REPLAY_ROOT"/scripts/deepresearch/replay/*.java
for replay_driver in RequirementCoverageReplay SourceScopeReplay SynthesisUploadReplay StatementSupportReplay CompletionDesignV4Replay CompletionRuntimeV5Replay; do
    if [ -n "${NOTEWEAVE_REPLAY_ONLY:-}" ] && [ "$NOTEWEAVE_REPLAY_ONLY" != "$replay_driver" ]; then continue; fi
    java -cp "$replay_classes:$replay_classpath" "com.noteweave.research.$replay_driver"
done
