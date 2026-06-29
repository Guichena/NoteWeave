#!/bin/bash
# userPromptSubmit hook: cache the user's prompt to a temp file for the stop hook
echo "$( cat )" | python3 -c "
import sys, json
d = json.load(sys.stdin)
with open('/tmp/kiro_last_prompt.txt', 'w') as f:
    f.write(d.get('prompt', ''))
"
exit 0
