import asyncio
from contextlib import asynccontextmanager
from hashlib import sha256 as hash_sha256
from pathlib import PurePath
import secrets
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from starlette.requests import ClientDisconnect

from app.config import MAX_OUTPUT_CHARS, Settings
from app.converter import FORMATS
from app.errors import ParseFailure
from app.models import ParseResponse
from app.service import ParseService


def create_app(settings=None, *, converter_factory=None):
    configured = settings or Settings()
    service = ParseService(configured, **({"converter_factory": converter_factory} if converter_factory else {}))

    @asynccontextmanager
    async def lifespan(_app):
        yield
        service.close()

    application = FastAPI(title="NoteWeave Parse Worker", lifespan=lifespan)
    application.state.parse_service = service

    async def require_token(request: Request):
        expected = configured.internal_auth_token.get_secret_value()
        if not expected:
            raise HTTPException(503, detail={"code": "INTERNAL_AUTH_NOT_CONFIGURED", "message": "解析服务鉴权尚未配置"})
        provided = request.headers.get("x-noteweave-internal-token", "")
        if not secrets.compare_digest(provided.encode("utf-8"), expected.encode("utf-8")):
            raise HTTPException(401, detail={"code": "UNAUTHORIZED", "message": "内部鉴权令牌不匹配"})

    @application.get("/health")
    async def health():
        return {"status": "UP", "parser": "DOCLING"}

    @application.post("/internal/parse", response_model=ParseResponse, dependencies=[Depends(require_token)])
    async def parse(request: Request,
            workspace_id: Annotated[str, Query(min_length=1, max_length=128)],
            source_id: Annotated[str, Query(min_length=1, max_length=128)],
            snapshot_id: Annotated[str, Query(min_length=1, max_length=128)],
            file_name: Annotated[str, Query(min_length=1, max_length=300)],
            mime_type: Annotated[str, Query(min_length=1, max_length=255)],
            sha256: Annotated[str, Query(pattern=r"^[0-9a-f]{64}$")],
            max_chars: Annotated[int | None, Query(ge=1, le=MAX_OUTPUT_CHARS)] = None):
        if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/octet-stream":
            raise HTTPException(415, detail={"code": "INVALID_CONTENT_TYPE", "message": "请求体必须为 application/octet-stream"})
        if any(character in file_name for character in ("/", "\\", ":", "\x00")) or file_name in {".", ".."}:
            raise HTTPException(422, detail={"code": "INVALID_FILE_NAME", "message": "仅接受本地文件名，不能提交路径或 URL"})
        suffix = PurePath(file_name).suffix.lower()
        format_entry = FORMATS.get(suffix)
        if format_entry is None or mime_type.lower() not in format_entry[1]:
            raise HTTPException(415, detail={"code": "UNSUPPORTED_DOCUMENT_TYPE", "message": "文档扩展名或 MIME 类型不受支持"})
        content_length = request.headers.get("content-length")
        if content_length is not None:
            try:
                declared_size = int(content_length)
            except ValueError:
                raise HTTPException(400, detail={"code": "INVALID_CONTENT_LENGTH", "message": "Content-Length 无效"}) from None
            if declared_size < 0 or declared_size > configured.max_upload_bytes:
                raise HTTPException(413, detail={"code": "PARSE_INPUT_LIMIT_EXCEEDED", "message": "上传文档超过大小上限"})
        if not service.acquire():
            raise HTTPException(429, detail={"code": "PARSE_CAPACITY_EXHAUSTED", "message": "解析服务正在处理其他文档"},
                                headers={"Retry-After": "1"})
        directory, submitted = None, False
        try:
            directory, path = service.new_upload(suffix)
            digest, total = hash_sha256(), 0
            async with asyncio.timeout(configured.upload_timeout_seconds):
                with path.open("wb") as stream:
                    async for chunk in request.stream():
                        total += len(chunk)
                        if total > configured.max_upload_bytes:
                            raise ParseFailure(413, "PARSE_INPUT_LIMIT_EXCEEDED", "实际上传文档超过大小上限")
                        digest.update(chunk)
                        stream.write(chunk)
            if not total:
                raise ParseFailure(422, "EMPTY_DOCUMENT", "上传文档不能为空")
            if digest.hexdigest() != sha256:
                raise ParseFailure(422, "DOCUMENT_HASH_MISMATCH", "实际上传内容与声明的 SHA-256 不一致")
            metadata = {"workspace_id": workspace_id, "source_id": source_id, "snapshot_id": snapshot_id,
                        "sha256": sha256, "mime_type": mime_type}
            future = service.submit(directory, path, metadata, format_entry[0],
                                    min(max_chars or configured.max_output_chars, configured.max_output_chars))
            submitted = True
            wrapped = asyncio.wrap_future(future)
            # 即便客户端已超时，后台失败也要被读取；容量和文件由真实完成回调释放。
            wrapped.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
            return await asyncio.wait_for(asyncio.shield(wrapped), timeout=configured.document_timeout_seconds)
        except ParseFailure as exc:
            raise HTTPException(exc.status_code, detail=exc.detail()) from exc
        except TimeoutError:
            raise HTTPException(504, detail={"code": "PARSE_TIMEOUT", "message": "文档上传或本地转换超时"}) from None
        except ClientDisconnect:
            raise HTTPException(400, detail={"code": "UPLOAD_INTERRUPTED", "message": "上传连接已中断"}) from None
        except Exception:
            raise HTTPException(422, detail={"code": "DOCLING_CONVERSION_FAILED", "message": "本地文档转换失败"}) from None
        finally:
            if not submitted:
                try:
                    if directory is not None:
                        service.cleanup(directory)
                finally:
                    service.capacity.release()

    return application


app = create_app()
