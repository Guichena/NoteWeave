import logging
from typing import Dict, List, Optional, Any, Callable, Tuple, Union

from .base import BaseAgent

logger = logging.getLogger(__name__)
from .constants import AgentState, TTSStrategy, ExecutionMode, AgentResult
from .context_manager import ContextManager
from .response_parser import ResponseParser
from ..tools import ToolManager
from ..prompts import (
    build_system_prompt,
    build_user_prompt,
    FORCE_ANSWER_PROMPT,
)
from ..utils import Config, LLMClient


class MarcoAgent(BaseAgent):
    
    DEFAULT_MAX_RETRIES = 3
    
    def __init__(
            self,
            config: Config,
            tool_manager: Optional[ToolManager] = None,
            system_prompt: Optional[str] = None,
            execution_mode: Optional[str] = None
        ):
        self.config = config
        self.execution_mode = execution_mode or config.agent.execution_mode
        
        self.tool_manager = tool_manager or self._create_tool_manager(config)
        
        self.llm_client = LLMClient.from_config(config)
        
        self.max_llm_calls = config.agent.max_llm_calls
        self.truncate_side = config.agent.truncate_side
        self.max_tool_response_length = config.agent.max_tool_response_length
        
        self.max_context_length = config.model.max_context_length
        self.max_output_tokens = config.generation.max_tokens
        self.token_safety_buffer = config.agent.token_safety_buffer
        self.effective_max_input_tokens = self.max_context_length - self.max_output_tokens - self.token_safety_buffer
        
        self.max_retries = getattr(config.agent, 'max_retries', self.DEFAULT_MAX_RETRIES)
        
        self.search_empty_result_restart_enabled = getattr(config.agent, 'search_empty_result_restart_enabled', False)
        self.search_empty_result_pattern = getattr(config.agent, 'search_empty_result_pattern', 'Results:0 ')
        
        self.context_manager = ContextManager(
            max_input_tokens=self.effective_max_input_tokens,
            token_counter=self.llm_client.count_tokens,
            enabled=getattr(config.agent, 'tts_enabled', False),
            truncate_side=self.truncate_side,
            max_tool_response_length=self.max_tool_response_length,
            max_discard_count=getattr(config.agent, 'tts_max_discard_count', 3),
            round_threshold=getattr(config.agent, 'tts_round_threshold', None),
            use_llm_judge=getattr(config.agent, 'tts_give_up_llm_judge', False),
            llm_call_fn=self.llm_client.call,
            verify_enabled=getattr(config.agent, 'tts_verify_enabled', False),
            verify_max_answers=getattr(config.agent, 'tts_verify_max_answers', 8),
            verify_early_stop_count=getattr(config.agent, 'tts_verify_early_stop_count', 4),
        )
        
        self.parser = ResponseParser()
        
        tools_list = self.tool_manager.get_tools_schema()
        self.system_message = system_prompt or build_system_prompt(
            tools=tools_list,
            include_date=True
        )
        
    
    def _create_tool_manager(self, config: Config) -> ToolManager:
        tool_configs = {
            name: getattr(config.tools, name, {})
            for name in config.tools.enabled
            if hasattr(config.tools, name)
        }
        return ToolManager(
            tool_names=config.tools.enabled,
            tool_configs=tool_configs
        )
    
    def _call_tool(self, tool_name: str, tool_args: dict) -> str:
        return self.tool_manager.call_tool(tool_name, tool_args)

    
    def _force_final_answer(
            self,
            messages: List[Dict],
            reason: str
        ) -> Tuple[str, str]:
        logger.info("⚡ Forcing final answer due to: %s", reason)
        
        messages.append({"role": "user", "content": FORCE_ANSWER_PROMPT})
        
        content = self.llm_client.call(messages)
        messages.append({"role": "assistant", "content": content.strip()})
        
        if self.parser.is_llm_error(content):
            return content, f"forced:{reason}:llm_error"
        
        answer = self.parser.extract_answer(content)
        if answer is not None:
            return answer, f"forced:{reason}"
        
        return content.strip(), f"forced:{reason}:format_error"
    
    
    def _check_round_limit(self, round_num: int, max_calls: Optional[int] = None) -> Optional[str]:
        limit = max_calls if max_calls is not None else self.max_llm_calls
        if round_num > limit:
            logger.info("🚫 Max calls reached: %d > %d", round_num, limit)
            return AgentState.MAX_CALLS_REACHED
        return None
    
    def _is_token_exceeded(self, messages: List[Dict]) -> bool:
        count = self.llm_client.count_tokens(messages)
        if count > self.effective_max_input_tokens:
            logger.info("🚫 Token limit exceeded: %d > %d", count, self.effective_max_input_tokens)
            return True
        return False
    
    
    def _process_llm_response(
            self,
            content: str,
            messages: List[Dict]
        ) -> Tuple[Optional[str], Optional[str], bool, bool]:
        if self.parser.is_llm_error(content):
            logger.warning("❌ LLM returned error: %s", content[:200])
            return content, AgentState.LLM_ERROR, False, True
        
        if self.parser.has_answer(content):
            messages.append({"role": "assistant", "content": content.strip()})
            answer = self.parser.extract_answer(content)
            logger.info("✅ Final answer: %s", answer[:200] + '...' if answer and len(answer) > 200 else answer)
            return answer, AgentState.FINISHED, False, False
        
        if self.parser.has_tool_call(content):
            tool_call_content = self.parser.extract_first_tool_call(content)
            tool_name, tool_args, parse_error = self.parser.parse_tool_call(tool_call_content)
            
            logger.info("🔧 Tool call: %s | args: %s", tool_name, tool_args)
            tool_result = parse_error if parse_error else self._call_tool(tool_name, tool_args)
            tool_result = self.context_manager.truncate_tool_response(tool_result)
            first_line = tool_result.split('\n', 1)[0][:200]
            logger.info("📋 Tool response (%d chars): %s", len(tool_result), first_line)
            
            messages.append({"role": "assistant", "content": tool_call_content.strip()})
            
            tool_response_content = f"<tool_response>\n{tool_result}\n</tool_response>"
            messages.append({"role": "user", "content": tool_response_content})
            
            if (self.search_empty_result_restart_enabled
                    and self.search_empty_result_pattern in tool_result):
                logger.info("🔄 [SearchEmptyResult] Detected pattern '%s' in Search result, restarting from scratch", self.search_empty_result_pattern)
                messages[:] = [messages[0], messages[1]]
                return None, None, True, False
            
            return None, None, True, False
        
        logger.warning("⚠️ Unexpected output format")
        return None, AgentState.FORMAT_ERROR, False, True
    
    
    def _call_llm_for_round(
            self,
            messages: List[Dict],
            round_num: int
        ) -> Tuple[Optional[str], Optional[str]]:
        max_attempts = self.max_retries + 1
        
        for attempt in range(max_attempts):
            current_input_tokens = self.llm_client.count_tokens(messages)
            
            logger.debug("[LLM Call] Round %d, attempt %d: input_tokens=%d, effective_max=%d, max_output=%d, context_limit=%d",
                         round_num, attempt + 1, current_input_tokens, self.effective_max_input_tokens, self.max_output_tokens, self.max_context_length)
            
            if current_input_tokens > self.effective_max_input_tokens:
                logger.info("🚫 [LLM Call] Input tokens exceeded limit: %d > %d, stopping",
                            current_input_tokens, self.effective_max_input_tokens)
                return None, AgentState.TOKEN_LIMIT_REACHED
            
            logger.debug("[LLM Call] Calling LLM with %d input tokens, max_output=%d", current_input_tokens, self.max_output_tokens)
            content = self.llm_client.call(messages)
            log_content = content.replace('\n', ' ')[:200] + '...' if len(content) > 200 else content.replace('\n', ' ')
            logger.info("🤖 LLM (%d chars): %s", len(content), log_content)
            
            resp_prediction, resp_state, should_continue, is_error = self._process_llm_response(
                content, messages
            )
            
            if not is_error:
                return (None, None) if should_continue else (resp_prediction, resp_state)
            
            error_type = "LLM error" if resp_state == AgentState.LLM_ERROR else "Format error"
            
            if attempt < self.max_retries:
                logger.warning("⚠️ %s, retrying (%d/%d)...", error_type, attempt + 1, self.max_retries)
                continue
            
            logger.warning("❌ %s, max retries exceeded for this round", error_type)
            if resp_state == AgentState.LLM_ERROR:
                messages.append({"role": "assistant", "content": content})
                return content.strip(), f"error:llm_error:round_{round_num}_retries({attempt + 1})"
            
            messages.append({"role": "assistant", "content": content.strip()})
            return self._force_final_answer(
                messages, f"format_error:round_{round_num}_retries({attempt + 1})"
            )
        
        return None, "error:unexpected"
    
    
    def _build_result(
            self,
            question: str,
            answer: str,
            task_id: str,
            prediction: str,
            termination: str,
            messages: List[Dict],
            rounds: int = 0,
        ) -> Union[AgentResult, Dict[str, Any]]:
        return AgentResult(
            task_id=task_id,
            question=question,
            answer=answer,
            prediction=prediction,
            termination=termination,
            messages=messages,
            rounds=rounds,
            execution_mode=self.execution_mode
        )
    
    
    def execute(
            self,
            question: str,
            answer: str = "",
            task_id: str = "",
            initial_messages: Optional[List[Dict]] = None,
            max_llm_calls_override: Optional[int] = None,
            **kwargs
        ) -> Union[AgentResult, Dict[str, Any]]:
        effective_max_llm_calls = max_llm_calls_override if max_llm_calls_override is not None else self.max_llm_calls
        if initial_messages is not None and len(initial_messages) >= 2:
            messages = [dict(m) for m in initial_messages]
        else:
            user_prompt = build_user_prompt(question)
            messages = [
                {"role": "system", "content": self.system_message},
                {"role": "user", "content": user_prompt}
            ]
        
        round_num = 0
        prediction = ""
        state = AgentState.RUNNING
        round_discarded = False
        full_messages = list(messages)
        self.context_manager.reset_discard_count()
        
        while state == AgentState.RUNNING:
            round_num += 1
            logger.info("─" * 60)
            logger.info("📍 Round %d", round_num)
            
            msg_snapshot = len(messages)
            
            limit_reason = self._check_round_limit(round_num, max_calls=effective_max_llm_calls)
            if limit_reason:
                if self.context_manager.verify_enabled and self.context_manager._answer_queue:
                    prediction = self.context_manager.verify_answers(question)
                    state = AgentState.FINISHED
                    logger.info("📊 Round limit reached, verify from %d queued answers",
                                len(self.context_manager._answer_queue))
                else:
                    prediction, state = self._force_final_answer(messages, limit_reason)
                full_messages.extend(messages[msg_snapshot:])
                continue
            
            if self.execution_mode == ExecutionMode.EFFICIENCY:
                if self._is_token_exceeded(messages):
                    prediction, state = self._force_final_answer(messages, AgentState.TOKEN_LIMIT_REACHED)
                    full_messages.extend(messages[msg_snapshot:])
                    continue
            else:
                messages, round_discarded, should_stop = self.context_manager.check_and_discard(
                    messages=messages,
                    user_question=question,
                    execution_mode=self.execution_mode,
                    already_discarded=round_discarded,
                )
                if should_stop:
                    if self.context_manager.verify_enabled and self.context_manager._answer_queue:
                        prediction = self.context_manager.verify_answers(question)
                        state = AgentState.FINISHED
                        logger.info("📊 TTS discard limit reached, verify from %d queued answers",
                                    len(self.context_manager._answer_queue))
                    else:
                        prediction, state = self._force_final_answer(messages, AgentState.TOKEN_LIMIT_REACHED)
                    full_messages.extend(messages[msg_snapshot:])
                    continue
            
            resp_prediction, resp_state = self._call_llm_for_round(messages, round_num)
            full_messages.extend(messages[msg_snapshot:])
            
            if resp_state is None:
                round_discarded = False
            
            if resp_state is not None:
                prediction, state = resp_prediction, resp_state

                tts_mode = (self.execution_mode == ExecutionMode.ACCURACY
                            and self.context_manager.enabled)

                if tts_mode and resp_state != AgentState.FINISHED:
                    logger.info("🔄 TTS: abnormal state=%s, discard and retry", resp_state)
                    messages = self.context_manager.force_discard(messages)
                    state = AgentState.RUNNING
                    prediction = ""

                elif tts_mode and resp_state == AgentState.FINISHED:
                    is_give_up = prediction and self.context_manager._is_give_up_answer(prediction)
                    logger.info("🔍 TTS: prediction[:80]=%r, is_give_up=%s",
                                prediction[:80] if prediction else None, is_give_up)
                    if is_give_up:
                        messages, round_discarded, _ = self.context_manager.check_and_discard(
                            messages=messages,
                            user_question=question,
                            execution_mode=self.execution_mode,
                            already_discarded=round_discarded,
                        )
                        state = AgentState.RUNNING
                        prediction = ""
                    elif self.context_manager.verify_enabled:
                        self.context_manager.add_answer(prediction)
                        if self.context_manager.check_early_stop() is not None or self.context_manager.should_verify():
                            prediction = self.context_manager.verify_answers(question)
                            state = AgentState.FINISHED
                        else:
                            messages = self.context_manager.force_discard(messages)
                            state = AgentState.RUNNING
                            prediction = ""
        
        return self._build_result(
            question=question,
            answer=answer,
            task_id=task_id,
            prediction=prediction or "No Answer",
            termination=state,
            messages=full_messages,
            rounds=round_num,
        )
