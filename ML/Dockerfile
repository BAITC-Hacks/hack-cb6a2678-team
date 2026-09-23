FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY windml ./windml
COPY models ./models
COPY data ./data
COPY outputs/training ./outputs/training
ENV WINDML_ROOT=/app PYTHONUNBUFFERED=1 WINDML_OFFLINE=1
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["uvicorn", "windml.api:app", "--host", "0.0.0.0", "--port", "8000"]
