"""mitmproxy addon: Codex's agent-internet policy, as its docs describe it.

A domain allowlist, and optionally "restrict network requests to GET, HEAD, and OPTIONS". The method
rule is why this profile is a TLS-intercepting proxy at all: on HTTPS, a proxy can only see the
method once it has decrypted the request.
"""
import os

from mitmproxy import http

ALLOWED = [
    line.strip().lstrip("*").lstrip(".")
    for line in open("/policy/allowlist.txt")
    if line.strip() and not line.startswith("#")
]
READ_ONLY = os.environ.get("CODEX_READ_ONLY") == "1"
SAFE_METHODS = {"GET", "HEAD", "OPTIONS"}


def allowed(host: str) -> bool:
    return any(host == d or host.endswith("." + d) for d in ALLOWED)


def deny(flow: http.HTTPFlow, why: str) -> None:
    flow.response = http.Response.make(403, why.encode(), {"Content-Type": "text/plain"})


def http_connect(flow: http.HTTPFlow) -> None:
    if not allowed(flow.request.host):
        deny(flow, "host not allowed")


def request(flow: http.HTTPFlow) -> None:
    if not allowed(flow.request.pretty_host):
        deny(flow, "host not allowed")
    elif READ_ONLY and flow.request.method not in SAFE_METHODS:
        deny(flow, f"method {flow.request.method} not allowed")
