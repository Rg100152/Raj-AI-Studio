"""Prompt realism enhancer and style injector for Raj AI Studio."""

import json
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
STYLES_FILE = ROOT_DIR / "data" / "styles.json"

REALISM_SUFFIX = (
    "photorealistic, ultra-detailed, 8k resolution, natural lighting, "
    "realistic textures, sharp focus, 35mm lens"
)


def load_styles():
    """Return the style presets from data/styles.json with a safe fallback."""
    fallback = [
        {"id": "photorealistic", "label": "Photorealistic", "promptSuffix": "photorealistic, natural lighting, 35mm lens"},
        {"id": "cyberpunk", "label": "Cyberpunk", "promptSuffix": "cyberpunk, neon lights, rainy futuristic city"},
        {"id": "anime", "label": "Anime", "promptSuffix": "anime style, vibrant colors, clean line art"},
        {"id": "3d-render", "label": "3D Render", "promptSuffix": "3D render, octane, soft studio lighting"},
        {"id": "cinematic", "label": "Cinematic", "promptSuffix": "cinematic still, dramatic lighting, depth of field"},
        {"id": "dark-hacker", "label": "Dark Hacker", "promptSuffix": "dark hacker aesthetic, terminal glow, green matrix light"},
    ]
    try:
        with STYLES_FILE.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        styles = data.get("styles", data) if isinstance(data, dict) else data
        return styles if isinstance(styles, list) and styles else fallback
    except (OSError, ValueError):
        return fallback


def style_suffix(style_id):
    """Look up the prompt suffix for a style preset id or label."""
    for style in load_styles():
        if not isinstance(style, dict):
            continue
        if str(style.get("id", "")).lower() == str(style_id).lower() or \
           str(style.get("label", "")).lower() == str(style_id).lower():
            return style.get("promptSuffix") or style.get("suffix") or ""
    return ""


def enhance_prompt(prompt, style=None, custom_suffix=None, realism=True):
    """Build the final prompt sent to the image engine."""
    base = (prompt or "").strip()
    if not base:
        raise ValueError("Prompt must not be empty")

    pieces = [base]
    suffix = (custom_suffix or style_suffix(style or "")).strip()
    if suffix and suffix.lower() not in base.lower():
        pieces.append(suffix.strip(" ,"))
    if realism and REALISM_SUFFIX.lower() not in base.lower():
        pieces.append(REALISM_SUFFIX)

    final = ", ".join(pieces)
    return final[:4000]
