#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
cosmic-config-check.py — Step 0 comprehensive preflight for ok-cosmic.

Checks performed:
  1. Python version (>= 3.8)
  2. Python dependencies — auto-install if missing (tree-sitter, tree-sitter-java)
  3. ok-cosmic.json configuration validation

Usage:
    python3 cosmic-config-check.py
    python3 cosmic-config-check.py --config /path/to/ok-cosmic.json
    python3 cosmic-config-check.py --config ok-cosmic.json --strict
    python3 cosmic-config-check.py --config ok-cosmic.json --json
"""

import argparse
import json
import os
import subprocess
import sys
from typing import Any, Dict, List

from config_loader import get_bool, get_int, read_project_config, validate_project_config
from script_utils import FriendlyArgumentParser, run_cli

# ──────────────────────────────────────────────
# Python 依赖包清单（module_name → pip_name）
# ──────────────────────────────────────────────

REQUIRED_PACKAGES = {
    "tree_sitter": "tree-sitter",
    "tree_sitter_java": "tree-sitter-java",
}


# 进度输出流（--json 模式时切换到 stderr，避免污染 JSON 输出）
_progress_out = sys.stdout


def _progress(msg: str) -> None:
    print(msg, file=_progress_out)


# ──────────────────────────────────────────────
# Phase 1: 运行环境检查
# ──────────────────────────────────────────────

def _check_python_version(issues: List[Dict[str, str]]) -> None:
    """检查 Python 版本 >= 3.8。"""
    ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    if sys.version_info < (3, 8):
        issues.append({"level": "ERROR", "key": "python.version",
                       "message": f"Python >= 3.8 required, current: {ver}"})
    else:
        _progress(f"[OK] Python {ver}")


def _check_and_install_dependencies(issues: List[Dict[str, str]], config: Dict[str, Any]) -> None:
    """检测缺失的 Python 依赖包，并按插件设置决定是否自动安装。"""
    missing = []
    for module_name, pip_name in REQUIRED_PACKAGES.items():
        try:
            __import__(module_name)
        except ImportError:
            missing.append((module_name, pip_name))

    if not missing:
        names = ", ".join(REQUIRED_PACKAGES.values())
        _progress(f"[OK] Python 依赖已就绪 ({names})")
        return

    pip_names = [p for _, p in missing]
    display = ", ".join(pip_names)
    manual = f"{sys.executable} -m pip install {' '.join(pip_names)}"

    if not get_bool(config, "python", "autoInstallDependencies", True):
        issues.append({
            "level": "WARNING",
            "key": "python.deps",
            "message": f"缺少 Python 依赖: {display}；自动安装已关闭。请手动执行: {manual}",
        })
        return

    timeout_seconds = max(1, get_int(config, "python", "installTimeoutSeconds", 120))
    _progress(f"[AUTO] 正在安装缺失的 Python 依赖: {display} ...")
    try:
        result = subprocess.run(
            [sys.executable, "-m", "pip", "install", "--quiet"] + pip_names,
            capture_output=True, text=True, timeout=timeout_seconds,
        )
        if result.returncode == 0:
            _progress(f"[OK] 已自动安装: {display}")
        else:
            err = (result.stderr or "").strip()[:200]
            issues.append({"level": "ERROR", "key": "python.deps",
                           "message": f"自动安装失败: {err}。请手动执行: {manual}"})
    except subprocess.TimeoutExpired:
        issues.append({"level": "ERROR", "key": "python.deps",
                       "message": f"安装超时（>{timeout_seconds} 秒）。请手动执行: {manual}"})
    except Exception as e:
        issues.append({"level": "ERROR", "key": "python.deps",
                       "message": f"安装异常: {e}。请手动执行: {manual}"})




# ──────────────────────────────────────────────
# 报告输出
# ──────────────────────────────────────────────

def _group_issues(issues: List[Dict[str, str]]) -> Dict[str, List[Dict[str, str]]]:
    errors = [issue for issue in issues if issue.get("level") == "ERROR"]
    warnings = [issue for issue in issues if issue.get("level") == "WARNING"]
    return {"errors": errors, "warnings": warnings}


def _print_text_report(issues: List[Dict[str, str]]) -> None:
    """打印环境+配置检查的文本报告（[OK] 行已在检查过程中实时输出）。"""
    for issue in issues:
        level = issue.get("level", "INFO")
        key = issue.get("key", "-")
        message = issue.get("message", "")
        print(f"[{level}] {key}: {message}")

    grouped = _group_issues(issues)
    errs, warns = len(grouped["errors"]), len(grouped["warnings"])
    print(f"[SUMMARY] errors={errs} warnings={warns}")


# ──────────────────────────────────────────────
# 主入口
# ──────────────────────────────────────────────

def main() -> int:
    parser = FriendlyArgumentParser(
        description="Step 0 环境与配置预检：检查 Python 依赖（自动安装）、ok-cosmic.json 配置完整性。"
    )
    parser.add_argument("--config", help="Path to ok-cosmic.json")
    parser.add_argument("--strict", action="store_true", help="Treat warnings as failures.")
    parser.add_argument("--json", action="store_true", help="Output machine-readable JSON report.")
    args = parser.parse_args()

    issues: List[Dict[str, str]] = []

    # --json 模式下，进度信息输出到 stderr，避免污染 JSON 输出
    global _progress_out
    if args.json:
        _progress_out = sys.stderr

    # Phase 1: 配置文件（先读配置，依赖安装策略也来自配置）
    config_path_str = args.config or "ok-cosmic.json"
    config: Dict[str, Any] = {}
    try:
        resolved_path, raw_config = read_project_config(args.config)
        config = dict(raw_config)
        config["__config_path__"] = str(resolved_path)
        config["__config_dir__"] = str(resolved_path.parent)
        _progress(f"[OK] 已找到配置文件: {resolved_path}")
        issues.extend(validate_project_config(config, str(resolved_path)))
        config_path_str = str(resolved_path)
    except Exception as e:
        issues.append({"level": "ERROR", "key": "__file__", "message": str(e)})

    # Phase 2: 运行环境
    _check_python_version(issues)
    _check_and_install_dependencies(issues, config)

    # 汇总
    grouped = _group_issues(issues)
    ok = not grouped["errors"] and not (args.strict and grouped["warnings"])

    if args.json:
        print(json.dumps({
            "ok": ok,
            "configPath": config_path_str,
            "errors": grouped["errors"],
            "warnings": grouped["warnings"],
        }, ensure_ascii=False, indent=2))
    else:
        if issues:
            _print_text_report(issues)
        else:
            print("[OK] 全部检查通过，未发现问题。")
        if args.strict and grouped["warnings"] and not grouped["errors"]:
            print("[STRICT] strict 模式下 warning 也会导致失败。", file=sys.stderr)

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(run_cli(main))
