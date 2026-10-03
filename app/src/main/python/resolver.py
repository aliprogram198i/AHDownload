import json
import re
import urllib.parse
import yt_dlp

ALLOWED_HOSTS = {"youtube.com","youtu.be","instagram.com","facebook.com","fb.watch","tiktok.com","twitter.com","x.com","vimeo.com","reddit.com"}

def _host(url):
    return (urllib.parse.urlparse(url).hostname or "").lower().removeprefix("www.")

def _allowed(url):
    host = _host(url)
    return any(host == h or host.endswith("." + h) for h in ALLOWED_HOSTS)

def _mime(ext):
    return {"mp4":"video/mp4","webm":"video/webm","mkv":"video/x-matroska","mov":"video/quicktime","m4a":"audio/mp4","mp3":"audio/mpeg","aac":"audio/aac","opus":"audio/opus","ogg":"audio/ogg","jpg":"image/jpeg","jpeg":"image/jpeg","png":"image/png","webp":"image/webp"}.get((ext or "").lower(),"application/octet-stream")

def _format(item):
    url = item.get("url")
    if not url: return None
    vcodec, acodec = item.get("vcodec") or "none", item.get("acodec") or "none"
    has_video, has_audio = vcodec != "none", acodec != "none"
    if not (has_video or has_audio): return None
    ext = item.get("ext") or "bin"
    return {"id":str(item.get("format_id") or ""),"ext":ext,"mime":_mime(ext),"width":item.get("width"),"height":item.get("height"),"fps":item.get("fps"),"abr":item.get("abr"),"tbr":item.get("tbr"),"sizeBytes":item.get("filesize") or item.get("filesize_approx"),"hasVideo":has_video,"hasAudio":has_audio,"codec":vcodec if has_video else acodec,"url":url}

def resolve(url):
    url=(url or "").strip()
    if not re.match(r"^https?://",url,re.I): raise ValueError("INVALID_URL")
    if not _allowed(url): raise ValueError("UNSUPPORTED_PLATFORM")
    opts={"quiet":True,"no_warnings":True,"noplaylist":True,"skip_download":True,"extract_flat":False,"socket_timeout":20}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl: info=ydl.extract_info(url,download=False)
    except Exception as exc:
        raise RuntimeError("EXTRACTION_FAILED:"+str(exc)[:240])
    if not info: raise RuntimeError("NO_MEDIA")
    formats=[]
    for item in info.get("formats") or []:
        parsed=_format(item)
        if parsed: formats.append(parsed)
    if not formats:
        direct=_format(info)
        if direct: formats.append(direct)
    unique=[]; seen=set()
    for f in formats:
        key=(f["id"],f["url"])
        if key not in seen: seen.add(key); unique.append(f)
    unique.sort(key=lambda f:(1 if f["hasVideo"] and f["hasAudio"] else 0,f.get("height") or 0,f.get("tbr") or 0,f.get("abr") or 0),reverse=True)
    return {"title":info.get("title") or "AHDownload file","thumbnail":info.get("thumbnail"),"duration":info.get("duration"),"extractor":info.get("extractor_key") or info.get("extractor"),"source":url,"formats":unique[:40]}

def resolve_json(payload):
    try: return json.dumps(resolve(json.loads(payload).get("url")),ensure_ascii=False)
    except ValueError as exc: return json.dumps({"error":str(exc)})
    except Exception as exc: return json.dumps({"error":str(exc)})
