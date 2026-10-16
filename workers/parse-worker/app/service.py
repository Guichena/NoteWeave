"""有界请求和共享模型转换；超时请求保留占用直到真实转换退出。"""

from concurrent.futures import ThreadPoolExecutor
import logging
from pathlib import Path
import shutil
import tempfile
import threading

from app.converter import build_converter
from app.errors import ParseFailure
from app.serializer import serialize_document


logger = logging.getLogger(__name__)


class ParseService:
    def __init__(self, settings, converter_factory=build_converter):
        self.settings = settings
        self.converter_factory = converter_factory
        self.converter = None
        self.capacity = threading.BoundedSemaphore(settings.max_concurrency)
        self.model_lock = threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=settings.max_concurrency, thread_name_prefix="docling-parse")
        self.temp_root = Path(settings.temp_root or tempfile.gettempdir()).resolve()
        self.temp_root.mkdir(parents=True, exist_ok=True)

    def acquire(self):
        return self.capacity.acquire(blocking=False)

    def new_upload(self, suffix):
        directory = Path(tempfile.mkdtemp(prefix="noteweave-parse-", dir=self.temp_root))
        return directory, directory / ("input" + suffix)

    def cleanup(self, directory):
        # 路径由 tempfile 创建，并在删除前核对边界；不使用用户文件名决定清理路径。
        resolved = Path(directory).resolve()
        if resolved.parent != self.temp_root or not resolved.name.startswith("noteweave-parse-"):
            raise RuntimeError("解析临时目录不在已授权清理范围内")
        shutil.rmtree(resolved, ignore_errors=False)

    def submit(self, directory, path, metadata, expected_format, max_chars):
        future = self.executor.submit(self.convert, path, metadata, expected_format, max_chars)
        def completed(_future):
            try:
                self.cleanup(directory)
            except OSError:
                logger.warning("解析临时目录清理失败")
            finally:
                self.capacity.release()
        future.add_done_callback(completed)
        return future

    def convert(self, path, metadata, expected_format, max_chars):
        # Docling 的缓存模型由进程共享，转换及元素读取必须串行。
        with self.model_lock:
            if self.converter is None:
                self.converter = self.converter_factory(self.settings)
            result = self.converter.convert(Path(path), raises_on_error=True,
                                            max_file_size=self.settings.max_upload_bytes)
            status = str(getattr(getattr(result, "status", None), "value", getattr(result, "status", "")))
            if status.lower() != "success":
                raise ParseFailure(422, "DOCLING_CONVERSION_FAILED", "Docling 未完整转换文档")
            actual_format = getattr(getattr(result, "input", None), "format", None)
            actual_format = str(getattr(actual_format, "value", actual_format)).upper()
            if actual_format != expected_format:
                raise ParseFailure(415, "DOCUMENT_TYPE_MISMATCH", "实际文档格式与声明类型不一致")
            return serialize_document(result.document, metadata, max_chars, self.settings.max_elements,
                                      conversion_result=result)

    def close(self):
        self.executor.shutdown(wait=False, cancel_futures=True)
