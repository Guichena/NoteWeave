#!/usr/bin/env python3
"""
Setup script for AgentCore Memory resource and credentials.
This script helps you create and configure an AgentCore Memory resource.
"""

import asyncio
import boto3
import json
import os
from datetime import datetime
from bedrock_agentcore.memory.client import MemoryClient
from bedrock_agentcore.memory.controlplane import MemoryControlPlaneClient


def check_aws_credentials():
    """Check if AWS credentials are configured."""
    try:
        session = boto3.Session()
        credentials = session.get_credentials()
        
        if credentials is None:
            print("❌ No AWS credentials found")
            print("Please configure AWS credentials using one of these methods:")
            print("1. AWS CLI: aws configure")
            print("2. Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY")
            print("3. IAM roles (if running on EC2)")
            print("4. AWS profiles: aws configure --profile <profile-name>")
            return False
        
        print("✅ AWS credentials found")
        print(f"   Access Key ID: {credentials.access_key[:8]}...")
        print(f"   Region: {session.region_name or 'Not set (will use us-east-1)'}")
        return True
        
    except Exception as e:
        print(f"❌ Error checking AWS credentials: {e}")
        return False


def check_bedrock_access(region_name="us-east-1"):
    """Check if we have access to Bedrock AgentCore services."""
    try:
        # Test control plane access
        control_client = boto3.client("bedrock-agentcore-control", region_name=region_name)
        control_client.list_memories(maxResults=1)
        print("✅ Bedrock AgentCore Control Plane access confirmed")
        
        # Test data plane access
        data_client = boto3.client("bedrock-agentcore", region_name=region_name)
        print("✅ Bedrock AgentCore Data Plane access confirmed")
        
        return True
        
    except Exception as e:
        print(f"❌ Bedrock AgentCore access error: {e}")
        print("Please ensure you have the required permissions:")
        print("- bedrock-agentcore-control:* (for memory management)")
        print("- bedrock-agentcore:* (for memory operations)")
        return False


def create_memory_resource(name="AgentCoreMemoryMCP", region_name="us-east-1"):
    """Create an AgentCore Memory resource with semantic strategy."""
    try:
        # Ensure name follows AWS naming pattern: [a-zA-Z][a-zA-Z0-9_]{0,47}
        safe_name = name.replace("-", "_").replace(" ", "_")
        if not safe_name[0].isalpha():
            safe_name = "A" + safe_name
        safe_name = safe_name[:48]  # Limit to 48 characters
        
        print(f"🔧 Creating AgentCore Memory resource: {safe_name}")
        
        # Initialize memory control plane client
        control_client = MemoryControlPlaneClient(region_name=region_name)
        
        # Define semantic strategy with namespace pattern
        # Namespace: /strategy/{memoryStrategyId}/actor/{actorId}/session/{sessionId}
        semantic_strategy = {
            "semanticMemoryStrategy": {
                "name": "semanticMemoryStrategy",
                "description": "Semantic memory strategy for conversation storage and retrieval",
                "namespaces": [
                    "/strategy/semanticMemoryStrategy/actor/{actorId}/session/{sessionId}"
                ]
            }
        }
        
        # Create memory resource
        memory = control_client.create_memory(
            name=safe_name,
            description="Memory resource for MCP server integration with semantic strategy",
            event_expiry_days=90,
            strategies=[semantic_strategy],
            wait_for_active=True,
            max_wait=300
        )
        
        memory_id = memory.get("id")
        print(f"✅ Memory resource created successfully!")
        print(f"   Memory ID: {memory_id}")
        print(f"   Status: {memory.get('status')}")
        print(f"   Region: {region_name}")
        print(f"   Strategy: semanticMemoryStrategy")
        print(f"   Namespace: /strategy/semanticMemoryStrategy/actor/{{actorId}}/session/{{sessionId}}")
        
        return memory_id
        
    except Exception as e:
        print(f"❌ Failed to create memory resource: {e}")
        return None


def test_memory_operations(memory_id, region_name="us-east-1"):
    """Test basic memory operations."""
    try:
        print(f"🧪 Testing memory operations with memory ID: {memory_id}")
        
        # Initialize memory client
        memory_client = MemoryClient(region_name=region_name)
        
        # Test creating an event
        test_event = memory_client.create_event(
            memory_id=memory_id,
            actor_id="test-user",
            session_id="test-session",
            messages=[
                ("Hello, I'm testing the memory system", "USER"),
                ("Great! The memory system is working correctly", "ASSISTANT")
            ]
        )
        
        event_id = test_event.get("eventId")
        print(f"✅ Test event created: {event_id}")
        
        # Test retrieving the event
        retrieved_event = memory_client.get_event(
            memoryId=memory_id,
            actorId="test-user",
            sessionId="test-session",
            eventId=event_id
        )
        
        print(f"✅ Event retrieval successful: {retrieved_event}")
        
        # Clean up test event
        memory_client.delete_event(
            memoryId=memory_id,
            actorId="test-user",
            sessionId="test-session",
            eventId=event_id
        )
        
        print("✅ Test event cleaned up")
        print("✅ All memory operations working correctly!")
        
        return True
        
    except Exception as e:
        print(f"❌ Memory operations test failed: {e}")
        return False


def generate_mcp_config(memory_id, region_name="us-east-1"):
    """Generate Kiro Agent configuration for kiro-cli."""
    current_dir = os.getcwd()
    
    print("\n📋 Actor ID Configuration")
    print("Choose how to identify the actor (user/project):")
    print("1. userid - Use system username (from USER environment variable)")
    print("2. projectid - Use a specific project ID")
    
    actor_choice = input("Enter choice [1/2] (default: 1): ").strip() or "1"
    
    if actor_choice == "2":
        actor_id_type = "projectid"
        project_id = input("Enter your project ID: ").strip()
        if not project_id:
            print("❌ Project ID is required when using projectid type")
            return
    else:
        actor_id_type = "userid"
        project_id = ""
        print(f"✅ Will use system username: {os.getenv('USER') or os.getenv('USERNAME') or 'default-user'}")
    
    agent_config = {
        "name": "kiro_memory",
        "description": "Kiro Agent with Memory Capabilities",
        "prompt": "You are Kiro, an AI assistant with memory capabilities. You can store and retrieve information across conversations using the AgentCore Memory MCP server. Use memory tools to remember important context, user preferences, project details, and prior decisions. When relevant, proactively recall stored memories to provide more personalized and context-aware assistance. Always leverage your memory capabilities to maintain continuity across interactions.\n\nIMPORTANT BEHAVIORS:\n1. At the START of every conversation, automatically call search_memories with a broad query to load any relevant context about the user's preferences and prior decisions.\n2. At the END of every conversation (when the user says goodbye, thanks, or the conversation naturally concludes), automatically call store_conversation to persist the key points discussed.",
        "mcpServers": {
            "agentcore-memory-mcp-server": {
            "command": f"{current_dir}/venv/bin/python",
            "args": [
                f"{current_dir}/bedrock_agentcore_memory_mcp_server.py"
            ],
            "env": {
                "AGENTCORE_MEMORY_ID": memory_id,
                "FASTMCP_LOG_LEVEL": "DEBUG",
                "PROJECT_ID": project_id,
                "FASTMCP_LOG_FILE": f"{current_dir}/mcp-server.log",
                "ACTOR_ID_TYPE": actor_id_type,
                "AWS_REGION": region_name,
                "REPO_DIR": f"{current_dir}"
            }
            }
        },
        "tools": [
            "*"
        ],
        "toolAliases": {},
        "allowedTools": [],
        "resources": [],
        "hooks": {
            "agentSpawn": [
            {
                "command": "~/.kiro/hooks/load-preferences.sh",
                "description": "Load user preferences from AgentCore Memory on agent init"
            }
            ],
            "userPromptSubmit": [
            {
                "command": "~/.kiro/hooks/cache-prompt.sh",
                "description": "Cache user prompt for stop hook"
            }
            ],
            "stop": [
            {
                "command": "~/.kiro/hooks/store-conversation.sh",
                "description": "Auto-store conversation via agentcore-memory MCP server",
                "timeout_ms": 25000
            }
            ]
        },
        "toolsSettings": {},
        "includeMcpJson": False,
        "model": None
        }
    
    try:
        os.mkdir('agent')
    except:
        print("agent directory already existing")
        pass

    with open("agent/kiro_memory.json", "w") as f:
        json.dump(agent_config, f, indent=2)
    
    print(f"\n✅ Kiro Agent configuration created: agent/kiro_memory.json")
    print(f"   Actor ID Type: {actor_id_type}")
    if actor_id_type == "projectid":
        print(f"   Project ID: {project_id}")
    print("\n Add this configuration to your kiro settings directory (~/.kiro/agents/kiro_memory.json)")


async def main():
    """Main setup function."""
    print("🚀 AgentCore Memory MCP Server Setup")
    print("=" * 50)
    
    # Step 1: Check AWS credentials
    print("\n1. Checking AWS Credentials...")
    if not check_aws_credentials():
        return
    
    # Step 2: Check Bedrock access
    print("\n2. Checking Bedrock AgentCore Access...")
    region = input("Enter AWS region (default: us-east-1): ").strip() or "us-east-1"
    
    if not check_bedrock_access(region):
        return
    
    # Step 3: Create or use existing memory resource
    print("\n3. Memory Resource Setup...")
    choice = input("Do you want to (c)reate a new memory resource or (u)se existing? [c/u]: ").lower()
    
    if choice == 'c':
        memory_name = input("Enter memory resource name (default: AgentCoreMemoryMCP): ").strip()
        memory_name = memory_name or "AgentCoreMemoryMCP"
        
        memory_id = create_memory_resource(memory_name, region)
        if not memory_id:
            return
    else:
        memory_id = input("Enter existing memory resource ID: ").strip()
        if not memory_id:
            print("❌ Memory ID is required")
            return
    
    # Step 4: Test memory operations
    print("\n4. Testing Memory Operations...")
    if not test_memory_operations(memory_id, region):
        return
    
    # Step 5: Generate configuration files
    print("\n5. Generating Configuration Files...")
    generate_mcp_config(memory_id, region)
    
    # Step 6: Final instructions
    print("\n🎉 Setup Complete!")
    print("=" * 50)
    print(f"Memory ID: {memory_id}")
    print(f"Region: {region}")

if __name__ == "__main__":
    asyncio.run(main())
