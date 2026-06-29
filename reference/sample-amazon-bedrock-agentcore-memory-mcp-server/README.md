
# Extending conversational memory in Kiro CLI using Amazon Bedrock AgentCore memory

## Agentcore Memory MCP Server

A Model Context Protocol (MCP) server that exposes Amazon Bedrock AgentCore Memory as MCP server which can be integrated with any other MCP client like Kiro, providing automatic conversation storage and retrieval.

## What it does

The AgentCore Memory MCP Server stores your kiro-cli conversations in Amazon Bedrock AgentCore Memory, providing persistent context across sessions.

## Prerequisites

- To be updated
- Python 3.13 or higher
- AWS account with Amazon Bedrock access
- Valid AWS credentials configured
- kiro-cli installed

## Quick start

### Step 1: Install dependencies

Run the following commands to create a virtual environment and install the dependencies:

```bash
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip3 install -r requirements.txt
```

### Step 2: Create AgentCore Memory resource

Run the setup script to create your memory resource:

The MCP server supports flexible actor identification:

**Option 1: User ID (default)**
- Set `ACTOR_ID_TYPE=userid` in Agent/kiro_memory.json
- Uses your system username from the `USER` environment variable
- Best for personal use

**Option 2: Project ID**
- Set `ACTOR_ID_TYPE=projectid` in Agent/kiro_memory.json
- Set `PROJECT_ID=your-project-id` Agent/kiro_memory.json
- Best for team/project-based memory isolation

The namespace strategy used is: `/strategy/semanticMemoryStrategy/actor/{actorId}/session/{sessionId}`

```bash
python3 setup_bedrock_agentcore_memory.py
```

This interactive script will:

- Check AWS credentials and permissions
- Create an AgentCore Memory resource with semantic memory strategy
- Configure actor ID type (userid or projectid)
- Generate your memory resource ID
- Test the connection
- Create the kiro agent configuration

### Step 3: Configure kiro-cli
```bash
mkdir -p ~/.kiro/agents/
mkdir -p ~/.kiro/hooks/
cp -p agent/kiro_memory.json ~/.kiro/agents/
cp -p hooks/cache-prompt.sh ~/.kiro/hooks/
cp -p hooks/load-preferences.sh ~/.kiro/hooks/
cp -p hooks/store-conversation.sh ~/.kiro/hooks/
chmod 755 ~/.kiro/hooks/*
```

### Step 4: Configure kiro_memory agent as the default agent


Add the following to the `cli.json` file in `~/.kiro/settings/` directory to use `kiro_memory` agent by default:

`{"chat.defaultAgent": "kiro_memory"}`



### Step 5: Open kiro-cli

If already logged in to kiro-cli, first logout and the re-login.  

```bash
kiro-cli login --use-device-flow
kiro-cli --classic
/tools
```

## Configuration Examples

### Example 1: User ID Configuration (Personal Use)

```json
{
  "mcpServers": {
    "agentcore-memory-mcp-server": {
      "command": "/path/to/venv/bin/python",
      "args": ["/path/to/bedrock_agentcore_memory_mcp_server.py"],
      "env": {
        "AWS_REGION": "us-east-1",
        "AGENTCORE_MEMORY_ID": "your-memory-id",
        "ACTOR_ID_TYPE": "userid",
        "PROJECT_ID": "",
        "LOG_LEVEL": "INFO"
      }
    }
  }
}
```

### Example 2: Project ID Configuration (Team/Project Use)

```json
{
  "mcpServers": {
    "agentcore-memory-mcp-server": {
      "command": "/path/to/venv/bin/python",
      "args": ["/path/to/bedrock_agentcore_memory_mcp_server.py"],
      "env": {
        "AWS_REGION": "us-east-1",
        "AGENTCORE_MEMORY_ID": "your-memory-id",
       
        "LOG_LEVEL": "INFO"
      }
    }
  }
}
```

## Available tools

### Core Tools

- `search_conversation_history` - **Primary tool** for searching conversation history by topic or timeframe
  - Searches directly through stored session events
  - Fast and reliable - finds conversations immediately after storage
  - Returns complete conversation turns with full context
  - Includes `context_summary` field for easy integration with kiro's context
  - **Multi-word search support**: Searches for all words present in content, regardless of order
  
- `search_memories` - Semantic search through processed long-term memories
  - Uses semantic/vector search for conceptual matching via AWS retrieve_memory_records API
  - Searches through memories processed by the semantic strategy
  - Includes `context_summary` field with relevance scores
  - **Note**: There may be a delay between storing conversations and them appearing in semantic search
  
- `store_conversation` - Store conversations with consistent session IDs
  - Uses `MemoryClient.create_event()` to trigger semantic memory strategy
  - Automatically generates hourly session IDs for consistency
  
- `get_direct_conversation_history` - Access complete conversation content for specific sessions
- `list_sessions` - List previously stored sessions

**Note:** All tools support optional `actor_id` parameter. If not provided, the actor_id will be determined based on your `ACTOR_ID_TYPE` configuration.

### Diagnostic Tools

- `get_server_config` - Get complete MCP server configuration including memory_id, region, actor_id, and namespace patterns
- `get_session_details` - Get detailed information about a specific session including events and extracted memories

### Management Tools

- `delete_session` - Delete a specific session and all its events
  - Requires session_id parameter
  - Deletes all events within the session
  - Cannot be undone
  
- `get_memory_stats` - View comprehensive memory usage statistics
  - Shows total sessions, events, and long-term memories
  - Displays recent session details with event counts
  - Provides average events per session
  - Uses `list_memory_records` API to accurately count all extracted memories
  - Searches across multiple namespace patterns to ensure complete coverage
  
- `clear_all_data` - Clear all conversation data for an actor
  - **Destructive operation** - requires `confirm=True` parameter
  - Deletes ALL sessions and events for the actor
  - Cannot be undone
  - Note: Long-term memories extracted by semantic strategy remain in the memory resource

## Usage examples

### Search conversation history (Primary tool)

The `search_conversation_history` tool provides reliable conversation retrieval. Results include a `context_summary` field that kiro can use to understand previous discussions:

**Search Features:**
- **Single-word queries**: Searches for exact substring matches (e.g., "lambda")
- **Multi-word queries**: Searches for all words present in the content, regardless of order (e.g., "lambda python runtime" will match content containing "runtimes supported by lambda for python")
- **Context integration**: Returns `context_summary` with full conversation content for kiro's context

```bash
# Find conversations about Lambda Python runtime
"search my conversation history about lambda python runtime"

# Find conversations about EMR
"search my conversation history about EMR"

# Get all recent conversations
"show me my recent conversation history"

# Search with custom timeframe
"search my conversations about serverless from the last 7 days"
```

### Search semantic memories

The `search_memories` tool searches through semantically processed long-term memories. Results include a `context_summary` with relevance scores:

```bash
# Search for memories about Python
"search my long-term memory about Python functions"

# Search for memories about AWS services
"search my memory about AWS Lambda"
```

### Store conversations

Conversations can be stored manually by instructing kiro-cli:

```bash
"store this conversation in memory"
```

### Check server configuration

```bash
"show me the memory server configuration"
```

This returns complete configuration including memory_id, region, actor_id, and namespace patterns.

### Delete a specific session

```bash
"delete session cli_session_20260122_14 from memory"
```

This will delete all events in the specified session. The operation cannot be undone.

### View memory statistics

```bash
"show me my memory usage statistics"
```

This returns:
- Total number of sessions
- Total number of events (counted by actually listing events in each session)
- Number of long-term memories (counted using `list_memory_records` API across multiple namespace patterns)
- Average events per session
- Details of recent sessions with their event counts

The tool now provides accurate counts by:
- Actually listing events in each session rather than trusting cached counts
- Using `list_memory_records` API to enumerate all extracted memories
- Searching across multiple namespace patterns (actor-level, session-level) to ensure complete coverage

### Inspect a specific session

```bash
"get details for session cli_session_20260122_15"
```

This returns:
- Number of events in the session
- Details of each event (messages, timestamps)
- Number of long-term memories extracted from this session
- The actual memory content
- The exact namespace being used

### Clear all data (use with caution!)

```bash
"clear all my conversation data from memory with confirmation"
```

**Warning**: This is a destructive operation that:
- Deletes ALL sessions and events for your actor_id
- Cannot be undone
- Requires `confirm=True` parameter to execute
- Long-term memories extracted by the semantic strategy will remain but lose their source events

## Key Features

### Context Integration

Both `search_conversation_history` and `search_memories` return a `context_summary` field that provides:
- **For conversation history**: Full USER/ASSISTANT message exchanges
- **For semantic memories**: Extracted insights with relevance scores

This allows kiro to understand previous discussions and act on historical context automatically.

### Multi-word Search

The search tools support flexible multi-word queries:
- Searches for all words present in content, regardless of order
- Example: "lambda python runtime" matches content containing "runtimes supported by lambda for python"

### Actor ID Flexibility

All tools support optional `actor_id` parameter:
- If not provided, uses configured `ACTOR_ID_TYPE` (userid or projectid)
- Allows override for specific use cases
- Ensures proper namespace isolation

## License

This library is licensed under the MIT-0 License. See the LICENSE file.

## Troubleshooting

### Why isn't `search_memories` finding my conversations?

The semantic memory strategy processes conversations asynchronously:

1. **Storage**: `store_conversation` uses `MemoryClient.create_event()` to store and trigger strategy processing
2. **Processing**: The semantic strategy extracts insights into long-term memories (may take a few minutes)
3. **Search**: `search_memories` uses AWS `retrieve_memory_records` API with wildcard namespace matching

**If memories aren't appearing:**
- Wait a few minutes after storing for the strategy to process
- Use `search_conversation_history` for immediate access to stored conversations
- Check CloudWatch logs for the memory resource to verify extraction is occurring
- Verify the namespace pattern matches: `/strategy/semanticMemoryStrategy/actor/{actorId}/*`

**Memory Isolation:**
- `search_memories` only returns memories from your configured memory_id
- Results are scoped to your actor_id namespace
- The wildcard namespace pattern ensures all sessions are searched

### Configuration Issues

If conversations aren't being found:
1. Check your actor_id configuration with `get_server_config`
2. Verify `ACTOR_ID_TYPE` matches your setup (userid or projectid)
3. If using projectid, ensure `PROJECT_ID` is set correctly
4. Restart kiro-cli to reload the MCP server with updated environment variables

## Technical Details

### Storage Method

- Uses `MemoryClient.create_event()` instead of `session.add_turns()`
- Better triggers the semantic memory strategy for automatic processing
- Generates hourly session IDs for consistent grouping

### Search Implementation

- **Conversation History**: Direct session event search via AgentCore SDK
- **Semantic Memories**: AWS `retrieve_memory_records` API with namespace wildcards
- **Namespace Pattern**: `/strategy/semanticMemoryStrategy/actor/{actorId}/*`
- **Context Integration**: Both tools return `context_summary` for kiro's context window

### Actor ID Resolution

The `get_actor_id(actor_id_type)` function determines the actor_id:
- `userid`: Uses `USER` environment variable (system username)
- `projectid`: Uses `PROJECT_ID` environment variable
- All tools accept optional `actor_id` parameter to override


## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.

