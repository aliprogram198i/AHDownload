from __future__ import annotations

import asyncio
import ipaddress
import os
import re
import time
from urllib.parse import urlparse

import yt_dlp
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, HttpUrl

app = FastAPI(title="AHDownload Resolver", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

ALLOWED_HOSTS = {
    "youtube.com", "youtu.be", "instagram.com", "facebook.com", "fb.watch",
    "tiktok.com", "twitter.com", "x.com", "vimeo.com", "reddit.com",
}

RATE_WINDOW = 60
RATE_LIMIT = 30
_hits: dict[str, list[float]] = {}

class ResolveRequest(BaseModel):
    url: HttpUrl

def host_allowed(host: str) -> bool:
    host = host.lower().split(":")[0].rstrip(".")
    return any(host == d or host.endswith("." + d) for d in ALLOWED_HOSTS)

def check_rate(client: str) -> None:
    now = time.time()
    hits = [t for t in _hits.get(client, []) if now - t < RATE_WINDOW]
    if len(hits) >= RATE_LIMIT:
        raise HTTPException(429, "rate_limited")
    hits.append(now)
    _hits[client] = hits

def size_of(f: dict) -> int | None:
    for key in ("filesize", "filesize_approx"):
        value = f.get(key)
        if isinstance(value, int) and value > 0:
            return value
    return None

def normalize(info: dict) -> dict:
    formats = []
    for f in info.get("formats") or []:
        url = f.get("url")
        if not url:
            continue
        vcodec = f.get("vcodec")
        acodec = f.get("acodec")
        has_video = bool(vcodec and vcodec != "none")
        has_audio = bool(acodec and acodec != "none")
        if not (has_video or has_audio):
            continue
        ext = (f.get("ext") or "").lower()
        if ext not in {"mp4", "webm", "m4a", "mp3", "aac", "opus", "ogg", "mov", "mkv"}:
            continue
        formats.append({
            "id": str(f.get("format_id") or ""),
            "ext": ext,
            "mime": f"video/{ext}" if has_video else f"audio/{ext}",
            "width": f.get("width"),
            "height": f.get("height"),
            "fps": f.get("fps"),
            "abr": f.get("abr"),
            "tbr": f.get("tbr"),
            "sizeBytes": size_of(f),
            "hasVideo": has_video,
            "hasAudio": has_audio,
            "codec": vcodec if has_video else acodec,
            "url": url,
        })
    formats.sort(key=lambda x: (
        1 if x["hasVideo"] and x["hasAudio"] else 0,
        x["height"] or 0,
        x["tbr"] or 0,
        x["abr"] or 0,
    ), reverse=True)
    return {
        "id": info.get("id"),
        "title": (info.get("title") or "AHDownload file")[:180],
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "source": info.get("webpage_url") or info.get("original_url"),
        "extractor": info.get("extractor_key") or info.get("extractor"),
        "formats": formats[:80],
    }

@app.get("/health")
async def health():
    return {"ok": True, "service": "AHDownload Resolver", "version": "1.0.0"}

@app.post("/v1/resolve")
async def resolve(body: ResolveRequest):
    url = str(body.url)
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or not host_allowed(parsed.hostname):
        raise HTTPException(400, "unsupported_source")
    check_rate("anonymous")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "extract_flat": False,
        "socket_timeout": 20,
        "retries": 2,
        "fragment_retries": 2,
        "http_headers": {
            "User-Agent": "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
        },
    }
    try:
        def run():
            with yt_dlp.YoutubeDL(opts) as ydl:
                return ydl.extract_info(url, download=False)
        info = await asyncio.to_thread(run)
        data = normalize(info)
        if not data["formats"]:
            raise HTTPException(422, "no_media_formats")
        return data
    except HTTPException:
        raise
    except Exception as exc:
        message = str(exc)
        if re.search(r"sign in|login|bot|captcha|private|unavailable", message, re.I):
            raise HTTPException(422, "source_requires_access")
        raise HTTPException(502, "resolver_failed")

@app.exception_handler(HTTPException)
async def http_error(_, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"status": "error", "code": str(exc.detail)})
