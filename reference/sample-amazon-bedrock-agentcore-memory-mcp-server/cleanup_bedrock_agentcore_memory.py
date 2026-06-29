#!/usr/bin/env python3
"""
Cleanup script for AgentCore Memory resources.
This script helps you delete AgentCore Memory resources and clean up associated data.
"""

import asyncio
import boto3
import json
import os
from bedrock_agentcore.memory.controlplane import MemoryControlPlaneClient


def check_aws_credentials():
    """Check if AWS credentials are configured."""
    try:
        session = boto3.Session()
        credentials = session.get_credentials()
        
        if credentials is None:
            print("❌ No AWS credentials found")
            print("Please configure AWS credentials first")
            return False
        
        print("✅ AWS credentials found")
        print(f"   Access Key ID: {credentials.access_key[:8]}...")
        print(f"   Region: {session.region_name or 'Not set (will use us-east-1)'}")
        return True
        
    except Exception as e:
        print(f"❌ Error checking AWS credentials: {e}")
        return False


def list_memory_resources(region_name="us-east-1"):
    """List all AgentCore Memory resources in the region."""
    try:
        print(f"📋 Listing AgentCore Memory resources in {region_name}...")
        
        control_client = MemoryControlPlaneClient(region_name=region_name)
        memories = control_client.list_memories()
        
        if not memories:
            print("   No memory resources found")
            return []
        
        print(f"   Found {len(memories)} memory resource(s):")
        for i, memory in enumerate(memories, 1):
            status = memory.get('status', 'Unknown')
            created = memory.get('createdAt', 'Unknown')
            
            # Try multiple possible name fields and extract from ID as fallback
            name = (memory.get('name') or 
                   memory.get('memoryName') or 
                   memory.get('displayName') or
                   memory.get('id', '').split('-')[0] or  # Extract prefix from ID
                   'Unnamed')
            
            print(f"   {i}. Name: {name}")
            print(f"      ID: {memory.get('id')}")
            print(f"      Status: {status}")
            print(f"      Created: {created}")
            print()
        
        return memories
        
    except Exception as e:
        print(f"❌ Failed to list memory resources: {e}")
        return []


def delete_memory_resource(memory_id, region_name="us-east-1"):
    """Delete a specific AgentCore Memory resource."""
    try:
        print(f"🗑️  Deleting memory resource: {memory_id}")
        
        control_client = MemoryControlPlaneClient(region_name=region_name)
        
        # Get memory details before deletion
        try:
            memory = control_client.get_memory(memory_id)
            name = memory.get('name') or memory.get('memoryName') or 'Unnamed'
            print(f"   Name: {name}")
            print(f"   Status: {memory.get('status')}")
        except:
            print("   (Could not retrieve memory details)")
        
        # Delete the memory resource
        control_client.delete_memory(
            memory_id=memory_id,
            wait_for_deletion=True,
            max_wait=300
        )
        
        print("✅ Memory resource deleted successfully!")
        return True
        
    except Exception as e:
        print(f"❌ Failed to delete memory resource: {e}")
        return False


def cleanup_config_files():
    """Clean up generated configuration files."""
    config_files = [
        "mcp_config.json",
        ".env"
    ]
    # config_files = [
    #     ".env.production",
    #     "mcp_config.json",
    #     ".env"
    # ]
    
    cleaned_files = []
    for file_path in config_files:
        if os.path.exists(file_path):
            try:
                os.remove(file_path)
                cleaned_files.append(file_path)
            except Exception as e:
                print(f"⚠️  Could not delete {file_path}: {e}")
    
    if cleaned_files:
        print(f"🧹 Cleaned up configuration files: {', '.join(cleaned_files)}")
    else:
        print("   No configuration files found to clean up")


async def main():
    """Main cleanup function."""
    print("🧹 AgentCore Memory Cleanup Script")
    print("=" * 50)
    
    # Step 1: Check AWS credentials
    print("\n1. Checking AWS Credentials...")
    if not check_aws_credentials():
        return
    
    # Step 2: Get region
    print("\n2. Region Configuration...")
    region = input("Enter AWS region (default: us-east-1): ").strip() or "us-east-1"
    
    # Step 3: List existing memory resources
    print("\n3. Discovering Memory Resources...")
    memories = list_memory_resources(region)
    
    if not memories:
        print("No memory resources to clean up.")
        cleanup_choice = input("\nDo you want to clean up configuration files? [y/N]: ").lower()
        if cleanup_choice == 'y':
            cleanup_config_files()
        return
    
    # Step 4: Choose deletion method
    print("\n4. Cleanup Options...")
    print("Choose cleanup method:")
    print("1. Delete specific memory resource")
    print("2. Delete all memory resources")
    print("3. Delete all + clean config files")
    print("4. Only clean config files")
    print("5. Cancel")
    
    choice = input("Enter your choice [1-5]: ").strip()
    
    if choice == "1":
        # Delete specific memory
        if len(memories) == 1:
            memory_id = memories[0]['id']
            # Use the same name extraction logic
            memory_name = (memories[0].get('name') or 
                          memories[0].get('memoryName') or 
                          memories[0].get('displayName') or
                          memories[0].get('id', '').split('-')[0] or
                          'Unnamed')
            confirm = input(f"Delete memory '{memory_name}' ({memory_id})? [y/N]: ").lower()
            if confirm == 'y':
                delete_memory_resource(memory_id, region)
        else:
            print("\nSelect memory to delete:")
            for i, memory in enumerate(memories, 1):
                # Try multiple possible name fields and extract from ID as fallback
                name = (memory.get('name') or 
                       memory.get('memoryName') or 
                       memory.get('displayName') or
                       memory.get('id', '').split('-')[0] or  # Extract prefix from ID
                       'Unnamed')
                print(f"{i}. {name} ({memory.get('id')})")
            
            try:
                selection = int(input("Enter number: ")) - 1
                if 0 <= selection < len(memories):
                    memory_id = memories[selection]['id']
                    # Use the same name extraction logic
                    memory_name = (memories[selection].get('name') or 
                                 memories[selection].get('memoryName') or 
                                 memories[selection].get('displayName') or
                                 memories[selection].get('id', '').split('-')[0] or
                                 'Unnamed')
                    confirm = input(f"Delete memory '{memory_name}' ({memory_id})? [y/N]: ").lower()
                    if confirm == 'y':
                        delete_memory_resource(memory_id, region)
                else:
                    print("❌ Invalid selection")
            except ValueError:
                print("❌ Invalid input")
    
    elif choice == "2":
        # Delete all memories
        confirm = input(f"Delete ALL {len(memories)} memory resources? [y/N]: ").lower()
        if confirm == 'y':
            success_count = 0
            for memory in memories:
                if delete_memory_resource(memory['id'], region):
                    success_count += 1
            print(f"\n✅ Deleted {success_count}/{len(memories)} memory resources")
    
    elif choice == "3":
        # Delete all memories + config files
        confirm = input(f"Delete ALL {len(memories)} memory resources AND config files? [y/N]: ").lower()
        if confirm == 'y':
            success_count = 0
            for memory in memories:
                if delete_memory_resource(memory['id'], region):
                    success_count += 1
            print(f"\n✅ Deleted {success_count}/{len(memories)} memory resources")
            cleanup_config_files()
    
    elif choice == "4":
        # Only clean config files
        cleanup_config_files()
    
    elif choice == "5":
        print("Cleanup cancelled")
        return
    
    else:
        print("❌ Invalid choice")
        return
    
    print("\n🎉 Cleanup Complete!")
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())