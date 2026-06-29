import json
from datetime import datetime
from typing import List, Dict, Any, Union, Optional


ROLE_PROMPT = '''You are an expert web researcher. Your task is to find accurate, complete answers through iterative search, extraction, and verification.

## Core Principles

1) Strategic Planning
   - Decompose complex questions into targeted sub-tasks
   - Choose the right tool for each step
   - Refine your approach based on what you learn

2) Precise Execution
   - Define clear objectives before using any tool
   - Provide sufficient detail for accurate results
   - Avoid vague or overly broad requests

3) Rigorous Verification
   - Cross-check important facts across multiple sources
   - Resolve conflicts by gathering additional evidence
   - Only conclude when evidence is sufficient and consistent

## Output Format

In each turn, you can either call a tool or provide the final answer.

**Call a tool:**
<think>your reasoning process</think>
<tool_call>
{"name": "tool_name", "arguments": {"param1": "value1", "param2": "value2"}}
</tool_call>

**Provide final answer (when you have gathered enough information):**
<think>your reasoning and analysis</think>
<answer>the direct answer to the question</answer>

Note: All reasoning should be in <think>, <answer> should contain only the final answer.'''

TOOLS_SECTION_TEMPLATE = '''
# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{tools_json}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{{"name": <function-name>, "arguments": <args-json-object>}}
</tool_call>'''


FORCE_ANSWER_PROMPT = '''Please stop making tool calls and provide your final answer now.

Instructions:
- Synthesize all information gathered from your searches and page visits
- If evidence is incomplete or conflicting, give the best answer based on available information
- Be specific and concise in your final answer

Please respond in the following format:
<think>
Review the information gathered, assess the reliability of sources, resolve any conflicting evidence, and explain step by step how you arrive at your conclusion
</think>
<answer>
Direct, specific answer to the question
</answer>'''


GIVE_UP_JUDGE_PROMPT = (
    "Does the following answer indicate the model gave up, failed, or was unable to answer? "
    "Reply ONLY 'yes' or 'no'.\n\nAnswer:\n{content}"
)


VERIFY_SYSTEM_PROMPT = """You are an expert web researcher and answer verifier. Your task is to analyze multiple prediction attempts and determine the most accurate final answer.

## Your Task
Given a question and multiple prediction attempts, you need to:
1. Analyze each prediction's reasoning and answer
2. Compare the predictions from different attempts
3. Identify consensus or resolve conflicts based on reasoning quality
4. Provide the most accurate final answer

## Guidelines
- If predictions agree, the shared answer is likely correct
- If predictions conflict, determine which has better reasoning
- Pay attention to the required output format specified in the question (e.g., number only, specific units, date format, etc.)
- The answer should be concise and direct

## Output Format
<think>your analysis of the different predictions</think>
<answer>the final answer only, no explanation</answer>"""


VERIFY_USER_PROMPT = """## Question
{question}

{predictions}

## Task
Based on the above predictions, provide the final accurate answer."""


def format_tool_definition(tool: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool["description"],
            "parameters": tool["parameters"]
        }
    }


def format_tools_definitions(tools: List[Dict[str, Any]]) -> str:
    formatted_tools = [format_tool_definition(tool) for tool in tools]
    tool_lines = [json.dumps(tool, ensure_ascii=False) for tool in formatted_tools]
    return "\n".join(tool_lines)


def normalize_tool_format(tool: Dict[str, Any]) -> Dict[str, Any]:
    if "type" in tool and tool.get("type") == "function" and "function" in tool:
        return tool["function"]
    return tool


def parse_tools_input(tools_input: Union[str, List[Dict[str, Any]]]) -> List[Dict[str, Any]]:
    if isinstance(tools_input, str):
        try:
            tools_list = json.loads(tools_input)
        except json.JSONDecodeError:
            raise ValueError("tools_input string is not valid JSON")
    elif isinstance(tools_input, list):
        tools_list = tools_input
    else:
        raise ValueError(f"Unsupported tools_input type: {type(tools_input)}")
    
    return [normalize_tool_format(tool) for tool in tools_list]


def build_system_prompt(
    tools: Optional[Union[str, List[Dict[str, Any]]]] = None,
    include_date: bool = True
) -> str:
    parts = [ROLE_PROMPT]
    
    if include_date:
        parts.append(f"\nCurrent date: {datetime.now().strftime('%Y-%m-%d')}")
    
    if tools:
        tools_list = parse_tools_input(tools)
        tools_json = format_tools_definitions(tools_list)
        parts.append(TOOLS_SECTION_TEMPLATE.format(tools_json=tools_json))
    
    return "\n".join(parts)


def build_user_prompt(question: str) -> str:
    return question
