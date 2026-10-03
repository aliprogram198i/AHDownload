import json
import sys
import urllib.request
import urllib.error

sys.path.insert(0, "app/src/main/python")
from resolver import resolve

CASES = {
    "youtube": "https://youtube.com/shorts/kdWMGpE8e-8?is=6je7xJBcDm17xbgs",
    "instagram": "https://www.instagram.com/reel/DeCfS9NqlLm/?stkn=MXdiYTRoc29vOHM1bQ==",
}


def probe(url):
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36",
            "Range": "bytes=0-1023",
            "Accept": "*/*",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            content_type = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            sample = response.read(1024)
            if response.status not in (200, 206):
                raise RuntimeError("MEDIA_HTTP_" + str(response.status))
            if content_type in ("text/html", "application/xhtml+xml"):
                raise RuntimeError("MEDIA_URL_RETURNED_HTML")
            if sample.lstrip().lower().startswith((b"<!doctype html", b"<html")):
                raise RuntimeError("MEDIA_BYTES_LOOK_LIKE_HTML")
            return {"status": response.status, "contentType": content_type, "bytesRead": len(sample)}
    except urllib.error.HTTPError as exc:
        raise RuntimeError("MEDIA_HTTP_" + str(exc.code)) from exc


def main():
    results = {}
    failed = []
    for name, page_url in CASES.items():
        try:
            media = resolve(page_url)
            if not media["formats"]:
                raise RuntimeError("NO_FORMATS")
            direct = media["formats"][0]["url"]
            if direct.rstrip("/") == page_url.rstrip("/"):
                raise RuntimeError("RESOLVER_RETURNED_PAGE_URL")
            if not direct.startswith(("http://", "https://")):
                raise RuntimeError("RESOLVER_RETURNED_NON_HTTP_URL")
            probe_result = probe(direct)
            top = media["formats"][0]
            results[name] = {
                "title": media["title"],
                "extractor": media["extractor"],
                "formatCount": len(media["formats"]),
                "topFormat": {
                    "id": top["id"],
                    "ext": top["ext"],
                    "height": top["height"],
                    "hasVideo": top["hasVideo"],
                    "hasAudio": top["hasAudio"],
                },
                "probe": probe_result,
            }
        except Exception as exc:
            failed.append({"case": name, "error": str(exc)})
    print(json.dumps({"passed": not failed, "results": results, "failed": failed}, ensure_ascii=False, indent=2))
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
