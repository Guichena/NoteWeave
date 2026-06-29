#!/bin/bash
# Stop hook: calls store_conversation via the agentcore-memory MCP server
EVENT=$(cat)

ASSISTANT_RESPONSE=$(echo "$EVENT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('assistant_response',''))" 2>/dev/null)
[ -z "$ASSISTANT_RESPONSE" ] && exit 0

USER_QUESTION=""
[ -f /tmp/kiro_last_prompt.txt ] && USER_QUESTION=$(cat /tmp/kiro_last_prompt.txt)
[ -z "$USER_QUESTION" ] && USER_QUESTION="(unknown)"

python3 - "$USER_QUESTION" "$ASSISTANT_RESPONSE" << 'PYEOF'
import sys, json, subprocess, os

user_question, assistant_response = sys.argv[1], sys.argv[2][:4000]
with open(os.path.expanduser('~/.kiro/agents/kiro_memory.json')) as f:
    config = json.load(f)
mcp_python = config['mcpServers']['agentcore-memory-mcp-server']['command']
mcp_server = config['mcpServers']['agentcore-memory-mcp-server']['args'][0]

env = os.environ.copy()
env.update({
    "AGENTCORE_MEMORY_ID": config['mcpServers']['agentcore-memory-mcp-server']['env']['AGENTCORE_MEMORY_ID'],
    "FASTMCP_LOG_LEVEL": config['mcpServers']['agentcore-memory-mcp-server']['env']['FASTMCP_LOG_LEVEL'],
    "ACTOR_ID_TYPE": config['mcpServers']['agentcore-memory-mcp-server']['env']['ACTOR_ID_TYPE'],
    "PROJECT_ID": config['mcpServers']['agentcore-memory-mcp-server']['env']['PROJECT_ID'],
    "AWS_REGION": config['mcpServers']['agentcore-memory-mcp-server']['env']['AWS_REGION']
})

init_req = json.dumps({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"hook","version":"1.0"}}})
tool_req = json.dumps({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"store_conversation","arguments":{"user_question":user_question,"assistant_response":assistant_response}}})

try:
    proc = subprocess.Popen([mcp_python, mcp_server], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    proc.communicate(input=(init_req+"\n"+tool_req+"\n").encode(), timeout=25)
except Exception as e:
    print(f"Hook error: {e}", file=sys.stderr)
    sys.exit(1)
PYEOF
