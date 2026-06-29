#!/bin/bash
# agentSpawn hook: calls search_memories via the agentcore-memory MCP server
# to load user preferences and context at the start of every conversation

RESULT=$(python3 - << 'PYEOF'
import sys, json, subprocess, os

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

init_req = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "hook", "version": "1.0"}
    }
})

tool_req = json.dumps({
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
        "name": "search_memories",
        "arguments": {
            "query": "user preferences and settings",
            "max_results": 10
        }
    }
})

try:
    proc = subprocess.Popen(
        [mcp_python, mcp_server],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env
    )
    stdout, stderr = proc.communicate(input=(init_req + "\n" + tool_req + "\n").encode(), timeout=25)

    # Parse the MCP responses — look for the tool call result (id: 2)
    for line in stdout.decode().strip().split("\n"):
        line = line.strip()
        if not line:
            continue
        try:
            resp = json.loads(line)
            if resp.get("id") == 2 and "result" in resp:
                content_parts = resp["result"].get("content", [])
                for part in content_parts:
                    if part.get("type") == "text":
                        data = json.loads(part["text"])
                        summary = data.get("context_summary", "")
                        if summary and summary != "No memory content found" and summary != "No conversation content found":
                            print(summary)
                            sys.exit(0)
        except (json.JSONDecodeError, KeyError, TypeError):
            continue

    # No meaningful preferences found
    print("")
except Exception as e:
    print(f"Hook error: {e}", file=sys.stderr)
    print("")
PYEOF
)

# Only emit the agent instruction if we actually got preferences back
if [ -n "$RESULT" ]; then
    echo "AGENT_INIT: The following user preferences and context were loaded from memory. Use them to personalize your responses:"
    echo "$RESULT"
else
    echo "AGENT_INIT: No stored user preferences found. Proceed normally."
fi
