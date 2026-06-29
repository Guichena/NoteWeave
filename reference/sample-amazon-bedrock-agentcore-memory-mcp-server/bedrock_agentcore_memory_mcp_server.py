#!/usr/bin/env python3
"""
AgentCore Memory MCP Server.
This MCP Server can be integrated with kiro-cli to store and retrieve conversations.

Main Tools:
- store_conversation: Store conversations with consistent session IDs
- get_direct_conversation_history: Access complete conversation content
- search_memories: Semantic search through stored conversations
- search_conversation_history: Retrieve previous conservations
- list_sessions: Retrieve previously stored sessions from the memory
"""

import asyncio
import logging
import os
import json
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any
from fastmcp import FastMCP

import threading
import time

# Load environment variables
from dotenv import load_dotenv
load_dotenv()

# AgentCore Memory imports
from bedrock_agentcore.memory.client import MemoryClient
from bedrock_agentcore.memory.session import MemorySessionManager
from bedrock_agentcore.memory.constants import ConversationalMessage, MessageRole

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Get environment configuration
DEFAULT_MEMORY_ID = os.getenv('AGENTCORE_MEMORY_ID')
DEFAULT_REGION = os.getenv('AWS_REGION', 'us-east-1')
ACTOR_ID_TYPE = os.getenv('ACTOR_ID_TYPE', 'userid')  # 'userid' or 'projectid'
PROJECT_ID = os.getenv('PROJECT_ID', '')  # Required if ACTOR_ID_TYPE is 'projectid'
MEMORY_STRATEGY_ID = 'semanticMemoryStrategy'  # The strategy used for namespace

logger.info(f"AgentCore Memory MCP Server - Environment configuration loaded:")
logger.info(f"  AGENTCORE_MEMORY_ID: {'✓ Set' if DEFAULT_MEMORY_ID else '✗ Not set'}")
logger.info(f"  AWS_REGION: {DEFAULT_REGION}")
logger.info(f"  ACTOR_ID_TYPE: {ACTOR_ID_TYPE}")
logger.info(f"  MEMORY_STRATEGY_ID: {MEMORY_STRATEGY_ID}")
if ACTOR_ID_TYPE == 'projectid':
    logger.info(f"  PROJECT_ID: {'✓ Set' if PROJECT_ID else '✗ Not set'}")


def get_actor_id(actor_id_type: str) -> str:
    """
    Get the actor_id based on actor_id_type parameter.
    
    Args:
        actor_id_type: Type of actor ID ('userid' or 'projectid')
    
    Returns:
        The resolved actor_id string
    
    Raises:
        ValueError: If projectid type is specified but PROJECT_ID env var is not set
    """
    logger.info(f"get_actor_id called with actor_id_type='{actor_id_type}'")
    logger.info(f"  Global ACTOR_ID_TYPE='{ACTOR_ID_TYPE}'")
    logger.info(f"  Global PROJECT_ID='{PROJECT_ID}'")
    
    if actor_id_type == 'projectid':
        if not PROJECT_ID:
            error_msg = "PROJECT_ID environment variable is required when actor_id_type is 'projectid'"
            logger.error(error_msg)
            raise ValueError(error_msg)
        logger.info(f"  Returning PROJECT_ID: '{PROJECT_ID}'")
        return PROJECT_ID
    else:  # Default to userid
        user_id = os.getenv('USER') or os.getenv('USERNAME') or 'default-user'
        logger.info(f"  Returning USER: '{user_id}'")
        return user_id


def get_namespace_path(actor_id: str, session_id: str = None) -> str:
    """
    Construct the namespace path for AgentCore Memory.
    
    The namespace follows the pattern:
    /strategy/{memoryStrategyId}/actor/{actorId}/session/{sessionId}
    
    Args:
        actor_id: The actor identifier
        session_id: Optional session identifier
    
    Returns:
        The complete namespace path
    """
    if session_id:
        return f"/strategy/{MEMORY_STRATEGY_ID}/actor/{actor_id}/session/{session_id}"
    else:
        return f"/strategy/{MEMORY_STRATEGY_ID}/actor/{actor_id}"


def query_matches_content(query: str, content: str) -> bool:
    """
    Check if a query matches content using flexible word matching.
    
    For multi-word queries, checks if all words are present in the content
    (not necessarily in order or adjacent).
    
    Args:
        query: The search query (can be single or multi-word)
        content: The content to search in
    
    Returns:
        True if the query matches the content, False otherwise
    """
    if not query or not content:
        return False
    
    query_lower = query.lower().strip()
    content_lower = content.lower()
    
    # Split query into words
    query_words = query_lower.split()
    
    # If single word, do simple substring match
    if len(query_words) == 1:
        return query_lower in content_lower
    
    # For multi-word queries, check if all words are present
    # This allows matching "lambda python runtime" with content containing
    # "runtimes supported by lambda for python"
    return all(word in content_lower for word in query_words)

# Create FastMCP app
app = FastMCP("AgentCore Memory MCP Server")

# Global variables for memory clients
memory_client: Optional[MemoryClient] = None
session_managers: Dict[str, MemorySessionManager] = {}



def get_session_manager(memory_id: str, region_name: str = None) -> MemorySessionManager:
    """Get or create a session manager for the given memory ID."""
    global session_managers
    
    # Use default region if not provided
    region_name = region_name or DEFAULT_REGION
    
    if memory_id not in session_managers:
        try:
            session_managers[memory_id] = MemorySessionManager(
                memory_id=memory_id,
                region_name=region_name
            )
            logger.info(f"Created MemorySessionManager for memory {memory_id}")
        except Exception as e:
            logger.error(f"Failed to create MemorySessionManager: {e}")
            raise
    
    return session_managers[memory_id]


def initialize_memory_client(region_name: str = None):
    """Initialize the AgentCore Memory client lazily."""
    global memory_client
    
    # Use default region if not provided
    region_name = region_name or DEFAULT_REGION
    
    try:
        if memory_client is None:
            memory_client = MemoryClient(region_name=region_name)
            logger.info(f"Initialized MemoryClient for region {region_name}")
        return True
    except Exception as e:
        logger.error(f"Failed to initialize MemoryClient: {e}")
        return False


async def get_recent_sessions(
    actor_id: str, 
    timeframe: str = "recent", 
    memory_id: Optional[str] = None, 
    region_name: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Get recent sessions based on timeframe.
    Timeframe can be: 'recent' (24h), 'yesterday', 'last_week', 'last_night', etc.
    """
    try:
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        # Calculate time range based on timeframe
        now = datetime.now()
        
        if timeframe in ["recent", "today"]:
            hours_back = 72  # Extended to 3 days for better coverage
        elif timeframe in ["yesterday", "last_night"]:
            hours_back = 96  # Look back 4 days to catch yesterday and day before
        elif timeframe == "last_week":
            hours_back = 168  # 7 days
        elif timeframe == "all":
            hours_back = 720  # 30 days for comprehensive search
        else:
            hours_back = 72  # Default to recent (3 days)
        
        # Generate possible session IDs for the timeframe
        session_ids = []
        for hour_offset in range(hours_back):
            target_time = now - timedelta(hours=hour_offset)
            session_id = f"cli_session_{target_time.strftime('%Y%m%d_%H')}"
            session_ids.append(session_id)
        
        # Check which sessions actually exist
        session_manager = get_session_manager(memory_id, region_name)
        existing_sessions = []
        
        for session_id in session_ids:
            try:
                # Try to create/access the session to see if it has content
                session = session_manager.create_memory_session(
                    actor_id=actor_id,
                    session_id=session_id
                )
                
                # Check if session has events
                events = session.list_events(max_results=1)
                if events:
                    # Parse the timestamp from session_id
                    timestamp_str = session_id.replace("cli_session_", "")
                    try:
                        session_time = datetime.strptime(timestamp_str, "%Y%m%d_%H")
                        existing_sessions.append({
                            "session_id": session_id,
                            "created_at": session_time.isoformat(),
                            "event_count": len(events),
                            "timeframe_match": timeframe
                        })
                    except ValueError:
                        # Fallback if timestamp parsing fails
                        existing_sessions.append({
                            "session_id": session_id,
                            "created_at": now.isoformat(),
                            "event_count": len(events),
                            "timeframe_match": timeframe
                        })
                        
            except Exception as e:
                # Session doesn't exist or has no content, skip
                continue
        
        # Sort by creation time (most recent first)
        existing_sessions.sort(key=lambda x: x["created_at"], reverse=True)
        
        logger.info(f"Found {len(existing_sessions)} sessions for timeframe '{timeframe}'")
        return existing_sessions
        
    except Exception as e:
        logger.error(f"Error getting recent sessions: {e}")
        return []


@app.tool
async def search_conversation_history(
    actor_id: Optional[str] = None,
    query: Optional[str] = None,
    days_back: Optional[int] = 30,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Generic tool to search conversation history with reliable direct session access.
    Works for any topic (EMR, Lambda, EC2, etc.) by searching through stored conversations.
    
    If actor_id is not provided, it will be determined based on ACTOR_ID_TYPE configuration:
    - 'userid': Uses the USER environment variable
    - 'projectid': Uses the PROJECT_ID environment variable
    """
    try:
        # Resolve actor_id: use provided value or get from configuration
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required",
                "message": "Please provide a valid AgentCore Memory resource ID"
            }
        
        logger.info(f"Searching conversation history for actor_id: {resolved_actor_id}")
        logger.info(f"Search parameters: actor_id='{resolved_actor_id}', memory_id='{memory_id}', region='{region_name}'")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Strategy 1: Try to list actual sessions for this actor
        actual_sessions = []
        try:
            logger.info(f"Attempting to list sessions for actor: {resolved_actor_id}")
            sessions = session_manager.list_actor_sessions(resolved_actor_id)
            if sessions:
                logger.info(f"Found {len(sessions)} existing sessions via list_actor_sessions")
                for session in sessions:
                    actual_sessions.append({
                        "session_id": session.get("sessionId"),
                        "created_at": session.get("createdAt"),
                        "last_updated": session.get("lastUpdatedAt"),
                        "event_count": session.get("eventCount", 0)
                    })
        except Exception as e:
            logger.warning(f"list_actor_sessions failed: {e}, falling back to time-based discovery")
        
        # Strategy 2: If no sessions found via listing, try time-based discovery
        if not actual_sessions:
            logger.info("No sessions found via listing, trying time-based session discovery")
            now = datetime.now()
            
            # Generate possible session IDs for the timeframe
            potential_session_ids = []
            max_days = min(days_back, 14)  # Limit to 14 days max
            
            for day_offset in range(max_days):
                target_date = now - timedelta(days=day_offset)
                # Check key hours to avoid timeout
                hours_to_check = [0, 6, 9, 12, 15, 18, 19, 20, 21, 22, 23] if day_offset < 3 else [9, 12, 18, 21]
                
                for hour in hours_to_check:
                    session_time = target_date.replace(hour=hour, minute=0, second=0, microsecond=0)
                    session_id = f"cli_session_{session_time.strftime('%Y%m%d_%H')}"
                    potential_session_ids.append(session_id)
            
            logger.info(f"Generated {len(potential_session_ids)} potential session IDs to check")
            
            # Check which sessions actually exist
            for session_id in potential_session_ids[:50]:  # Limit to first 50 to avoid timeout
                try:
                    session = session_manager.create_memory_session(
                        actor_id=resolved_actor_id,
                        session_id=session_id
                    )
                    events = session.list_events(max_results=1)
                    if events:
                        actual_sessions.append({
                            "session_id": session_id,
                            "event_count": len(events)
                        })
                except Exception:
                    continue
        
        logger.info(f"Found {len(actual_sessions)} actual sessions for actor {resolved_actor_id}")
        
        # If no sessions found at all, return early
        if not actual_sessions:
            return {
                "success": True,
                "conversations": [],
                "total_found": 0,
                "sessions_checked": 0,
                "sessions_with_content": 0,
                "query": query,
                "actor_id": resolved_actor_id,
                "namespace": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}",
                "message": f"No sessions found for actor '{resolved_actor_id}'. No conversations have been stored yet.",
                "suggestions": [
                    "Store conversations using the store_conversation tool",
                    "Verify the correct actor_id is being used with get_resolved_actor_id"
                ]
            }
        
        # Now search through the actual sessions
        all_found_conversations = []
        sessions_checked = 0
        sessions_with_matches = 0
        
        for session_info in actual_sessions:
            session_id = session_info["session_id"]
            sessions_checked += 1
            
            try:
                # Get session and events
                session = session_manager.create_memory_session(
                    actor_id=resolved_actor_id,
                    session_id=session_id
                )
                
                events = session.list_events(max_results=20)
                
                if not events:
                    continue
                
                # Extract conversation content
                for event in events:
                    event_data = {
                        "eventId": event.get("eventId"),
                        "timestamp": event.get("eventTimestamp"),
                        "messages": []
                    }
                    
                    # Extract messages from payload
                    payload = event.get("payload", [])
                    for item in payload:
                        if "conversational" in item:
                            conv = item["conversational"]
                            event_data["messages"].append({
                                "role": conv.get("role"),
                                "content": conv.get("content", {}).get("text", "")
                            })
                    
                    # Check if conversation matches query
                    conversation_matches = True  # Include all if no query
                    
                    if query:
                        conversation_matches = False
                        for message in event_data.get("messages", []):
                            message_content = message.get("content", "")
                            if query_matches_content(query, message_content):
                                conversation_matches = True
                                break
                    
                    if conversation_matches:
                        all_found_conversations.append({
                            "session_id": session_id,
                            "conversation": event_data,
                            "match_type": "content_match" if query else "all_conversations",
                            "query": query,
                            "source": "actor_sessions"
                        })
                        sessions_with_matches += 1
                
            except Exception as e:
                logger.warning(f"Error searching session {session_id}: {e}")
                continue
        
        # Sort conversations by timestamp (most recent first)
        all_found_conversations.sort(
            key=lambda x: x["conversation"].get("timestamp", ""), 
            reverse=True
        )
        
        # Create a context summary for kiro to understand
        context_summary = []
        for conv in all_found_conversations[:10]:  # Limit to top 10 for context
            messages = conv["conversation"].get("messages", [])
            if messages:
                # Extract user questions and assistant responses
                for msg in messages:
                    role = msg.get("role", "").upper()
                    content = msg.get("content", "")
                    if content:
                        context_summary.append(f"{role}: {content}")
        
        # Prepare response
        return {
            "success": True,
            "conversations": all_found_conversations,
            "total_found": len(all_found_conversations),
            "sessions_checked": sessions_checked,
            "sessions_with_content": len(actual_sessions),
            "sessions_with_matches": sessions_with_matches,
            "query": query,
            "actor_id": resolved_actor_id,
            "namespace": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}",
            "message": f"Found {len(all_found_conversations)} conversations" + (f" matching '{query}'" if query else "") + f" (checked {sessions_checked} sessions for actor '{resolved_actor_id}')",
            "strategy_used": "list_actor_sessions" if actual_sessions else "time_based_discovery",
            "context_summary": "\n\n".join(context_summary) if context_summary else "No conversation content found"
        }
        
    except Exception as e:
        logger.error(f"Error in search_conversation_history: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to search conversation history"
        }


@app.tool
async def search_memories(
    query: str,
    actor_id: Optional[str] = None,
    max_results: Optional[int] = 10,
    namespace: Optional[str] = None,
    memory_type: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Enhanced search memories using semantic search in AgentCore Memory.
    Includes fallback to conversation content search if semantic search fails.
    
    If actor_id is not provided, it will be determined based on ACTOR_ID_TYPE configuration.
    """
    try:
        # Resolve actor_id: use provided value or get from configuration
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        
        # Use environment variables as defaults
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required",
                "message": "Please provide a valid AgentCore Memory resource ID or set AGENTCORE_MEMORY_ID environment variable"
            }
        
        logger.info(f"Searching memories for actor_id: {resolved_actor_id}, query: {query}")
        logger.info(f"Using memory_id: {memory_id}, region: {region_name}")
        logger.info(f"AWS Account context - this should only search within the current account")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        search_attempts = []
        
        # Attempt 1: Semantic search using long-term memories
        try:
            # Create a temporary session for search
            session = session_manager.create_memory_session(
                actor_id=resolved_actor_id,
                session_id="search_session"
            )
            
            # Use namespace or default - include memory_id context
            # Use wildcard to search across all sessions for this actor
            search_namespace = namespace or f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}/*"
            
            logger.info(f"Performing semantic search with namespace: {search_namespace}")
            logger.info(f"Session is bound to memory_id: {memory_id}")
            logger.warning(f"SECURITY CHECK: All results should have memory_id={memory_id}")
            
            # Handle wildcard queries - semantic search doesn't support "*" wildcards
            # Convert wildcard to a broad search term
            search_query = query
            if query == "*" or query.strip() == "":
                search_query = "conversation"  # Use a generic term for broad search
                logger.info(f"Wildcard query detected, using broad search term: '{search_query}'")
            
            # Try direct AWS SDK approach using retrieve_memory_records
            logger.info(f"Attempting direct retrieve_memory_records API call")
            logger.info(f"Parameters: memory_id={memory_id}, namespace={search_namespace}, query={search_query}, topK={max_results or 10}")
            
            import boto3
            
            # Create bedrock-agentcore client directly
            bedrock_agentcore = boto3.client('bedrock-agentcore', region_name=region_name)
            
            # Call retrieve_memory_records directly
            try:
                # First try without memoryStrategyId filter
                response = bedrock_agentcore.retrieve_memory_records(
                    memoryId=memory_id,
                    namespace=search_namespace,
                    searchCriteria={
                        'searchQuery': search_query,
                        'topK': max_results or 10
                    }
                )
                
                logger.info(f"API Response keys: {response.keys()}")
                logger.info(f"Full API response: {json.dumps(response, default=str, indent=2)}")
                
                memory_records = response.get('memoryRecordSummaries', [])
                logger.info(f"Direct API call (no strategy filter) returned {len(memory_records)} records")
                
                # If no results, try with different namespace patterns
                if len(memory_records) == 0:
                    logger.info("No results with wildcard namespace, trying without wildcard")
                    alt_namespace = f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}"
                    response2 = bedrock_agentcore.retrieve_memory_records(
                        memoryId=memory_id,
                        namespace=alt_namespace,
                        searchCriteria={
                            'searchQuery': search_query,
                            'topK': max_results or 10
                        }
                    )
                    memory_records2 = response2.get('memoryRecordSummaries', [])
                    logger.info(f"Alternative namespace '{alt_namespace}' returned {len(memory_records2)} records")
                    
                    if len(memory_records2) > 0:
                        memory_records = memory_records2
                        search_namespace = alt_namespace
                
            except Exception as api_error:
                logger.error(f"retrieve_memory_records API call failed: {api_error}")
                logger.error(f"Error type: {type(api_error).__name__}")
                raise
            
            # Convert memory records to a more readable format
            # The direct API returns memoryRecordSummaries
            results = []
            
            for record in memory_records:
                # The direct API response structure
                record_id = record.get('memoryRecordId')
                record_content = record.get('content', {})
                record_namespaces = record.get('namespaces', [])
                record_namespace = record_namespaces[0] if record_namespaces else 'unknown'
                
                logger.info(f"Record {record_id}: namespace={record_namespace}, strategy={record.get('memoryStrategyId')}")
                
                # All records from this API call should be from the correct memory_id
                # since we specified it in the request
                results.append({
                    "id": record_id,
                    "content": record_content.get('text', '') if isinstance(record_content, dict) else str(record_content),
                    "relevance_score": record.get('score', 0.0),
                    "namespace": record_namespace,
                    "created_at": record.get('createdAt'),
                    "metadata": record.get('metadata', {}),
                    "memory_id": memory_id,
                    "memory_strategy_id": record.get('memoryStrategyId'),
                    "search_method": "direct_api"
                })
            
            search_attempts.append({
                "method": "direct_api_retrieve_memory_records", 
                "success": True, 
                "results": len(results)
            })
            
            if results:
                logger.info(f"Found {len(results)} memories using direct API for query '{query}'")
                
                # Create a context summary for kiro to understand
                context_summary = []
                for result in results:
                    content = result.get("content", "")
                    score = result.get("relevance_score", 0.0)
                    if content:
                        context_summary.append(f"[Relevance: {score:.1%}] {content}")
                
                return {
                    "success": True,
                    "results": results,
                    "total_found": len(results),
                    "query": query,
                    "namespace": search_namespace,
                    "actor_id": resolved_actor_id,
                    "configured_memory_id": memory_id,
                    "search_method": "direct_api",
                    "message": f"Found {len(results)} memories using direct API",
                    "search_attempts": search_attempts,
                    "context_summary": "\n\n".join(context_summary) if context_summary else "No memory content found"
                }
        
        except Exception as e:
            search_attempts.append({"method": "semantic_search", "success": False, "error": str(e)})
            logger.warning(f"Semantic search failed: {e}")
        
        # Attempt 2: Fallback to conversation content search
        try:
            logger.info("Falling back to conversation content search")
            
            # Get recent sessions and search through their content
            recent_sessions = await get_recent_sessions(resolved_actor_id, "last_week", memory_id, region_name)
            search_attempts.append({"method": "content_search_prep", "success": True, "sessions": len(recent_sessions)})
            
            matching_conversations = []
            
            for session_info in recent_sessions:
                try:
                    content = await get_direct_conversation_history(
                        resolved_actor_id, 
                        session_info["session_id"], 
                        memory_id=memory_id, 
                        region_name=region_name
                    )
                    
                    if content.get("success") and content.get("conversations"):
                        for conv in content["conversations"]:
                            # Search through message content using flexible matching
                            for message in conv.get("messages", []):
                                message_text = message.get("content", "")
                                if query_matches_content(query, message_text):
                                    matching_conversations.append({
                                        "session_id": session_info["session_id"],
                                        "event_id": conv.get("eventId"),
                                        "timestamp": conv.get("timestamp"),
                                        "matching_message": message,
                                        "full_conversation": conv,
                                        "relevance_score": 0.8,  # High score for direct text match
                                        "search_method": "content_match"
                                    })
                                    
                                    # Limit results
                                    if len(matching_conversations) >= (max_results or 10):
                                        break
                            
                            if len(matching_conversations) >= (max_results or 10):
                                break
                    
                except Exception as e:
                    logger.warning(f"Failed to search content in session {session_info['session_id']}: {e}")
                
                if len(matching_conversations) >= (max_results or 10):
                    break
            
            search_attempts.append({"method": "content_search", "success": True, "results": len(matching_conversations)})
            
            if matching_conversations:
                logger.info(f"Found {len(matching_conversations)} conversations using content search")
                
                # Create a context summary for kiro to understand
                context_summary = []
                for match in matching_conversations:
                    message = match.get("matching_message", {})
                    role = message.get("role", "").upper()
                    content = message.get("content", "")
                    if content:
                        context_summary.append(f"{role}: {content}")
                
                return {
                    "success": True,
                    "results": matching_conversations,
                    "total_found": len(matching_conversations),
                    "query": query,
                    "actor_id": resolved_actor_id,
                    "search_method": "content_match",
                    "message": f"Found {len(matching_conversations)} conversations containing '{query}'",
                    "search_attempts": search_attempts,
                    "context_summary": "\n\n".join(context_summary) if context_summary else "No conversation content found"
                }
        
        except Exception as e:
            search_attempts.append({"method": "content_search", "success": False, "error": str(e)})
            logger.error(f"Content search failed: {e}")
        
        # No results found with any method
        logger.info(f"No memories found for query '{query}' using any search method")
        
        return {
            "success": True,
            "results": [],
            "total_found": 0,
            "query": query,
            "actor_id": resolved_actor_id,
            "search_method": "none",
            "message": f"No memories found matching '{query}'",
            "search_attempts": search_attempts,
            "suggestions": [
                "Try a different search term",
                "Check if conversations were stored properly",
                "Use get_direct_conversation_history to see recent conversations"
            ]
        }
        
    except Exception as e:
        logger.error(f"Error searching memories: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to search memories in AgentCore Memory"
        }


@app.tool
async def store_conversation(
    user_question: str,
    assistant_response: str,
    actor_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Store conversation with consistent session ID (hourly sessions).
    This is the recommended tool for kiro-cli to store conversations.
    
    If actor_id is not provided, it will be determined based on ACTOR_ID_TYPE configuration.
    """
    try:
        # Resolve actor_id: use provided value or get from configuration
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        
        # Use environment variables as defaults
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required"
            }
        
        logger.info(f"Storing conversation for actor_id: {resolved_actor_id}")
        
        # Initialize memory client if needed
        if not initialize_memory_client(region_name):
            return {
                "success": False,
                "error": "Failed to initialize memory client"
            }
        
        # Use hourly session ID for consistency (same format as existing sessions)
        session_id = f"cli_session_{datetime.now().strftime('%Y%m%d_%H')}"
        
        # Use MemoryClient.create_event() instead of session.add_turns()
        # This should trigger the semantic memory strategy more reliably
        event = memory_client.create_event(
            memory_id=memory_id,
            actor_id=resolved_actor_id,
            session_id=session_id,
            messages=[
                (user_question, "USER"),
                (assistant_response, "ASSISTANT")
            ]
        )
        
        logger.info(f"Stored conversation for actor {resolved_actor_id} in session {session_id} using create_event")
        
        # Extract event ID
        event_id = event.get("eventId") if isinstance(event, dict) else None
        
        return {
            "success": True,
            "event_id": event_id,
            "session_id": session_id,
            "actor_id": resolved_actor_id,
            "message": f"Conversation stored successfully in session {session_id} (using create_event for strategy triggering)",
            "metadata": {
                "tool_name": "store_conversation",
                "memory_id": memory_id,
                "region": region_name,
                "storage_method": "create_event"
            }
        }
        
    except Exception as e:
        logger.error(f"Error storing conversation: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to store conversation"
        }




@app.tool
async def get_direct_conversation_history(
    actor_id: Optional[str] = None,
    session_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Get complete conversation history with full message content.
    Uses direct event access to retrieve the actual stored conversations.
    
    If actor_id is not provided, it will be determined based on ACTOR_ID_TYPE configuration.
    """
    try:
        # Resolve actor_id: use provided value or get from configuration
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        # Use current hour session if not provided
        if not session_id:
            session_id = f"cli_session_{datetime.now().strftime('%Y%m%d_%H')}"
        
        logger.info(f"Getting conversation history for actor_id: {resolved_actor_id}, session: {session_id}")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Create session
        session = session_manager.create_memory_session(
            actor_id=resolved_actor_id,
            session_id=session_id
        )
        
        # Get events directly
        events = session.list_events(max_results=20)
        
        # Extract conversation content
        conversations = []
        for event in events:
            event_data = {
                "eventId": event.get("eventId"),
                "timestamp": event.get("eventTimestamp"),
                "messages": []
            }
            
            # Extract messages from payload
            payload = event.get("payload", [])
            for item in payload:
                if "conversational" in item:
                    conv = item["conversational"]
                    event_data["messages"].append({
                        "role": conv.get("role"),
                        "content": conv.get("content", {}).get("text", "")
                    })
            
            conversations.append(event_data)
        
        return {
            "success": True,
            "session_id": session_id,
            "actor_id": resolved_actor_id,
            "total_events": len(events),
            "conversations": conversations,
            "message": f"Retrieved {len(events)} events directly from session {session_id}"
        }
        
    except Exception as e:
        logger.error(f"Error getting direct conversation history: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to get direct conversation history"
        }


@app.tool
async def list_sessions(
    actor_id: Optional[str] = None,
    limit: Optional[int] = 50,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    List user sessions from AgentCore Memory with improved session discovery.
    
    If actor_id is not provided, it will be determined based on ACTOR_ID_TYPE configuration.
    """
    try:
        # Resolve actor_id: use provided value or get from configuration
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required",
                "message": "Please provide a valid AgentCore Memory resource ID"
            }
        
        logger.info(f"Listing sessions for actor_id: {resolved_actor_id}")
        
        session_manager = get_session_manager(memory_id, region_name)
        
        # Try the standard list_actor_sessions first
        try:
            sessions = session_manager.list_actor_sessions(resolved_actor_id)
            if sessions:
                formatted_sessions = []
                for session in sessions[:limit or 50]:
                    formatted_sessions.append({
                        "session_id": session.get("sessionId"),
                        "created_at": session.get("createdAt"),
                        "last_updated": session.get("lastUpdatedAt"),
                        "event_count": session.get("eventCount", 0)
                    })
                
                return {
                    "success": True,
                    "sessions": formatted_sessions,
                    "total_found": len(formatted_sessions),
                    "actor_id": resolved_actor_id,
                    "method": "standard_listing",
                    "message": f"Found {len(formatted_sessions)} sessions"
                }
        except Exception as e:
            logger.warning(f"Standard session listing failed: {e}")
        
        # Fallback: Use recent session discovery
        logger.info("Falling back to recent session discovery")
        recent_sessions = await get_recent_sessions(resolved_actor_id, "last_week", memory_id, region_name)
        
        if recent_sessions:
            return {
                "success": True,
                "sessions": recent_sessions[:limit or 50],
                "total_found": len(recent_sessions),
                "actor_id": resolved_actor_id,
                "method": "recent_discovery",
                "message": f"Found {len(recent_sessions)} sessions using discovery method"
            }
        
        # If no sessions found at all
        return {
            "success": True,
            "sessions": [],
            "total_found": 0,
            "actor_id": resolved_actor_id,
            "method": "none",
            "message": "No sessions found for this actor"
        }
        
    except Exception as e:
        logger.error(f"Error listing sessions: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to list sessions"
        }


@app.tool
async def get_server_config() -> Dict[str, Any]:
    """
    Get the current MCP server configuration for debugging.
    Returns the actor_id_type, project_id, memory_id, and region being used.
    """
    try:
        # Get the resolved actor_id
        resolved_actor_id = get_actor_id(ACTOR_ID_TYPE)
        
        return {
            "success": True,
            "configuration": {
                "actor_id_type": ACTOR_ID_TYPE,
                "project_id": PROJECT_ID if PROJECT_ID else "(not set)",
                "resolved_actor_id": resolved_actor_id,
                "memory_id": DEFAULT_MEMORY_ID,
                "region": DEFAULT_REGION,
                "memory_strategy_id": MEMORY_STRATEGY_ID,
                "namespace_pattern": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{{actorId}}/session/{{sessionId}}",
                "current_namespace": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}"
            },
            "message": "Server configuration retrieved successfully"
        }
    except Exception as e:
        logger.error(f"Error getting server config: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to get server configuration"
        }


@app.tool
async def delete_session(
    session_id: str,
    actor_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Delete a specific session and all its events.
    
    Args:
        session_id: The session ID to delete (e.g., "cli_session_20260122_14")
        actor_id: Optional actor ID (uses configured ACTOR_ID_TYPE if not provided)
        memory_id: Optional memory resource ID
        region_name: Optional AWS region
    
    Returns:
        Success status and details about the deletion
    """
    try:
        # Resolve actor_id
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required"
            }
        
        logger.info(f"Deleting session {session_id} for actor {resolved_actor_id}")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Create session to access it
        session = session_manager.create_memory_session(
            actor_id=resolved_actor_id,
            session_id=session_id
        )
        
        # List events to see what we're deleting
        events = session.list_events(max_results=100)
        event_count = len(events)
        
        if event_count == 0:
            return {
                "success": False,
                "message": f"Session {session_id} not found or already empty",
                "session_id": session_id,
                "actor_id": resolved_actor_id
            }
        
        # Delete all events in the session
        deleted_count = 0
        for event in events:
            try:
                event_id = event.get("eventId")
                if event_id:
                    session.delete_event(event_id)
                    deleted_count += 1
            except Exception as e:
                logger.warning(f"Failed to delete event {event_id}: {e}")
        
        return {
            "success": True,
            "session_id": session_id,
            "actor_id": resolved_actor_id,
            "events_deleted": deleted_count,
            "total_events": event_count,
            "message": f"Successfully deleted {deleted_count} of {event_count} events from session {session_id}"
        }
        
    except Exception as e:
        logger.error(f"Error deleting session: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": f"Failed to delete session {session_id}"
        }


@app.tool
async def get_memory_stats(
    actor_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Get memory usage statistics for the actor.
    
    Args:
        actor_id: Optional actor ID (uses configured ACTOR_ID_TYPE if not provided)
        memory_id: Optional memory resource ID
        region_name: Optional AWS region
    
    Returns:
        Statistics about memory usage including session count, event count, and memory records
    """
    try:
        # Resolve actor_id
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required"
            }
        
        logger.info(f"Getting memory stats for actor {resolved_actor_id}")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Get sessions
        try:
            sessions = session_manager.list_actor_sessions(resolved_actor_id)
        except Exception:
            sessions = []
        
        # Count events across sessions by actually checking each session
        # The eventCount from list_actor_sessions may not be accurate
        total_events = 0
        session_details = []
        
        for session_info in sessions[:50]:  # Limit to 50 sessions for performance
            session_id = session_info.get("sessionId")
            
            # Get actual event count by listing events
            try:
                session = session_manager.create_memory_session(
                    actor_id=resolved_actor_id,
                    session_id=session_id
                )
                events = session.list_events(max_results=100)
                actual_event_count = len(events)
                total_events += actual_event_count
            except Exception as e:
                logger.warning(f"Failed to get events for session {session_id}: {e}")
                actual_event_count = 0
            
            session_details.append({
                "session_id": session_id,
                "event_count": actual_event_count,
                "created_at": session_info.get("createdAt"),
                "last_updated": session_info.get("lastUpdatedAt")
            })
            
        logger.info(f"Counted {total_events} total events across {len(sessions)} sessions")
        
        # Get long-term memory count using list_memory_records API
        # This API lists all memories in a namespace without requiring a search query
        import boto3
        bedrock_agentcore = boto3.client('bedrock-agentcore', region_name=region_name)
        
        memory_count = 0
        list_error = None
        namespace_tried = None
        
        try:
            # Try multiple namespace patterns to find memories
            # The memories might be at different namespace levels
            namespaces_to_try = [
                f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}/*",
                f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}",
                f"/strategy/{MEMORY_STRATEGY_ID}/*",
            ]
            
            # Also try specific session namespaces from recent sessions
            for session_detail in session_details[:5]:  # Try top 5 recent sessions
                session_id = session_detail.get("session_id")
                namespaces_to_try.append(f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}/session/{session_id}")
            
            all_memory_ids = set()
            
            for ns in namespaces_to_try:
                try:
                    namespace_tried = ns
                    logger.info(f"Trying namespace: {ns}")
                    
                    response = bedrock_agentcore.list_memory_records(
                        memoryId=memory_id,
                        namespace=ns
                    )
                    memory_records = response.get('memoryRecordSummaries', [])
                    
                    for record in memory_records:
                        all_memory_ids.add(record.get('memoryRecordId'))
                    
                    logger.info(f"Found {len(memory_records)} memories in namespace {ns}")
                    
                    # Handle pagination
                    while response.get('nextToken'):
                        response = bedrock_agentcore.list_memory_records(
                            memoryId=memory_id,
                            namespace=ns,
                            startingToken=response['nextToken']
                        )
                        more_records = response.get('memoryRecordSummaries', [])
                        for record in more_records:
                            all_memory_ids.add(record.get('memoryRecordId'))
                    
                except Exception as ns_error:
                    logger.debug(f"Namespace {ns} failed: {ns_error}")
                    continue
            
            memory_count = len(all_memory_ids)
            logger.info(f"Total unique memories found across all namespaces: {memory_count}")
            
        except Exception as e:
            list_error = str(e)
            logger.error(f"Failed to list memory records: {e}")
            logger.error(f"Error type: {type(e).__name__}")
            import traceback
            logger.error(f"Traceback: {traceback.format_exc()}")
            memory_count = 0
        
        return {
            "success": True,
            "actor_id": resolved_actor_id,
            "memory_id": memory_id,
            "statistics": {
                "total_sessions": len(sessions),
                "total_events": total_events,
                "long_term_memories": memory_count,
                "average_events_per_session": round(total_events / len(sessions), 2) if sessions else 0
            },
            "recent_sessions": session_details[:10],  # Show top 10 most recent
            "namespace": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}",
            "message": f"Found {len(sessions)} sessions with {total_events} events and {memory_count} long-term memories" + (f" (namespace: {namespace_tried}, error: {list_error})" if list_error else "")
        }
        
    except Exception as e:
        logger.error(f"Error getting memory stats: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to get memory statistics"
        }


@app.tool
async def get_session_details(
    session_id: str,
    actor_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None
) -> Dict[str, Any]:
    """
    Get detailed information about a specific session.
    
    Args:
        session_id: The session ID to inspect (e.g., "cli_session_20260122_15")
        actor_id: Optional actor ID (uses configured ACTOR_ID_TYPE if not provided)
        memory_id: Optional memory resource ID
        region_name: Optional AWS region
    
    Returns:
        Detailed information about the session including events and related memories
    """
    try:
        # Resolve actor_id
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required"
            }
        
        logger.info(f"Getting details for session {session_id}, actor {resolved_actor_id}")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Create session
        session = session_manager.create_memory_session(
            actor_id=resolved_actor_id,
            session_id=session_id
        )
        
        # Get events
        events = session.list_events(max_results=100)
        
        # Extract event details
        event_details = []
        for event in events:
            event_data = {
                "event_id": event.get("eventId"),
                "timestamp": event.get("eventTimestamp"),
                "message_count": 0,
                "messages": []
            }
            
            # Extract messages from payload
            payload = event.get("payload", [])
            for item in payload:
                if "conversational" in item:
                    conv = item["conversational"]
                    event_data["messages"].append({
                        "role": conv.get("role"),
                        "content": conv.get("content", {}).get("text", "")[:100] + "..."  # Truncate for readability
                    })
                    event_data["message_count"] += 1
            
            event_details.append(event_data)
        
        # Search for long-term memories from this session
        import boto3
        bedrock_agentcore = boto3.client('bedrock-agentcore', region_name=region_name)
        
        session_namespace = f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}/session/{session_id}"
        
        try:
            # Try to find memories specifically from this session
            response = bedrock_agentcore.retrieve_memory_records(
                memoryId=memory_id,
                namespace=session_namespace,
                searchCriteria={
                    'searchQuery': 'conversation',
                    'topK': 50
                }
            )
            memory_records = response.get('memoryRecordSummaries', [])
            
            memory_details = []
            for record in memory_records:
                content = record.get('content', {})
                memory_details.append({
                    "memory_id": record.get('memoryRecordId'),
                    "content": content.get('text', '') if isinstance(content, dict) else str(content),
                    "created_at": record.get('createdAt'),
                    "namespaces": record.get('namespaces', [])
                })
            
        except Exception as e:
            logger.warning(f"Failed to get memories for session: {e}")
            memory_details = []
        
        return {
            "success": True,
            "session_id": session_id,
            "actor_id": resolved_actor_id,
            "namespace": session_namespace,
            "statistics": {
                "total_events": len(events),
                "total_memories": len(memory_details)
            },
            "events": event_details,
            "memories": memory_details,
            "message": f"Session {session_id} has {len(events)} events and {len(memory_details)} extracted memories"
        }
        
    except Exception as e:
        logger.error(f"Error getting session details: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": f"Failed to get details for session {session_id}"
        }


@app.tool
async def clear_all_data(
    actor_id: Optional[str] = None,
    memory_id: Optional[str] = None,
    region_name: Optional[str] = None,
    confirm: bool = False
) -> Dict[str, Any]:
    """
    Clear all conversation data for an actor. This is a destructive operation!
    
    Args:
        actor_id: Optional actor ID (uses configured ACTOR_ID_TYPE if not provided)
        memory_id: Optional memory resource ID
        region_name: Optional AWS region
        confirm: Must be set to True to actually perform the deletion
    
    Returns:
        Success status and details about what was deleted
    
    Warning:
        This will delete ALL sessions and events for the actor. This cannot be undone!
        Long-term memories extracted by the semantic strategy will remain in the memory resource
        but will no longer have source events.
    """
    try:
        # Resolve actor_id
        resolved_actor_id = actor_id if actor_id else get_actor_id(ACTOR_ID_TYPE)
        memory_id = memory_id or DEFAULT_MEMORY_ID
        region_name = region_name or DEFAULT_REGION
        
        if not memory_id:
            return {
                "success": False,
                "error": "memory_id is required"
            }
        
        if not confirm:
            return {
                "success": False,
                "message": "Confirmation required. Set confirm=True to proceed with deletion.",
                "warning": "This will delete ALL conversation data for the actor and cannot be undone!",
                "actor_id": resolved_actor_id,
                "namespace": f"/strategy/{MEMORY_STRATEGY_ID}/actor/{resolved_actor_id}"
            }
        
        logger.warning(f"CLEARING ALL DATA for actor {resolved_actor_id}")
        
        # Get session manager
        session_manager = get_session_manager(memory_id, region_name)
        
        # Get all sessions
        try:
            sessions = session_manager.list_actor_sessions(resolved_actor_id)
        except Exception:
            sessions = []
        
        if not sessions:
            return {
                "success": True,
                "message": "No sessions found to delete",
                "actor_id": resolved_actor_id
            }
        
        # Delete all sessions
        deleted_sessions = 0
        deleted_events = 0
        failed_deletions = 0
        
        for session_info in sessions:
            session_id = session_info.get("sessionId")
            try:
                result = await delete_session(
                    session_id=session_id,
                    actor_id=resolved_actor_id,
                    memory_id=memory_id,
                    region_name=region_name
                )
                
                if result.get("success"):
                    deleted_sessions += 1
                    deleted_events += result.get("events_deleted", 0)
                else:
                    failed_deletions += 1
                    
            except Exception as e:
                logger.error(f"Failed to delete session {session_id}: {e}")
                failed_deletions += 1
        
        return {
            "success": True,
            "actor_id": resolved_actor_id,
            "memory_id": memory_id,
            "deleted_sessions": deleted_sessions,
            "deleted_events": deleted_events,
            "failed_deletions": failed_deletions,
            "total_sessions": len(sessions),
            "message": f"Cleared {deleted_sessions} sessions with {deleted_events} events for actor {resolved_actor_id}",
            "warning": "Long-term memories extracted by the semantic strategy remain in the memory resource"
        }
        
    except Exception as e:
        logger.error(f"Error clearing all data: {e}")
        return {
            "success": False,
            "error": str(e),
            "message": "Failed to clear all data"
        }





async def main():
    """Run the AgentCore Memory MCP server."""
    logger.info("Starting AgentCore Memory MCP Server")
    
    try:
        # Run the server using the async method to avoid event loop conflicts
        await app.run_stdio_async()
    except KeyboardInterrupt:
        logger.info("Server stopped by user")
    except Exception as e:
        logger.error(f"Server error: {e}")
        import traceback
        traceback.print_exc()
        raise


def main_sync():
    """Synchronous entry point that handles event loop properly."""
    try:
        # Check if we're already in an async context (like when called by MCP client)
        try:
            loop = asyncio.get_running_loop()
            # If we get here, we're in an async context - this shouldn't happen for MCP
            logger.warning("Already in async context - running directly")
            # For direct execution, we need to handle this differently
            import sys
            if sys.stdin.isatty():
                # Interactive mode - just exit gracefully
                logger.info("Interactive mode detected - server configured correctly")
                logger.info("Use this server with an MCP client like kiro-cli")
                return
            else:
                # Non-interactive (MCP client) - this is unexpected
                logger.error("Unexpected: MCP client context with existing event loop")
                return
        except RuntimeError:
            # No event loop running - safe to create one
            pass
        
        # Run the FastMCP server
        asyncio.run(app.run())
        
    except Exception as e:
        logger.error(f"Failed to start MCP server: {e}")
        import traceback
        traceback.print_exc()
        raise


if __name__ == "__main__":
    main_sync()