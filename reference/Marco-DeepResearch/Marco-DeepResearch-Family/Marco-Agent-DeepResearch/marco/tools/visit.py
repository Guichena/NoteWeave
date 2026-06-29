import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional, Union

from openai import OpenAI

from .base import BaseTool, register_tool
from .readpage import read_page


logger = logging.getLogger(__name__)


WEBPAGE_EXTRACTOR_PROMPT = """Extract information from the webpage based on the goal.

## Content
{webpage_content}

## Goal
{goal}

## Instructions
1. Locate sections in the content that are relevant to the goal
2. Extract key facts, data, and necessary context:
   - For factual queries: provide specific answers with source details
   - For verification: state whether the claim is supported, contradicted, or not mentioned
   - For broad topics: summarize the most important points
3. If relevant information is not found:
   - Set found=false
   - If page contains potential links or URL patterns that might help, mention them
4. Be complete (include all essential information) but concise (omit irrelevant details)

## Output (JSON)
{{"found": true/false, "content": "extracted information with brief supporting context"}}
"""


@register_tool("visit")
class VisitTool(BaseTool):

    name = "visit"
    description = "Read webpage content to extract specific information, verify claims, or understand context."
    parameters = {
        "type": "object",
        "properties": {
            "urls": {
                "type": "array",
                "items": {"type": "string"},
                "description": "URL(s) to visit. Supports single or multiple urls.",
            },
            "goal": {
                "type": "string",
                "description": "The specific information to retrieve. Be precise, not vague.",
            },
        },
        "required": ["urls", "goal"],
    }

    def __init__(
        self,
        mode: str = "hybrid",
        timeout: int = 15,
        max_retries: int = 5,
        max_workers: int = 3,
        max_content_length: int = 102400 * 4,
        jina_api_key: Optional[str] = None,
        summary_api_key: Optional[str] = None,
        summary_base_url: Optional[str] = None,
        summary_model: Optional[str] = None,
        summary_max_tokens: int = 4096,
        summary_max_retries: int = 3,
        summary_stop: Optional[List[str]] = None,
        summary_extra_body: Optional[dict] = None,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.mode = mode
        self.timeout = timeout
        self.max_retries = max_retries
        self.max_workers = max_workers
        self.max_content_length = max_content_length
        self.jina_api_key = jina_api_key or os.getenv("JINA_API_KEY", "")
        self.summary_api_key = summary_api_key or os.getenv("SUMMARY_MODEL_OPENAI_API_KEY", "")
        self.summary_base_url = summary_base_url or os.getenv("SUMMARY_MODEL_OPENAI_BASE_URL", "")
        self.summary_model = summary_model or os.getenv("SUMMARY_MODEL_NAME", "")
        self.summary_max_tokens = summary_max_tokens
        self.summary_max_retries = summary_max_retries
        self.summary_stop = summary_stop
        self.summary_extra_body = summary_extra_body

    def call(self, params: Union[str, dict], **kwargs) -> str:
        try:
            if isinstance(params, str):
                params = json.loads(params)
            urls = params["urls"]
            goal = params["goal"]
        except (json.JSONDecodeError, KeyError, TypeError):
            return (
                "[Web Visit] Request parameters are incorrect; "
                "the request must include the `urls` and `goal` fields."
            )

        if isinstance(urls, str):
            urls = [urls]
        if not urls:
            return "[Web Visit] `urls` must not be empty."

        logger.info(f"[Web Visit] batch {len(urls)} urls, goal={goal!r}")

        responses: List[str] = []
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = {executor.submit(self._visit_single_page, url, goal): url for url in urls}
            for fut in as_completed(futures):
                url = futures[fut]
                try:
                    responses.append(fut.result())
                except Exception as e:
                    logger.error(f"[Web Visit] error on {url}: {e}")
                    responses.append(self._format_fetch_failed(url))

        return "\n=======\n".join(responses).strip()

    def _visit_single_page(self, url: str, goal: str) -> str:
        content, service = read_page(
            url=url,
            mode=self.mode,
            timeout=self.timeout,
            max_retries=self.max_retries,
            jina_api_key=self.jina_api_key,
        )
        if content is None:
            return self._format_fetch_failed(url)

        logger.info(f"[Web Visit] fetched via={service} len={len(content)} url={url}")
        truncated = self._truncate(content, self.max_content_length)
        return self._summarize(url, truncated, goal)

    def _summarize(self, url: str, content: str, goal: str) -> str:
        if not (self.summary_api_key and self.summary_base_url and self.summary_model):
            logger.error(
                "[Web Visit] summary LLM is not configured; set "
                "SUMMARY_MODEL_OPENAI_API_KEY / SUMMARY_MODEL_OPENAI_BASE_URL / SUMMARY_MODEL_NAME"
            )
            return self._format_summary_failed(url)

        client = OpenAI(api_key=self.summary_api_key, base_url=self.summary_base_url)

        prompt = WEBPAGE_EXTRACTOR_PROMPT.format(webpage_content=content, goal=goal)
        messages = [{"role": "user", "content": prompt}]
        extra_kwargs: dict = {}
        if self.summary_stop:
            extra_kwargs["stop"] = self.summary_stop
        if self.summary_extra_body:
            extra_kwargs["extra_body"] = self.summary_extra_body

        last_err: Optional[str] = None
        for attempt in range(self.summary_max_retries):
            try:
                response = client.chat.completions.create(
                    model=self.summary_model,
                    messages=messages,
                    max_tokens=self.summary_max_tokens,
                    **extra_kwargs,
                )
                raw = (response.choices[0].message.content or "").strip()
                if not raw:
                    last_err = "empty response content"
                    logger.warning(f"[Web Visit] summary empty ({attempt + 1}/{self.summary_max_retries})")
                    continue

                parsed = self._parse_json(raw)
                if parsed is None:
                    last_err = "failed to parse JSON"
                    logger.warning(
                        f"[Web Visit] summary JSON parse failed ({attempt + 1}/{self.summary_max_retries})"
                    )
                    continue

                return self._format_success(
                    url,
                    bool(parsed.get("found", False)),
                    str(parsed.get("content", "")).strip(),
                )

            except Exception as e:
                last_err = str(e)
                logger.warning(
                    f"[Web Visit] summary retry {attempt + 1}/{self.summary_max_retries}: {e}"
                )
                if attempt < self.summary_max_retries - 1:
                    time.sleep(1)

        logger.error(f"[Web Visit] summary failed after {self.summary_max_retries} retries: {last_err}")
        return self._format_summary_failed(url)

    @staticmethod
    def _parse_json(raw: str) -> Optional[dict]:
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            left, right = raw.find("{"), raw.rfind("}")
            if left == -1 or right == -1 or left >= right:
                return None
            try:
                return json.loads(raw[left : right + 1])
            except json.JSONDecodeError:
                return None

    @staticmethod
    def _truncate(content: str, max_length: int) -> str:
        if len(content) <= max_length:
            return content
        half = max_length // 2
        return (
            content[:half]
            + f"\n..._This content has been truncated to stay below {max_length} characters_...\n"
            + content[-half:]
        )

    @staticmethod
    def _format_fetch_failed(url: str) -> str:
        return f"[Source] {url}\n[Status] Failed\n[Reason] Unable to fetch webpage content.\n"

    @staticmethod
    def _format_summary_failed(url: str) -> str:
        return f"[Source] {url}\n[Status] Failed\n[Reason] Failed to extract information from webpage.\n"

    @staticmethod
    def _format_success(url: str, found: bool, content: str) -> str:
        result = f"[Source] {url}\n[Status] Success\n"
        if found and content:
            result += f"[Content] {content}\n"
        else:
            result += "[Content] No relevant information found on this page.\n"
        return result
