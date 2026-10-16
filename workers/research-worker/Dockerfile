FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN pip install --no-cache-dir \
    "kafka-python>=2.0.2" \
    "openai>=2.0,<3" \
    "pydantic-settings>=2.6.0" \
    "tldextract>=5.1,<6"

COPY app ./app

CMD ["python", "-m", "app.agent_kafka_consumer"]
