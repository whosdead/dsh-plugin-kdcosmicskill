#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
config_loader.py — Shared configuration loader for ok-cosmic scripts.

Provides shared helpers to:
1. Locate and parse ``ok-cosmic.json``
2. Validate Step 0 required content
3. Inject resolved config path metadata for downstream scripts
"""

import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Windows console encoding fix (shared by all scripts)
if os.name == "nt":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass


Issue = Dict[str, str]


def resolve_project_config_path(config_path: Optional[str] = None) -> Path:
    """Resolve ``ok-cosmic.json`` path from CLI input or current working directory."""
    return Path(config_path).expanduser() if config_path else Path.cwd() / "ok-cosmic.json"


def read_project_config(config_path: Optional[str] = None) -> Tuple[Path, Dict[str, Any]]:
    """
    Read and parse ``ok-cosmic.json`` without mutating the payload.

    Returns:
    - resolved absolute config path
    - raw JSON object as dict
    """
    target_path = resolve_project_config_path(config_path)

    if not target_path.is_file():
        raise FileNotFoundError(
            f"找不到配置文件: {target_path}。请确保文件存在或通过 --config 参数指定正确路径。"
        )

    try:
        with target_path.open("r", encoding="utf-8-sig") as f:
            data = json.load(f)
    except FileNotFoundError:
        raise
    except json.JSONDecodeError as e:
        raise RuntimeError(
            f"加载配置文件 {target_path} 失败: JSON 解析错误，第 {e.lineno} 行第 {e.colno} 列: {e.msg}"
        ) from e
    except Exception as e:
        raise RuntimeError(f"加载配置文件 {target_path} 失败: {e}") from e

    if not isinstance(data, dict):
        raise ValueError(f"配置文件格式错误: {target_path} 必须包含有效的 JSON 对象")

    return target_path.resolve(), data


def _add_issue(issues: List[Issue], level: str, key: str, message: str) -> None:
    issues.append({"level": level, "key": key, "message": message})


def _is_positive_number(value: Any) -> bool:
    try:
        return float(value) > 0
    except (TypeError, ValueError):
        return False


def _resolve_child_path(base_dir: Path, raw_path: str) -> Path:
    expanded = Path(os.path.expanduser(raw_path))
    if expanded.is_absolute():
        return expanded.resolve()
    return (base_dir / expanded).resolve()


def validate_project_config(config: Dict[str, Any], config_path: Optional[str] = None) -> List[Issue]:
    """
    Validate ``ok-cosmic.json`` content for Step 0 preflight.

    Validation levels:
    - ERROR: should stop before running skill scripts
    - WARNING: non-blocking but means some capabilities are unavailable
    """
    issues: List[Issue] = []
    raw_config_path = config_path or str(config.get("__config_path__", "")).strip()
    base_dir = (
        Path(raw_config_path).expanduser().resolve().parent
        if raw_config_path
        else Path.cwd()
    )

    graph_config = config.get("graph")
    if graph_config is None:
        _add_issue(issues, "ERROR", "graph", "缺少知识库配置对象（DSH 设置 → 苍穹开发 → 知识库数据库路径）。")
    elif not isinstance(graph_config, dict):
        _add_issue(issues, "ERROR", "graph", "`graph` 必须是 JSON 对象。")
    else:
        db_path = str(graph_config.get("dbPath", "")).strip()
        if not db_path:
            _add_issue(issues, "ERROR", "knowledgeDbPath", "缺少必填项：知识库数据库路径（DSH 设置 → 苍穹开发）。")
        else:
            resolved_db_path = _resolve_child_path(base_dir, db_path)
            if not resolved_db_path.exists():
                _add_issue(
                    issues,
                    "WARNING",
                    "knowledgeDbPath",
                    f"知识库数据库不存在: {resolved_db_path}",
                )

    route_env_available = bool(os.getenv("COSMIC_ROUTE_API") or os.getenv("COSMIC_RUNTIME_ROUTE_API"))
    route_config = config.get("route")
    route_api_url = ""
    if route_config is not None:
        if not isinstance(route_config, dict):
            _add_issue(issues, "ERROR", "route", "`route` 必须是 JSON 对象。")
        else:
            route_api_url = str(route_config.get("apiUrl", "")).strip()
            if not route_api_url and not route_env_available:
                _add_issue(
                    issues,
                    "WARNING",
                    "routeApiUrl",
                    "统一路由 API 地址为空，在线查询将不可用（DSH 设置 → 苍穹开发）。",
                )
            if "timeoutSeconds" in route_config and not _is_positive_number(route_config.get("timeoutSeconds")):
                _add_issue(
                    issues,
                    "ERROR",
                    "routeTimeoutSeconds",
                    "路由超时必须是大于 0 的数字（DSH 设置 → 苍穹开发）。",
                )
            if "openApiSign" in route_config and not isinstance(route_config.get("openApiSign"), str):
                _add_issue(
                    issues,
                    "ERROR",
                    "routeOpenApiSign",
                    "OpenAPI 签名必须是字符串（DSH 设置 → 苍穹开发）。",
                )

    if route_config is None and not route_env_available:
        _add_issue(
            issues,
            "WARNING",
            "route",
            "缺少统一路由配置，三个在线查询能力将不可用（DSH 设置 → 苍穹开发）。",
        )

    extension_repos = config.get("extensionRepos")
    if extension_repos is not None:
        if not isinstance(extension_repos, list):
            _add_issue(issues, "ERROR", "extensionRepos", "扩展代码库必须是字符串数组（DSH 设置 → 苍穹开发）。")
        else:
            for idx, raw_path in enumerate(extension_repos):
                key = f"extensionRepos[{idx}]"
                if not isinstance(raw_path, str) or not raw_path.strip():
                    _add_issue(
                        issues,
                        "ERROR",
                        key,
                        f"`{key}` 必须是非空字符串路径。",
                    )
                    continue
                resolved_repo = _resolve_child_path(base_dir, raw_path.strip())
                if not resolved_repo.is_dir():
                    _add_issue(
                        issues,
                        "WARNING",
                        key,
                        f"扩展代码库路径不存在或不是目录: {resolved_repo}",
                    )
                else:
                    _add_issue(
                        issues,
                        "OK",
                        key,
                        f"扩展代码库: {resolved_repo}",
                    )

    return issues


def get_section(config: Dict[str, Any], name: str) -> Dict[str, Any]:
    """Return a nested config section as a dict, or an empty dict."""
    value = config.get(name) if isinstance(config, dict) else None
    return value if isinstance(value, dict) else {}


def get_number(config: Dict[str, Any], section: str, key: str, default: float) -> float:
    """Read a numeric value from a config section, falling back to the default."""
    value = get_section(config, section).get(key, default)
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(default)


def get_int(config: Dict[str, Any], section: str, key: str, default: int) -> int:
    """Read an integer value from a config section, falling back to the default."""
    return int(get_number(config, section, key, default))


def get_bool(config: Dict[str, Any], section: str, key: str, default: bool) -> bool:
    """Read a boolean-ish value from a config section."""
    value = get_section(config, section).get(key, default)
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
    return bool(default)


def load_project_config(config_path: Optional[str] = None) -> Dict[str, Any]:
    """
    Load project-level config from provided path or current working directory.

    Returns a dict with two injected keys for downstream consumers:
    - ``__config_path__``: absolute path to the resolved config file
    - ``__config_dir__``: absolute path to the parent directory of the config file
    """
    resolved, data = read_project_config(config_path)
    data.setdefault("__config_path__", str(resolved))
    data.setdefault("__config_dir__", str(resolved.parent))
    return data
