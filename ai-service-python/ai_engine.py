"""Raj AI Studio — free AI image microservice (Flask, port 5000)."""

import io
import json
import os
import random
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

import requests
from flask import Flask, jsonify, request, send_from_directory

from prompt_enhancer import enhance_prompt

ROOT_DIR = Path(__file__).resolve().parent.parent
GENERATED_DIR = ROOT_DIR / "generated"
DATA_DIR = ROOT_DIR / "data"
CONFIG_FILE = DATA_DIR / "config.json"
HISTORY_FILE = DATA_DIR / "history.json"
ANALYTICS_FILE = DATA_DIR / "analytics.json"

POLLINATIONS_MODELS = {"flux-realism", "flux", "any-dark", "turbo", "zimage"}
POLLINATIONS_URL = "https://image.pollinations.ai/prompt/{prompt}"
HF_URL = "https://api-inference.huggingface.co/models/{model}"
REQUEST_TIMEOUT = int(os.getenv("AI_REQUEST_TIMEOUT", "120"))

app = Flask(__name__, static_folder=None)


def load_config():
    defaults = {
        "ports": {"java_port": 8080, "python_port": 5000},
        "defaultModel": "flux-realism",
        "apiEndpoints": {
            "generate": "/api/generate",
            "history": "/api/history",
            "styles": "/api/styles",
            "health": "/api/health",
        },
    }
    try:
        with CONFIG_FILE.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        defaults.update(data)
    except (OSError, ValueError):
        pass
    return defaults


CONFIG = load_config()
GENERATED_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR.mkdir(parents=True, exist_ok=True)


def json_error(message, status=400):
    return jsonify({"status": "error", "message": message}), status


def validate_payload(payload):
    if not isinstance(payload, dict):
        return "JSON object required"
    prompt = str(payload.get("prompt", "")).strip()
    if not prompt:
        return "prompt is required"
    if len(prompt) > 4000:
        return "prompt is too long (max 4000 characters)"
    try:
        width = int(payload.get("width", 768))
        height = int(payload.get("height", 768))
    except (TypeError, ValueError):
        return "width and height must be numbers"
    if not 256 <= width <= 2048 or not 256 <= height <= 2048:
        return "width and height must be between 256 and 2048"
    model = str(payload.get("model", CONFIG.get("defaultModel", "flux-realism"))).strip()
    engine = str(payload.get("engine", "pollinations")).strip().lower()
    if not model or len(model) > 120:
        return "model must be a non-empty string (max 120 chars)"
    if engine in ("", "pollinations") and model not in POLLINATIONS_MODELS:
        return f"unsupported pollinations model: {model}"
    return None


def call_pollinations(prompt, width, height, seed, model):
    url = POLLINATIONS_URL.format(prompt=quote(prompt))
    params = {
        "width": width,
        "height": height,
        "seed": seed,
        "model": model,
        "nologo": "true",
        "enhance": "true",
    }
    response = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
    response.raise_for_status()
    return response.content


def call_huggingface(prompt, model, token):
    headers = {"Authorization": f"Bearer {token}"}
    payload = {"inputs": prompt}
    url = HF_URL.format(model=model)
    response = requests.post(url, headers=headers, json=payload, timeout=REQUEST_TIMEOUT)
    if response.status_code == 503:
        raise RuntimeError("Hugging Face model is loading; please retry in a moment")
    response.raise_for_status()
    return response.content


def save_image(content, seed, extension="png"):
    """Validate image bytes with Pillow, then persist them under generated/."""
    from PIL import Image

    try:
        image = Image.open(io.BytesIO(content))
        image.verify()
    except Exception as exc:  # pragma: no cover - depends on remote bytes
        raise RuntimeError(f"AI service returned invalid image data: {exc}")

    filename = f"raj-ai-{int(time.time())}-{seed}.{extension}"
    path = GENERATED_DIR / filename
    path.write_bytes(content)
    return "./generated/" + filename


def append_json_file(path, key, record, limit=200):
    data = {}
    if path.exists() and path.stat().st_size:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                data = {key: data}
        except ValueError:
            data = {}
    records = data.get(key, [])
    if not isinstance(records, list):
        records = []
    records.append(record)
    data[key] = records[-limit:]
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def client_ip():
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.headers.get("X-Real-IP", request.remote_addr or "unknown")


@app.after_request
def add_cors(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    response.headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS"
    return response


@app.route("/health", methods=["GET"])
@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "service": "python-ai-engine",
        "defaultModel": CONFIG.get("defaultModel", "flux-realism"),
        "hfConfigured": bool(os.getenv("HF_API_KEY")),
    })


@app.route("/api/styles", methods=["GET", "OPTIONS"])
def styles():
    if request.method == "OPTIONS":
        return "", 204
    from prompt_enhancer import load_styles
    return jsonify({"status": "success", "styles": load_styles()})


@app.route("/api/generate", methods=["POST", "OPTIONS"])
def generate():
    if request.method == "OPTIONS":
        return "", 204

    payload = request.get_json(silent=True)
    error = validate_payload(payload)
    if error:
        return json_error(error)

    width = int(payload.get("width", 768))
    height = int(payload.get("height", 768))
    seed = int(payload.get("seed") or random.randint(1, 999_999_999))
    model = str(payload.get("model", CONFIG.get("defaultModel", "flux-realism")))
    prompt = enhance_prompt(
        payload["prompt"],
        style=payload.get("style"),
        custom_suffix=payload.get("suffix"),
        realism=bool(payload.get("realism", True)),
    )

    engine_req = str(payload.get("engine", "pollinations")).strip().lower()
    hf_token = (str(payload.get("hfToken", "")).strip() or os.getenv("HF_API_KEY", "").strip())
    use_hf = engine_req == "huggingface" or bool(payload.get("useHuggingFace")) or str(payload.get("provider", "")).lower() in {"hf", "huggingface"}
    try:
        if use_hf and hf_token:
            hf_model = model if "/" in model else "black-forest-labs/FLUX.1-schnell"
            content = call_huggingface(prompt, hf_model, hf_token)
            engine = f"huggingface:{hf_model}"
        else:
            poll_model = model if model in POLLINATIONS_MODELS else CONFIG.get("defaultModel", "flux-realism")
            content = call_pollinations(prompt, width, height, seed, poll_model)
            engine = poll_model
        image_url = save_image(content, seed)
    except requests.RequestException as exc:
        return json_error(f"Image engine request failed: {exc}", 502)
    except RuntimeError as exc:
        return json_error(str(exc), 502)
    except Exception as exc:
        return json_error(f"Unexpected error: {exc}", 500)

    append_json_file(
        HISTORY_FILE,
        "history",
        {
            "prompt": str(payload.get("originalPrompt", payload["prompt"])),
            "enhancedPrompt": prompt,
            "imageUrl": image_url,
            "seed": seed,
            "width": width,
            "height": height,
            "model": engine,
            "style": str(payload.get("style", "")),
            "timestamp": datetime.now(timezone.utc).isoformat(),
        },
    )
    append_json_file(
        ANALYTICS_FILE,
        "generations",
        {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "ip": client_ip(),
            "userAgent": request.headers.get("User-Agent", ""),
            "model": engine,
            "prompt": str(payload.get("originalPrompt", payload["prompt"]))[:180],
        },
        limit=1000,
    )

    return jsonify({
        "status": "success",
        "imageUrl": image_url,
        "seed": seed,
        "engine": engine,
        "model": model,
        "width": width,
        "height": height,
        "prompt": prompt,
    })


@app.route("/api/history", methods=["GET"])
def history():
    try:
        data = json.loads(HISTORY_FILE.read_text(encoding="utf-8")) if HISTORY_FILE.exists() else {}
        records = data.get("history", []) if isinstance(data, dict) else data
    except ValueError:
        records = []
    return jsonify({"status": "success", "history": records[-100:]})


@app.route("/generated/<path:filename>")
def generated_file(filename):
    return send_from_directory(GENERATED_DIR, filename)


@app.route("/")
def index():
    return send_from_directory(ROOT_DIR, "index.html")


@app.route("/<path:filename>")
def static_files(filename):
    allowed = {"index.html", "admin.html"}
    if filename.startswith(("css/", "js/", "assets/", "data/")) or filename in allowed:
        return send_from_directory(ROOT_DIR, filename)
    return json_error("Not found", 404)


if __name__ == "__main__":
    port = int(CONFIG.get("ports", {}).get("python_port", 5000))
    print("=" * 64)
    print("  Raj AI Studio — Python AI Microservice")
    print(f"  URL      : http://127.0.0.1:{port}")
    print(f"  Engine   : Pollinations (default: {CONFIG.get('defaultModel', 'flux-realism')})")
    print(f"  HF token : {'configured' if os.getenv('HF_API_KEY') else 'not set (optional)'}")
    print("=" * 64)
    app.run(host="127.0.0.1", port=port, debug=False, threaded=True)
