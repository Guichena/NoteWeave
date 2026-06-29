import json
from typing import Optional, Tuple


class ResponseParser:
    
    LLM_ERROR_MESSAGES = (
        "Call LLM API error",
        "Call LLM API empty response"
    )
    
    TOOL_CALL_START = "<tool_call>"
    TOOL_CALL_END = "</tool_call>"
    ANSWER_START = "<answer>"
    ANSWER_END = "</answer>"
    
    def has_tool_call(self, content: str) -> bool:
        return self.TOOL_CALL_START in content
    
    def has_answer(self, content: str) -> bool:
        return self.ANSWER_START in content and self.ANSWER_END in content
    
    def is_llm_error(self, content: str) -> bool:
        return content in self.LLM_ERROR_MESSAGES
    
    def extract_first_tool_call(self, text: str) -> str:
        start = text.find(self.TOOL_CALL_START)
        if start == -1:
            return text
        
        end = text.find(self.TOOL_CALL_END, start)
        
        if end == -1:
            return text.strip() + self.TOOL_CALL_END
        
        second_start = text.find(self.TOOL_CALL_START, end)
        if second_start != -1:
            return text[:end + len(self.TOOL_CALL_END)]
        
        return text
    
    def parse_tool_call(self, content: str) -> Tuple[Optional[str], Optional[dict], Optional[str]]:
        try:
            after_start = content.split(self.TOOL_CALL_START)[1]
            
            if self.TOOL_CALL_END in after_start:
                tool_call_str = after_start.split(self.TOOL_CALL_END)[0].strip()
            else:
                tool_call_str = after_start.strip()
            
            tool_call = json.loads(tool_call_str)
            
            tool_name = tool_call.get('name')
            tool_args = tool_call.get('arguments', {})
            
            if not tool_name:
                return None, None, 'Error: Missing "name" field in tool call.'
            
            return tool_name, tool_args, None
            
        except json.JSONDecodeError as e:
            return None, None, f'Error: Invalid JSON in tool call. {str(e)}'
        except Exception as e:
            return None, None, f'Error: Failed to parse tool call. {str(e)}'
    
    def extract_answer(self, content: str) -> Optional[str]:
        if self.has_answer(content):
            return content.split(self.ANSWER_START)[1].split(self.ANSWER_END)[0].strip()
        return None
