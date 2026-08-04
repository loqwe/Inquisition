import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from sanity_ocr import MAX_IMAGE_BYTES, SanityOcr


OCR = SanityOcr()


class OcrHandler(BaseHTTPRequestHandler):
    server_version = "InquisitionSanityOcr/1.0"

    def do_GET(self):
        if self.path != "/health":
            self.send_json(404, {"error": "not found"})
            return
        self.send_json(200, {"status": "ok"})

    def do_POST(self):
        if self.path != "/v1/sanity":
            self.send_json(404, {"error": "not found"})
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            content_length = 0
        if content_length <= 0 or content_length > MAX_IMAGE_BYTES:
            self.send_json(413, {"error": "invalid image size"})
            return
        content_type = self.headers.get("Content-Type", "").lower()
        if not (content_type.startswith("image/") or content_type.startswith("application/octet-stream")):
            self.send_json(415, {"error": "unsupported media type"})
            return
        try:
            result = OCR.recognize_bytes(self.rfile.read(content_length))
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
            return
        except Exception:
            self.send_json(500, {"error": "ocr inference failed"})
            return
        if result is None:
            self.send_json(422, {"error": "no consistent sanity reading"})
            return
        self.send_json(200, {
            "currentSanity": result.current_sanity,
            "maxSanity": result.max_sanity,
            "confidence": result.confidence,
            "votes": result.votes,
        })

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("ascii")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string, *args):
        print("ocr-http", self.address_string(), format_string % args, flush=True)


if __name__ == "__main__":
    host = os.getenv("OCR_HOST", "0.0.0.0")
    port = int(os.getenv("OCR_PORT", "8000"))
    ThreadingHTTPServer((host, port), OcrHandler).serve_forever()
