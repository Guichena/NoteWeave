FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN pip install --no-cache-dir \
    "fastapi>=0.115.0" \
    "kafka-python>=2.0.2" \
    "pydantic-settings>=2.6.0" \
    "uvicorn>=0.32.0"

COPY app ./app

EXPOSE 18091
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18091"]
