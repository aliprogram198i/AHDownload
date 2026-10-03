import unittest
from unittest.mock import patch

import app.src.main.python.resolver as resolver


class ResolverCookieTest(unittest.TestCase):
    def test_webview_cookie_is_forwarded_to_yt_dlp(self):
        captured = {}

        class FakeYDL:
            def __init__(self, opts):
                captured["opts"] = opts

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def extract_info(self, url, download=False):
                return {
                    "title": "test",
                    "extractor_key": "Youtube",
                    "formats": [{
                        "format_id": "18",
                        "url": "https://media.example/video.mp4",
                        "protocol": "https",
                        "ext": "mp4",
                        "vcodec": "avc1",
                        "acodec": "mp4a",
                        "width": 720,
                        "height": 720,
                    }]
                }

        with patch.object(resolver.yt_dlp, "YoutubeDL", FakeYDL):
            result = resolver.resolve(
                "https://www.youtube.com/watch?v=test",
                cookies="SID=test-cookie; PREF=test"
            )

        self.assertEqual(result["formats"][0]["url"], "https://media.example/video.mp4")
        self.assertEqual(
            captured["opts"]["http_headers"]["Cookie"],
            "SID=test-cookie; PREF=test"
        )


if __name__ == "__main__":
    unittest.main()
