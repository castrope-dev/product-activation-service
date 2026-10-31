import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
JWK = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(KEY.public_key()))
JWK.update(kid="local-dev", use="sig", alg="RS256")
ACTIVATIONS = set()
LOCK = Lock()


def read_body(handler):
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        body = bytearray()
        while True:
            line = handler.rfile.readline(128)
            if not line.endswith(b"\r\n"):
                raise ValueError("invalid chunk header")
            size = int(line.strip().split(b";", 1)[0], 16)
            if size < 0 or len(body) + size > 8192:
                raise OverflowError("body limit exceeded")
            if size == 0:
                if handler.rfile.readline(128) != b"\r\n":
                    raise ValueError("trailers are not supported")
                return bytes(body)
            chunk = handler.rfile.read(size)
            if len(chunk) != size or handler.rfile.read(2) != b"\r\n":
                raise ValueError("invalid chunk body")
            body.extend(chunk)
    size = int(handler.headers.get("Content-Length", "0"))
    if size < 0:
        raise ValueError("invalid content length")
    if size > 8192:
        raise OverflowError("body limit exceeded")
    body = handler.rfile.read(size)
    if len(body) != size:
        raise ValueError("incomplete body")
    return body


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, status, body, headers=None):
        encoded = json.dumps(body).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"status": "UP"})
        if self.path == "/.well-known/jwks.json":
            return self.respond(200, {"keys": [JWK]})
        self.respond(404, {})

    def do_POST(self):
        try:
            self.connection.settimeout(10)
            body = json.loads(read_body(self) or b"{}")
            if not isinstance(body, dict):
                raise ValueError("expected an object")
        except OverflowError:
            return self.respond(413, {})
        except (ValueError, TypeError):
            return self.respond(400, {})
        if self.path == "/dev/token":
            now = int(time.time())
            token = jwt.encode({"iss": "http://localhost:8081", "sub": body.get("consumerId", "demo"),
                                "aud": "activation-api", "iat": now, "exp": now + 3600,
                                "scope": body.get("scope", "requests.read requests.write metrics.read")},
                               KEY, algorithm="RS256", headers={"kid": "local-dev"})
            return self.respond(200, {"access_token": token, "token_type": "Bearer", "expires_in": 3600})
        if self.path != "/provider/v1/activate":
            return self.respond(404, {})
        key = self.headers.get("Idempotency-Key")
        if not key:
            return self.respond(400, {"error": "missing idempotency key"})
        product = body.get("productCode", "")
        if product == "ERROR-400":
            return self.respond(400, {})
        if product == "ERROR-500":
            return self.respond(500, {})
        if product in ("ERROR-429", "ERROR-503"):
            return self.respond(int(product[-3:]), {}, {"Retry-After": "2"})
        with LOCK:
            replay = key in ACTIVATIONS
            ACTIVATIONS.add(key)
        if product == "TIMEOUT" and not replay:
            time.sleep(8)
        self.respond(200, {"activated": True, "replayed": replay})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
