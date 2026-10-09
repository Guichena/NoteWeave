FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
RUN cp /etc/apt/sources.list.d/debian.sources /tmp/debian.sources \
    && awk 'BEGIN { RS=""; ORS="\n\n" } $0 !~ /debian-security/' /tmp/debian.sources > /etc/apt/sources.list.d/debian.sources \
    && sed -i 's#http://deb.debian.org#https://deb.debian.org#g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install --no-install-recommends -y \
        fonts-arphic-gbsn00lp \
        ffmpeg \
        libreoffice-impress \
        poppler-utils \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        texlive-lang-chinese \
        texlive-latex-extra \
        texlive-pictures \
        texlive-xetex \
    && mv /tmp/debian.sources /etc/apt/sources.list.d/debian.sources \
    && rm -rf /var/lib/apt/lists/*
RUN cp /etc/apt/sources.list.d/debian.sources /tmp/debian.sources \
    && awk 'BEGIN { RS=""; ORS="\n\n" } $0 !~ /debian-security/' /tmp/debian.sources > /etc/apt/sources.list.d/debian.sources \
    && sed -i 's#http://deb.debian.org#https://deb.debian.org#g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install --no-install-recommends -y texlive-fonts-recommended \
    && mv /tmp/debian.sources /etc/apt/sources.list.d/debian.sources \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir \
    "fastapi>=0.115.0,<1" \
    "kafka-python>=2.0.2,<4" \
    "pydantic-settings>=2.6.0,<3" \
    "Pillow>=11.0,<13" \
    "pypdf>=6.0,<7" \
    "python-pptx>=1.0.2,<2" \
    "faster-whisper>=1.2.1,<2" \
    "av>=15,<16" \
    "reportlab>=4.2.0,<5" \
    "yt-dlp>=2025.6.30,<2027" \
    "uvicorn>=0.32.0,<1"

COPY app ./app
COPY mcp ./mcp
RUN mkdir -p /app/runtime

EXPOSE 18092
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18092"]
