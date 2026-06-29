import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List, Optional, Tuple, Union

import requests

from .base import BaseTool, register_tool


logger = logging.getLogger(__name__)


DEFAULT_SEARCH_API_URL = "https://google.serper.dev/search"


@register_tool("search")
class SearchTool(BaseTool):

    name = "search"
    description = "Search the web via Google to find relevant information and URLs."
    parameters = {
        "type": "object",
        "properties": {
            "querys": {
                "type": "array",
                "items": {"type": "string"},
                "description": (
                    "Search queries for finding relevant information. "
                    "Supports single or multiple queries."
                ),
            },
        },
        "required": ["querys"],
    }

    def __init__(
        self,
        api_key: Optional[str] = None,
        api_url: Optional[str] = None,
        timeout: int = 20,
        max_retries: int = 5,
        max_workers: int = 10,
        max_results_per_query: int = 10,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.api_key = api_key or os.getenv("GOOGLE_SEARCH_KEY", "")
        self.api_url = api_url or os.getenv("SEARCH_API_URL", DEFAULT_SEARCH_API_URL)
        self.timeout = timeout
        self.max_retries = max_retries
        self.max_workers = max_workers
        self.max_results_per_query = max_results_per_query

    def call(self, params: Union[str, dict], **kwargs) -> str:
        try:
            if isinstance(params, str):
                params = json.loads(params)
            querys = params["querys"]
        except (json.JSONDecodeError, KeyError, TypeError):
            return "[Google Search] Request parameters are incorrect; the `querys` field is required."

        if isinstance(querys, str):
            querys = [querys]
        if not querys:
            return "[Google Search] `querys` must not be empty."
        if not self.api_key:
            return "[Google Search] GOOGLE_SEARCH_KEY is not configured."

        logger.info(f"[Google Search] batch {len(querys)} queries (max_results={self.max_results_per_query})")

        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            results = list(executor.map(self._search_single_query, querys))

        all_results: Dict[str, List[Dict[str, Any]]] = {}
        failed: List[str] = []
        total = 0
        for query, items, ok in results:
            all_results[query] = items
            total += len(items)
            if not ok:
                failed.append(query)

        return self._format(all_results, total, querys, failed)

    def _search_single_query(self, query: str) -> Tuple[str, List[Dict[str, Any]], bool]:
        headers = {
            "X-API-KEY": self.api_key,
            "Content-Type": "application/json",
        }
        payload = {
            "q": query,
            "num": self.max_results_per_query,
        }

        for attempt in range(self.max_retries):
            try:
                response = requests.post(
                    self.api_url, headers=headers, json=payload, timeout=self.timeout
                )
                response.raise_for_status()
                data = response.json() if response.content else {}

                if isinstance(data, dict):
                    items = data.get("organic", data.get("items", []))
                elif isinstance(data, list):
                    items = data
                else:
                    items = []

                parsed = [
                    {
                        "title": item.get("title", ""),
                        "link": item.get("link", item.get("url", "")),
                        "snippet": item.get("snippet", item.get("description", "")),
                        "position": item.get("position", i + 1),
                    }
                    for i, item in enumerate(items[: self.max_results_per_query])
                ]
                return query, parsed, True

            except Exception as e:
                logger.warning(
                    f"[Google Search] query={query!r} attempt {attempt + 1}/{self.max_retries}: {e}"
                )
                if attempt < self.max_retries - 1:
                    time.sleep(1)

        return query, [], False

    @staticmethod
    def _format(
        results: Dict[str, List[Dict[str, Any]]],
        total: int,
        querys: List[str],
        failed: List[str],
    ) -> str:
        lines = [f"[Search] Queries:{len(querys)} Results:{total} Failed:{len(failed)}"]
        for query in querys:
            items = results.get(query, [])
            lines.append("")
            lines.append(f"Query: {query} ({len(items)} results)")
            for i, item in enumerate(items, 1):
                lines.append(f"{i}. {item.get('title', '')}|{item.get('link', '')}")
                snippet = item.get("snippet", "")
                if snippet:
                    lines.append(snippet)
        if failed:
            lines.append("Failed: " + ", ".join(failed))
        return "\n".join(lines)
