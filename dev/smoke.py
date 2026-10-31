import json
import time
import urllib.request


def call(method, url, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.load(response)


_, auth = call("POST", "http://localhost:8081/dev/token", {"consumerId": "ci-smoke"})
token = auth["access_token"]
for product in ("INTERNET-500", "TIMEOUT", "ERROR-400"):
    payload = {"externalId": "smoke-" + product + "-" + str(time.time_ns()),
               "customerId": "CUST-SMOKE", "productCode": product}
    code, created = call("POST", "http://localhost:8080/api/v1/requests", payload, token)
    assert code == 201, (code, created)
    code, replay = call("POST", "http://localhost:8080/api/v1/requests", payload, token)
    assert code == 200 and replay["id"] == created["id"], (code, replay)
    deadline = time.monotonic() + 45
    while True:
        _, result = call("GET", "http://localhost:8080/api/v1/requests/" + created["id"], token=token)
        if result["status"] in ("COMPLETED", "FAILED"):
            break
        assert time.monotonic() < deadline, result
        time.sleep(0.5)
    expected = "FAILED" if product == "ERROR-400" else "COMPLETED"
    assert result["status"] == expected, result
    if product == "TIMEOUT":
        assert result["attempts"] >= 2, result
    print(product + ": " + result["status"])
