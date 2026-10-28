FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HF_HUB_DISABLE_XET=1
WORKDIR /app
RUN apt-get update && apt-get install --no-install-recommends -y \
    ffmpeg tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml ./
COPY media_mcp ./media_mcp
RUN pip install --no-cache-dir .
EXPOSE 18094
CMD ["uvicorn", "media_mcp.server:app", "--host", "0.0.0.0", "--port", "18094"]
