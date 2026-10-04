import json
import re
import urllib.parse
import urllib.request
import time
from html import unescape
import yt_dlp

ALLOWED_HOSTS = {
    "youtube.com", "youtu.be", "instagram.com", "facebook.com", "fb.watch",
    "tiktok.com", "twitter.com", "x.com", "vimeo.com", "reddit.com"
}
DIRECT_PROTOCOLS = {"http", "https"}
HTML_TYPES = {"text/html", "application/xhtml+xml"}


def _host(url):
    return (urllib.parse.urlparse(url).hostname or "").lower().removeprefix("www.")


def _normalize_url(url):
    value = (url or "").strip()
    value = re.sub(r"[\u0000-\u001f\u007f\u200b-\u200d\ufeff]", "", value)
    if not re.match(r"^https?://", value, re.I):
        if re.match(r"^(?:www\.)?(?:instagram\.com|youtube\.com|youtu\.be|facebook\.com|fb\.watch|tiktok\.com|x\.com|twitter\.com|vimeo\.com|reddit\.com)(?:/|$)", value, re.I):
            value = "https://" + value
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme.lower() not in DIRECT_PROTOCOLS or not parsed.hostname:
        raise ValueError("INVALID_URL")
    return value


def _allowed(url):
    host = _host(url)
    return any(host == h or host.endswith("." + h) for h in ALLOWED_HOSTS)


def _mime(ext):
    return {
        "mp4": "video/mp4", "webm": "video/webm", "mkv": "video/x-matroska",
        "mov": "video/quicktime", "m4v": "video/x-m4v",
        "m4a": "audio/mp4", "mp3": "audio/mpeg", "aac": "audio/aac",
        "opus": "audio/opus", "ogg": "audio/ogg", "wav": "audio/wav",
        "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
        "webp": "image/webp"
    }.get((ext or "").lower(), "application/octet-stream")


def _format(item):
    media_url = item.get("url")
    if not media_url or not str(media_url).lower().startswith(("http://", "https://")):
        return None

    protocol = str(item.get("protocol") or "").lower()
    if protocol and protocol not in DIRECT_PROTOCOLS:
        return None

    vcodec = item.get("vcodec") or "none"
    acodec = item.get("acodec") or "none"
    has_video = vcodec != "none"
    has_audio = acodec != "none"
    if not (has_video or has_audio):
        return None

    ext = item.get("ext") or "bin"
    mime = _mime(ext)
    if mime in HTML_TYPES:
        return None

    return {
        "id": str(item.get("format_id") or ""),
        "ext": ext,
        "mime": mime,
        "protocol": protocol or "https",
        "width": item.get("width"),
        "height": item.get("height"),
        "fps": item.get("fps"),
        "abr": item.get("abr"),
        "tbr": item.get("tbr"),
        "sizeBytes": item.get("filesize") or item.get("filesize_approx"),
        "hasVideo": has_video,
        "hasAudio": has_audio,
        "codec": vcodec if has_video else acodec,
        "url": media_url,
    }


def _format_score(f):
    progressive = 1 if f["hasVideo"] and f["hasAudio"] else 0
    video = 1 if f["hasVideo"] else 0
    height = f.get("height") or 0
    bitrate = f.get("tbr") or f.get("abr") or 0
    return progressive, video, height, bitrate


def _decode_url(value):
    value = unescape(value).replace("\\/", "/")
    try:
        value = json.loads('"' + value.replace('"', '\\"') + '"')
    except Exception:
        pass
    return value


def _instagram_candidates(html):
    candidates = []
    patterns = (
        r"<meta[^>]+property=[\"']og:video(?::secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
        r"<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video(?::secure_url)?[\"']",
        r"<meta[^>]+name=[\"']twitter:player:stream[\"'][^>]+content=[\"']([^\"']+)[\"']",
        r'"(?:video_url|playback_url|contentUrl)"\s*:\s*"([^"]+)"',
        r'"video_versions"\s*:\s*\[[^]]{0,12000}?"url"\s*:\s*"([^"]+)"',
    )
    for pattern in patterns:
        candidates.extend(re.findall(pattern, html, re.I | re.S))

    # Instagram frequently embeds the media URL in serialized JSON with
    # escaped slashes/query separators. Capture CDN URLs as a last resort.
    for raw in re.findall(
        r'https?:\\/\\/(?:[^"\\<>\s]|\\/)+',
        html,
        re.I,
    ):
        candidates.append(raw)

    seen = set()
    result = []
    for raw in candidates:
        media_url = _decode_url(raw)
        media_url = media_url.replace("\\u0026", "&").replace("\\u003d", "=")
        if not media_url.startswith(("http://", "https://")):
            continue
        lower = media_url.lower()
        if any(ext in lower.split("?", 1)[0] for ext in (".jpg", ".jpeg", ".png", ".webp", ".gif")):
            continue
        if media_url not in seen:
            seen.add(media_url)
            result.append(media_url)
    return result[:64]


def _probe_instagram_media(media_url, source_url, cookies, user_agent):
    probe = urllib.request.Request(
        media_url,
        headers={
            "User-Agent": user_agent,
            "Accept": "*/*",
            "Range": "bytes=0-1023",
            "Referer": source_url,
            "Origin": "https://www.instagram.com",
        },
    )
    if cookies:
        probe.add_header("Cookie", cookies)
    with urllib.request.urlopen(probe, timeout=15) as response:
        content_type = (response.headers.get("Content-Type") or "").split(";")[0].lower()
        if content_type in HTML_TYPES:
            return None
        path = urllib.parse.urlparse(media_url).path.lower()
        ext = path.rsplit(".", 1)[-1] if "." in path else "mp4"
        if ext not in {"mp4", "webm", "mov", "m4v"}:
            ext = "mp4" if content_type.startswith("video/") else ext
        if not content_type.startswith("video/") and ext not in {"mp4", "webm", "mov", "m4v"}:
            return None
        size = response.headers.get("Content-Length")
        return ext, content_type, int(size) if size and size.isdigit() else None


def _instagram_page_fallback(url, cookies=None):
    user_agent = (
        "Mozilla/5.0 (Linux; Android 15; Mobile) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/140.0 Mobile Safari/537.36"
    )
    headers = {
        "User-Agent": user_agent,
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer": "https://www.instagram.com/",
    }
    if cookies:
        headers["Cookie"] = cookies
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        html = response.read(4 * 1024 * 1024).decode("utf-8", "ignore")

    candidates = _instagram_candidates(html)
    title_match = re.search(
        r"<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
        html, re.I
    )
    title = unescape(title_match.group(1)).strip() if title_match else "Instagram video"

    for media_url in candidates:
        try:
            probed = _probe_instagram_media(media_url, url, cookies, user_agent)
            if probed is None:
                continue
            ext, content_type, size = probed
            return {
                "title": title,
                "thumbnail": None,
                "duration": None,
                "extractor": "InstagramPageFallback",
                "source": url,
                "formats": [{
                    "id": "instagram-page",
                    "ext": ext,
                    "mime": content_type or _mime(ext),
                    "protocol": "https",
                    "width": None,
                    "height": None,
                    "fps": None,
                    "abr": None,
                    "tbr": None,
                    "sizeBytes": size,
                    "hasVideo": True,
                    "hasAudio": True,
                    "codec": "unknown",
                    "url": media_url,
                }],
            }
        except Exception:
            continue
    raise RuntimeError("INSTAGRAM_PAGE_MEDIA_NOT_FOUND")


def _youtube_merged_formats(formats):
    videos = [
        f for f in formats
        if f["hasVideo"] and not f["hasAudio"]
        and f["ext"] == "mp4"
        and str(f.get("codec") or "").startswith("avc")
    ]
    audios = [
        f for f in formats
        if f["hasAudio"] and not f["hasVideo"]
        and f["ext"] in {"m4a", "mp4"}
        and str(f.get("codec") or "").startswith(("mp4a", "aac"))
    ]
    if not videos or not audios:
        return []
    audio = max(audios, key=lambda f: (f.get("abr") or 0, -(f.get("sizeBytes") or 0)))
    merged = []
    for video in sorted(videos, key=lambda f: (f.get("height") or 0), reverse=True):
        merged.append({
            **video,
            "id": f"mux-{video['id']}-{audio['id']}",
            "hasAudio": True,
            "mergeRequired": True,
            "audioUrl": audio["url"],
            "audioExt": audio["ext"],
            "audioSizeBytes": audio.get("sizeBytes"),
            "sizeBytes": (
                (video.get("sizeBytes") or 0) + (audio.get("sizeBytes") or 0)
                if video.get("sizeBytes") or audio.get("sizeBytes") else None
            ),
        })
    return merged


def resolve(url, cookies=None):
    url = _normalize_url(url)
    if not _allowed(url):
        raise ValueError("UNSUPPORTED_PLATFORM")

    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "extract_flat": False,
        "socket_timeout": 30,
        "retries": 2,
        "fragment_retries": 2,
        "http_headers": {
            "User-Agent": (
                "Mozilla/5.0 (Linux; Android 15; Mobile) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/140.0 Mobile Safari/537.36"
            ),
            "Accept-Language": "en-US,en;q=0.9",
            **({"Cookie": cookies} if cookies else {}),
        },
    }

    info = None
    last_error = None
    attempts = 3 if _host(url) in {"youtube.com", "youtu.be"} else 1
    for attempt in range(attempts):
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
            break
        except Exception as exc:
            last_error = exc
            message = str(exc)
            network_error = bool(re.search(
                r"No address associated with hostname|Temporary failure in name resolution|Name or service not known|timed out|Connection reset|Network is unreachable",
                message,
                re.I,
            ))
            if attempt + 1 < attempts and network_error:
                time.sleep(1.5 * (attempt + 1))
                continue
            break

    if info is None:
        exc = last_error or RuntimeError("EXTRACTION_FAILED")
        if _host(url) == "instagram.com":
            try:
                return _instagram_page_fallback(url, cookies)
            except Exception as fallback_exc:
                raise RuntimeError(
                    "EXTRACTION_FAILED:" + str(exc)[:180] +
                    "|INSTAGRAM_FALLBACK:" + str(fallback_exc)[:120]
                )
        raise RuntimeError("EXTRACTION_FAILED:" + str(exc)[:240])

    if not info:
        raise RuntimeError("NO_MEDIA")

    formats = []
    for item in info.get("formats") or []:
        parsed = _format(item)
        if parsed:
            formats.append(parsed)

    if not formats:
        direct = _format(info)
        if direct:
            formats.append(direct)

    unique = []
    seen = set()
    for f in formats:
        key = (f["id"], f["url"])
        if key in seen:
            continue
        seen.add(key)
        unique.append(f)

    unique.sort(key=_format_score, reverse=True)

    if _host(url) == "youtube.com" or _host(url) == "youtu.be":
        merged = _youtube_merged_formats(unique)
        if merged:
            # Put merged MP4/H.264 + AAC choices first. They are directly
            # downloadable by the Android worker and do not require ffmpeg.
            unique = merged + unique

    if not unique:
        if _host(url) == "instagram.com":
            try:
                return _instagram_page_fallback(url, cookies)
            except Exception as fallback_exc:
                raise RuntimeError("INSTAGRAM_PAGE_MEDIA_NOT_FOUND:" + str(fallback_exc)[:180])
        raise RuntimeError("NO_DIRECT_MEDIA_FORMATS")

    return {
        "title": info.get("title") or "AHDownload file",
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "extractor": info.get("extractor_key") or info.get("extractor"),
        "source": url,
        "formats": unique[:40],
    }


def resolve_json(payload):
    try:
        body = json.loads(payload or "{}")
        return json.dumps(resolve(body.get("url"), cookies=body.get("cookies")), ensure_ascii=False)
    except ValueError as exc:
        return json.dumps({"error": str(exc)})
    except Exception as exc:
        return json.dumps({"error": str(exc)})
