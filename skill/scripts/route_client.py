#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""Shared runtime/route HTTP client helpers for ok-cosmic scripts."""

import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, Optional


DEFAULT_ROUTE_TIMEOUT_SECONDS = 10.0
ROUTE_PAYLOAD_KEYS = ("data", "result", "respData", "response")


def append_query_param(url: str, key: str, value: str) -> str:
    """Append a query parameter unless the key already exists."""
    if not url or not key or not value:
        return url
    parsed = urllib.parse.urlsplit(url)
    query_pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    if any(k == key for k, _ in query_pairs):
        return url
    query_pairs.append((key, value))
    return urllib.parse.urlunsplit(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            urllib.parse.urlencode(query_pairs),
            parsed.fragment,
        )
    )


def parse_bool(value: Any, default: bool = False) -> bool:
    """Parse bool-like JSON/env values."""
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "yes", "y", "on"}:
            return True
        if normalized in {"0", "false", "no", "n", "off"}:
            return False
    return bool(value)


def _first_non_empty(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


class RouteClient:
    """Small JSON POST client for Cosmic unified runtime/route APIs."""

    def __init__(
        self,
        route_config: Optional[Dict[str, Any]] = None,
        *,
        api_url: Optional[str] = None,
        debug: bool = False,
        default_timeout: float = DEFAULT_ROUTE_TIMEOUT_SECONDS,
        missing_message: Optional[str] = None,
    ):
        if not isinstance(route_config, dict):
            route_config = {}

        self.debug = debug
        self.api_url = _first_non_empty(
            api_url,
            route_config.get("apiUrl"),
            os.getenv("COSMIC_ROUTE_API"),
            os.getenv("COSMIC_RUNTIME_ROUTE_API"),
        )
        open_api_sign = _first_non_empty(
            route_config.get("openApiSign"),
            route_config.get("openapiSign"),
            os.getenv("COSMIC_ROUTE_OPEN_API_SIGN"),
            os.getenv("COSMIC_OPEN_API_SIGN"),
        )
        if open_api_sign:
            self.api_url = append_query_param(self.api_url, "openApiSign", open_api_sign)

        self.api_token = _first_non_empty(
            route_config.get("apiToken"),
            route_config.get("token"),
            os.getenv("COSMIC_ROUTE_TOKEN"),
        )
        self.timeout = float(
            route_config.get("timeoutSeconds")
            or os.getenv("COSMIC_ROUTE_TIMEOUT")
            or default_timeout
        )
        self.skip_ssl_verify = parse_bool(route_config.get("skipSslVerify"), default=True)
        self.missing_message = missing_message or (
            "未配置统一路由 API。请在 ok-cosmic.json 的 route.apiUrl 中配置统一路由，"
            "或设置 COSMIC_ROUTE_API / COSMIC_RUNTIME_ROUTE_API 环境变量。"
        )

    def _log_debug(self, msg: str) -> None:
        if self.debug:
            print(f" (DEBUG) {msg}", file=sys.stderr)

    def post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """POST JSON payload and return the decoded JSON object."""
        if not self.api_url:
            raise RuntimeError(self.missing_message)

        headers = {"Content-Type": "application/json"}
        if self.api_token:
            headers["Authorization"] = f"Bearer {self.api_token}"

        req_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._log_debug(f"POST {self.api_url}")
        self._log_debug(f"payload={json.dumps(payload, ensure_ascii=False)}")
        req = urllib.request.Request(
            self.api_url,
            data=req_body,
            headers=headers,
            method="POST",
        )

        try:
            ssl_context = ssl.create_default_context()
            if self.skip_ssl_verify:
                ssl_context.check_hostname = False
                ssl_context.verify_mode = ssl.CERT_NONE
            with urllib.request.urlopen(req, timeout=self.timeout, context=ssl_context) as resp:
                raw = json.loads(resp.read().decode("utf-8", errors="replace"))
            if not isinstance(raw, dict):
                raise RuntimeError("远程接口返回的根对象不是 JSON Object。")
            return raw
        except urllib.error.HTTPError as e:
            body = ""
            try:
                body = e.read().decode("utf-8", errors="replace").strip()
            except Exception:
                pass
            detail = f": {body}" if body else f": {e.reason}"
            raise RuntimeError(f"HTTP {e.code}{detail}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"Network error: {e.reason}") from e
        except json.JSONDecodeError as e:
            raise RuntimeError(f"响应 JSON 解析失败: {e}") from e


def unwrap_route_payload(data: Any, payload_matcher: Callable[[Any], bool]) -> Any:
    """Unwrap common route wrapper keys and return the domain payload."""
    if not isinstance(data, dict):
        return data
    if payload_matcher(data):
        return data
    for key in ROUTE_PAYLOAD_KEYS:
        value = data.get(key)
        if payload_matcher(value):
            return value
    return data


def unwrap_route_raw(raw: Dict[str, Any], payload_matcher: Callable[[Any], bool]) -> Dict[str, Any]:
    """Unwrap one level of route envelope while preserving the original root shape."""
    data = raw.get("data")
    if not isinstance(data, dict):
        return raw
    if "status" in data and "data" in data:
        return data
    if payload_matcher(data):
        return raw
    for key in ROUTE_PAYLOAD_KEYS:
        value = data.get(key)
        if payload_matcher(value):
            cloned = dict(raw)
            cloned["data"] = value
            return cloned
    return raw
