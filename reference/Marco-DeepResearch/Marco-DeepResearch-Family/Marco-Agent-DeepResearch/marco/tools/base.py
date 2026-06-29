from abc import ABC, abstractmethod
from typing import Dict, List, Union, Any, Optional
import logging

logger = logging.getLogger(__name__)


_TOOL_REGISTRY: Dict[str, type] = {}


def register_tool(name: str):
    def decorator(cls):
        _TOOL_REGISTRY[name] = cls
        cls.name = name
        return cls
    return decorator


def get_tool(name: str) -> Optional[type]:
    return _TOOL_REGISTRY.get(name)


class BaseTool(ABC):
    
    name: str = ""
    description: str = ""
    parameters: Dict[str, Any] = {}
    
    def __init__(self, **kwargs):
        self.config = kwargs
    
    @abstractmethod
    def call(self, params: Union[str, dict], **kwargs) -> str:
        pass
    
    def __call__(self, params: Union[str, dict], **kwargs) -> str:
        return self.call(params, **kwargs)
    
    @classmethod
    def get_function_schema(cls) -> Dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": cls.name,
                "description": cls.description,
                "parameters": cls.parameters
            }
        }


class ToolManager:
    
    def __init__(self, tool_names: Optional[List[str]] = None, tool_configs: Optional[Dict[str, dict]] = None):
        self.tools: Dict[str, BaseTool] = {}
        tool_configs = tool_configs or {}
        
        if tool_names is None:
            for name, cls in _TOOL_REGISTRY.items():
                config = tool_configs.get(name, {})
                self.tools[name] = cls(**config)
        else:
            for name in tool_names:
                cls = get_tool(name)
                if cls:
                    config = tool_configs.get(name, {})
                    self.tools[name] = cls(**config)
                else:
                    logger.warning("⚠️ Tool '%s' not found in registry", name)
    
    def call_tool(self, name: str, params: Union[str, dict], **kwargs) -> str:
        if name not in self.tools:
            return f"Error: Tool '{name}' not found"
        return self.tools[name].call(params, **kwargs)
    
    def get_tools_schema(self) -> List[Dict[str, Any]]:
        return [tool.get_function_schema() for tool in self.tools.values()]
