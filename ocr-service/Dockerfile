FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OCR_HOST=0.0.0.0 \
    OCR_PORT=8000

WORKDIR /app

COPY requirements-runtime.txt /app/requirements-runtime.txt
RUN pip install --no-cache-dir -r /app/requirements-runtime.txt \
    && pip install --no-cache-dir --no-deps rapidocr-onnxruntime==1.4.4

COPY sanity_ocr.py server.py /app/

RUN useradd --system --uid 10001 --no-create-home ocr

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"

USER 10001

CMD ["python", "/app/server.py"]
