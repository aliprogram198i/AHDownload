from __future__ import annotations
import ipaddress, socket
from urllib.parse import urlparse
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl
import yt_dlp

app = FastAPI(title="AHDownload Resolver API", version="1.0.0")

class ResolveRequest(BaseModel):
    url: HttpUrl

def validate_public_url(raw: str) -> None:
    p = urlparse(raw)
    if p.scheme not in ("http", "https") or not p.hostname:
        raise HTTPException(400, "URL_INVALID")
    try:
        infos = socket.getaddrinfo(p.hostname, 443 if p.scheme == "https" else 80, type=socket.SOCK_STREAM)
        for info in infos:
            addr = ipaddress.ip_address(info[4][0])
            if addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_reserved or addr.is_multicast:
                raise HTTPException(400, "URL_BLOCKED")
    except socket.gaierror:
        raise HTTPException(400, "HOST_UNRESOLVED")

@app.get("/health")
def health():
    return {"ok": True, "service": "ahdownload-resolver"}

@app.post("/v1/resolve")
def resolve(body: ResolveRequest):
    raw = str(body.url)
    validate_public_url(raw)
    opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": False,
            "extract_flat": False, "socket_timeout": 20, "retries": 2,
            "http_headers": {"User-Agent": "AHDownload/1.0"}}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(raw, download=False)
    except Exception as exc:
        raise HTTPException(422, {"code": "RESOLVE_FAILED", "message": str(exc)[:500]})

    entries = info.get("entries") or [info]
    formats = []
    for item in entries[:20]:
        if not item:
            continue
        for f in item.get("formats") or []:
            url = f.get("url")
            if not url:
                continue
            vcodec, acodec = f.get("vcodec"), f.get("acodec")
            has_video = bool(vcodec and vcodec != "none")
            has_audio = bool(acodec and acodec != "none")
            if not has_video and not has_audio:
                continue
            formats.append({
                "id": str(f.get("format_id") or len(formats)),
                "type": "VIDEO" if has_video else "AUDIO",
                "container": f.get("ext"),
                "codec": vcodec if has_video else acodec,
                "resolution": f.get("resolution") or (f"{f.get('width')}x{f.get('height')}" if f.get("width") and f.get("height") else None),
                "fps": f.get("fps"),
                "bitrate": f.get("tbr"),
                "hasAudio": has_audio,
                "hasVideo": has_video,
                "estimatedSize": f.get("filesize") or f.get("filesize_approx"),
                "url": url,
                "title": item.get("title") or info.get("title"),
            })
    return {
        "source": raw,
        "type": "VIDEO" if any(f["hasVideo"] for f in formats) else "AUDIO",
        "title": info.get("title") or "AHDownload media",
        "thumbnailUrl": info.get("thumbnail"),
        "durationMs": int(info["duration"] * 1000) if info.get("duration") else None,
        "sizeBytes": info.get("filesize") or info.get("filesize_approx"),
        "formats": formats,
    }
