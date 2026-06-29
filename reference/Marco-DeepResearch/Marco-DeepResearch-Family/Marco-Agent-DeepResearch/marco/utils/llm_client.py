import logging
import traceback
import os
from typing import Dict, List, Optional, Callable, Any

from openai import OpenAI

logger = logging.getLogger(__name__)

import tiktoken

from transformers import AutoTokenizer


CHARS_PER_TOKEN_ESTIMATE = 2.5
ESTIMATION_BUFFER_RATIO = 1.2


class LLMClient:
    
    def __init__(
            self,
            api_key: str,
            api_base: str,
            model_name: str,
            max_tokens: int = 8192,
            temperature: float = 0.7,
            top_p: float = 0.95,
            stop_sequences: Optional[List[str]] = None,
            max_retries: int = 5,
            tokenizer_path: Optional[str] = None,
        ):
        self.api_key = api_key
        self.api_base = api_base
        self.model_name = model_name
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p
        self.stop_sequences = stop_sequences or []
        self.max_retries = max_retries
        self.tokenizer_path = tokenizer_path
        
        self._tokenizer = None
        self._custom_tokenizer = None
    
    @property
    def tokenizer(self):
        if self._custom_tokenizer is None and self.tokenizer_path and os.path.exists(self.tokenizer_path):
            logger.info("[Tokenizer] Loading custom tokenizer from: %s", self.tokenizer_path)
            self._custom_tokenizer = AutoTokenizer.from_pretrained(self.tokenizer_path)
            logger.info("[Tokenizer] Custom tokenizer loaded successfully")
            return self._custom_tokenizer
        
        if self._tokenizer is None:
            try:
                self._tokenizer = tiktoken.encoding_for_model(self.model_name)
                logger.info("[Tokenizer] Using tiktoken for model: %s", self.model_name)
            except KeyError:
                self._tokenizer = tiktoken.get_encoding("cl100k_base")
                logger.info("[Tokenizer] Model %s not in tiktoken, using cl100k_base", self.model_name)
        return self._tokenizer
    
    def count_tokens(self, messages: List[Dict]) -> int:
        if self._custom_tokenizer is not None:
            try:
                formatted = self._custom_tokenizer.apply_chat_template(
                    messages, 
                    tokenize=False, 
                    add_generation_prompt=False
                )
                tokens = len(self._custom_tokenizer.encode(formatted, add_special_tokens=False))
                return tokens
            except Exception:
                full_text = ""
                for msg in messages:
                    full_text += f"{msg.get('role', '')}: {msg.get('content', '')}\n"
                tokens = len(self._custom_tokenizer.encode(full_text, add_special_tokens=False))
                return tokens
        
        full_text = ""
        for msg in messages:
            full_text += f"{msg.get('role', '')}: {msg.get('content', '')}\n"
        
        if self.tokenizer is not None:
            tokens = len(self.tokenizer.encode(full_text))
            return tokens
        
        estimated = int(len(full_text) / CHARS_PER_TOKEN_ESTIMATE * ESTIMATION_BUFFER_RATIO)
        return estimated
    
    def count_tokens_text(self, text: str) -> int:
        if self._custom_tokenizer is not None:
            return len(self._custom_tokenizer.encode(text, add_special_tokens=False))
        
        if self.tokenizer is not None:
            return len(self.tokenizer.encode(text))
        
        estimated = int(len(text) / CHARS_PER_TOKEN_ESTIMATE * ESTIMATION_BUFFER_RATIO)
        return estimated
    
    def _get_client(self) -> OpenAI:
        return OpenAI(
            api_key=self.api_key,
            base_url=self.api_base,
        )
    
    def call(
            self,
            messages: List[Dict],
            max_tries: Optional[int] = None,
            **kwargs
        ) -> str:
        max_tries = max_tries or self.max_retries
        client = self._get_client()
        
        stop_value = kwargs.get("stop", self.stop_sequences) or None
        call_params = {
            "model": kwargs.get("model", self.model_name),
            "messages": messages,
            "stop": stop_value,
            "temperature": kwargs.get("temperature", self.temperature),
            "top_p": kwargs.get("top_p", self.top_p),
            "max_tokens": kwargs.get("max_tokens", self.max_tokens),
        }
        
        logger.debug("LLM call with stop_sequences: %s", stop_value)
        
        for attempt in range(max_tries):
            try:
                response = client.chat.completions.create(**call_params)
                content = response.choices[0].message.content
                if content:
                    return content
            except Exception as e:
                if attempt < (max_tries - 1):
                    logger.warning("⚠️ LLM API error (attempt %d/%d): %s", attempt + 1, max_tries, e)
                else:
                    logger.error("❌ LLM API error (all %d attempts failed): %s", max_tries, e)
                    logger.error("❌ Traceback:\n%s", traceback.format_exc())
                    return "Call LLM API error"
                continue
        
        return "Call LLM API empty response"
    
    @classmethod
    def from_config(cls, config: Any) -> "LLMClient":
        tokenizer_path = getattr(config.model, 'tokenizer_path', None)
        
        return cls(
            api_key=config.model.api_key,
            api_base=config.model.api_base,
            model_name=config.model.model_name,
            max_tokens=config.generation.max_tokens,
            temperature=config.generation.temperature,
            top_p=config.generation.top_p,
            stop_sequences=config.generation.stop_sequences,
            max_retries=config.generation.max_retries,
            tokenizer_path=tokenizer_path,
        )
