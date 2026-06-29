import io
import logging
import os
import re
import time
from typing import Optional, Tuple

import requests
from markdownify import MarkdownConverter, chomp
import PyPDF2


logger = logging.getLogger(__name__)


JINA_READER_URL_PREFIX = "https://r.jina.ai/"

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

_PLAINTEXT_MIMES = {
    "text/plain", "text/css", "text/csv", "text/javascript",
    "application/xml", "text/xml", "application/xhtml+xml",
    "application/rtf", "application/json", "application/ld+json",
}


class _LinkAwareMarkdownConverter(MarkdownConverter):

    def convert_a(self, el, text, parent_tags):
        if "_noformat" in parent_tags:
            return text
        prefix, suffix, text = chomp(text)
        if not text:
            text = ""
        href = el.get("href")
        title = el.get("title")
        if (
            self.options["autolinks"]
            and text.replace(r"\_", "_") == href
            and not title
            and not self.options["default_title"]
        ):
            return "<%s>" % href
        if self.options["default_title"] and not title:
            title = href
        title_part = ' "%s"' % title.replace('"', r"\"") if title else ""
        return (
            "%s[%s](%s%s)%s" % (prefix, text, href, title_part, suffix)
            if href
            else text
        )


def html_to_markdown(html: str, **options) -> str:
    return _LinkAwareMarkdownConverter(**options).convert(html)


def extract_text_from_pdf(pdf_file) -> Optional[str]:
    try:
        reader = PyPDF2.PdfReader(pdf_file)
        pages = [(page.extract_text() or "") for page in reader.pages]
        return "\n".join(pages).strip() or None
    except Exception as e:
        logger.error(f"[ReadPage] PDF extract failed: {e}")
        return None


def read_page_requests(
    url: str,
    timeout: int = 15,
    max_retries: int = 2,
) -> Optional[str]:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": (
            "text/html,application/xhtml+xml,application/xml;q=0.9,"
            "image/avif,image/webp,image/apng,*/*;q=0.8"
        ),
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br",
        "Connection": "keep-alive",
        "Referer": "https://www.google.com/",
        "Upgrade-Insecure-Requests": "1",
    }

    for attempt in range(max_retries):
        try:
            response = requests.get(url, headers=headers, timeout=timeout)
            response.raise_for_status()

            mime = response.headers.get("content-type", "").split(";")[0].strip()
            if mime == "application/pdf":
                content = extract_text_from_pdf(io.BytesIO(response.content))
            elif mime == "text/html":
                content = html_to_markdown(response.text).strip()
            elif mime in _PLAINTEXT_MIMES:
                content = response.text
            else:
                logger.warning(f"[ReadPage] unsupported content-type '{mime}' for {url}")
                return None

            if not content:
                return None
            return re.sub(r"\n{3,}", "\n\n", content)

        except Exception as e:
            logger.warning(
                f"[ReadPage] requests failed ({attempt + 1}/{max_retries}) {url}: {e}"
            )
            if attempt < max_retries - 1:
                time.sleep(1)

    return None


def read_page_jina(
    url: str,
    api_key: Optional[str] = None,
    timeout: int = 15,
    max_retries: int = 2,
) -> Optional[str]:
    api_key = api_key or os.getenv("JINA_API_KEY", "")
    if not api_key:
        logger.error("[ReadPage] JINA_API_KEY is not configured")
        return None

    headers = {"Authorization": f"Bearer {api_key}"}

    for attempt in range(max_retries):
        try:
            response = requests.get(
                f"{JINA_READER_URL_PREFIX}{url}",
                headers=headers,
                timeout=timeout,
            )
            if response.status_code == 200:
                logger.info(
                    f"[ReadPage] Jina ok ({len(response.text)} chars) url={url}"
                )
                return response.text
            logger.warning(
                f"[ReadPage] Jina non-200 ({attempt + 1}/{max_retries}) "
                f"status={response.status_code} url={url}"
            )
        except Exception as e:
            logger.warning(
                f"[ReadPage] Jina exception ({attempt + 1}/{max_retries}) url={url}: {e}"
            )
        if attempt < max_retries - 1:
            time.sleep(1)

    return None


def read_page(
    url: str,
    mode: str = "hybrid",
    timeout: int = 15,
    max_retries: int = 5,
    jina_api_key: Optional[str] = None,
) -> Tuple[Optional[str], str]:
    if mode == "requests":
        content = read_page_requests(url, timeout=timeout, max_retries=max_retries)
        return content, ("requests" if content else "")

    if mode == "jina":
        content = read_page_jina(
            url, api_key=jina_api_key, timeout=timeout, max_retries=max_retries
        )
        return content, ("jina" if content else "")

    if mode == "hybrid":
        content = read_page_requests(url, timeout=timeout, max_retries=max_retries)
        if content is not None:
            return content, "requests"
        logger.info(f"[ReadPage] requests failed for {url}, falling back to Jina")
        content = read_page_jina(
            url, api_key=jina_api_key, timeout=timeout, max_retries=max_retries
        )
        return content, ("jina" if content else "")

    raise ValueError(f"Unknown read_page mode: {mode!r} (expected: requests | jina | hybrid)")
