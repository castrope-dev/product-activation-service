import io
import unittest
from types import SimpleNamespace

from server import read_body


class BodyReaderTest(unittest.TestCase):
    def test_reads_chunked_body(self):
        handler = SimpleNamespace(headers={"Transfer-Encoding": "chunked"},
                                  rfile=io.BytesIO(b'4\r\n{"a"\r\n3\r\n:1}\r\n0\r\n\r\n'))
        self.assertEqual(read_body(handler), b'{"a":1}')

    def test_reads_content_length(self):
        handler = SimpleNamespace(headers={"Content-Length": "2"}, rfile=io.BytesIO(b"{}"))
        self.assertEqual(read_body(handler), b"{}")

    def test_rejects_oversized_body(self):
        for headers, body in [({"Content-Length": "8193"}, b""),
                              ({"Transfer-Encoding": "chunked"}, b"2001\r\n")]:
            with self.assertRaises(OverflowError):
                read_body(SimpleNamespace(headers=headers, rfile=io.BytesIO(body)))

    def test_rejects_invalid_or_incomplete_body(self):
        for headers, body in [({"Content-Length": "-1"}, b""),
                              ({"Content-Length": "4"}, b"{}"),
                              ({"Transfer-Encoding": "chunked"}, b"X\r\n"),
                              ({"Transfer-Encoding": "chunked"}, b"2\r\n{}XX")]:
            with self.assertRaises(ValueError):
                read_body(SimpleNamespace(headers=headers, rfile=io.BytesIO(body)))


if __name__ == "__main__":
    unittest.main()
