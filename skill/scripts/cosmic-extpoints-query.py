#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
cosmic-extpoints-query.py — Cosmic business extension point query tool.

Usage:
    python3 cosmic-extpoints-query.py --config ok-cosmic.json get --keyword 应付
    python3 cosmic-extpoints-query.py --config ok-cosmic.json get 应付
    python3 cosmic-extpoints-query.py --config ok-cosmic.json get --keyword 应付 --full  # 需要生成代码/查看示例时使用

What it provides:
1. Online business extension point lookup by keyword
2. Unified route API resolution with environment-variable fallback
3. Optional route.openApiSign support for OpenAPI signed URLs
4. Human-readable summary output by default, with full JSON only for code sample inspection
5. AI-friendly field: hasSample

What it does NOT do:
- It does not verify Java method signatures by itself; if the full result has no
  clear Java sample code, use cosmic-api-knowledge.py detail to confirm methods
  and parameters
- It does not generate plugin implementation code
- It does not infer whether an extension point is suitable for a concrete event

Prerequisites:
- A reachable runtime/route API, configured by ok-cosmic.json / route env / --api-url
"""

import argparse
import json
import sys
from typing import Any, Dict, Iterable, List, Optional, Tuple

from config_loader import get_int, load_project_config
from route_client import RouteClient, unwrap_route_raw
from script_utils import FriendlyArgumentParser, run_cli


def _shorten(text: Any, max_len: int = 80) -> str:
    value = "" if text is None else str(text)
    value = value.replace("\r", " ").replace("\n", " ").strip()
    if len(value) <= max_len:
        return value
    return value[: max_len - 1] + "…"


def _md_escape(text: Any) -> str:
    return _shorten(text).replace("|", "\\|") or "-"


def _pick_value(item: Dict[str, Any], keys: Iterable[str]) -> Any:
    lower_map = {str(k).lower(): v for k, v in item.items()}
    for key in keys:
        if key in item and item.get(key) not in (None, ""):
            return item.get(key)
        value = lower_map.get(key.lower())
        if value not in (None, ""):
            return value
    return None


def _record_has_sample(item: Dict[str, Any]) -> bool:
    sample = item.get("sample")
    if isinstance(sample, str):
        return bool(sample.strip())
    return bool(sample)


def _first_list_from_mapping(data: Dict[str, Any]) -> Tuple[Optional[List[Any]], Optional[Any]]:
    """
    Try common list wrappers without assuming the exact remote payload shape.

    Returns:
    - records list, if found
    - total count, if exposed by the wrapper
    """
    list_keys = (
        "records",
        "rows",
        "items",
        "list",
        "result",
        "values",
        "extPoints",
        "extpoints",
        "data",
    )
    total = (
        data.get("total")
        or data.get("totalCount")
        or data.get("count")
        or data.get("size")
    )
    for key in list_keys:
        value = data.get(key)
        if isinstance(value, list):
            return value, total
    return None, total


def _looks_like_extpoints_payload(value: Any) -> bool:
    if isinstance(value, list):
        return True
    if not isinstance(value, dict):
        return False
    records, _ = _first_list_from_mapping(value)
    return records is not None


def _unwrap_route_raw(raw: Dict[str, Any]) -> Dict[str, Any]:
    return unwrap_route_raw(raw, _looks_like_extpoints_payload)


class ExtPointsQuery:
    def __init__(
        self,
        config: Dict[str, Any],
        api_url: Optional[str] = None,
        debug: bool = False,
    ):
        self.debug = debug
        self.max_display = max(1, get_int(config, "limits", "maxDisplayRecords", 120))
        route_config = config.get("route", {})
        if not isinstance(route_config, dict):
            route_config = {}

        self.route_client = RouteClient(
            route_config,
            api_url=api_url,
            debug=debug,
            missing_message=(
                "未配置业务拓展点查询 API。请在 DSH 设置 → 苍穹开发 中配置统一路由地址，"
                "或设置 COSMIC_ROUTE_API / COSMIC_RUNTIME_ROUTE_API 环境变量；"
                "临时调试也可使用 --api-url 指定统一路由地址。"
            ),
        )

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self.route_client.post(payload)

    @staticmethod
    def _normalize(raw: Dict[str, Any], keyword: str) -> Dict[str, Any]:
        raw_data = raw.get("data")
        records: Optional[List[Any]] = None
        total: Optional[Any] = None
        trace_id = raw.get("traceId")

        if isinstance(raw_data, list):
            records = raw_data
        elif isinstance(raw_data, dict):
            records, total = _first_list_from_mapping(raw_data)
            trace_id = raw_data.get("traceId") or trace_id
        elif isinstance(raw.get("records"), list):
            records = raw.get("records")
            total = raw.get("total") or raw.get("totalCount") or raw.get("count")

        if records is None:
            records = []

        has_sample = any(_record_has_sample(item) for item in records if isinstance(item, dict))
        return {
            "status": "ok",
            "keyword": keyword,
            "traceId": trace_id,
            "total": total,
            "count": len(records),
            "hasSample": has_sample,
            "records": records,
            "data": raw_data,
            "raw": raw,
        }

    def query(self, keyword: str) -> Dict[str, Any]:
        normalized_keyword = (keyword or "").strip()
        if not normalized_keyword:
            raise ValueError("keyword 不能为空。请传入业务关键词，例如：--keyword 应付")

        payload = {
            "data": {
                "type": "extpoint",
                "reqData": {
                    "keyword": normalized_keyword,
                },
            }
        }
        raw = _unwrap_route_raw(self._post(payload))

        if raw.get("status") is False:
            message = str(raw.get("message") or "remote api status=false")
            error_code = raw.get("errorCode")
            raise RuntimeError(f"{message} (errorCode={error_code})")

        return self._normalize(raw, normalized_keyword)

    def _render_record_table(self, records: List[Any]) -> List[str]:
        if not records:
            return []

        rows = [
            "### [List] 拓展点列表",
            "| # | 编码 | 名称 | 接口 / 类 | 对象 / 模块 | 示例 | 说明 |",
            "| :--- | :--- | :--- | :--- | :--- | :--- | :--- |",
        ]
        for idx, item in enumerate(records[: self.max_display], start=1):
            if not isinstance(item, dict):
                rows.append(
                    f"| {idx} | - | - | - | - | - | `{_md_escape(json.dumps(item, ensure_ascii=False))}` |"
                )
                continue

            name = _pick_value(
                item,
                (
                    "name",
                    "extPointName",
                    "extpointName",
                    "pointName",
                    "title",
                    "sceneName",
                    "scenarioName",
                    "bizName",
                ),
            )
            number = _pick_value(item, ("number", "code", "sceneCode", "bizCode", "extPointCode"))
            interface = _pick_value(
                item,
                (
                    "interface",
                    "interfaceName",
                    "fullClassName",
                    "className",
                    "clazz",
                    "apiClass",
                    "serviceInterface",
                    "serviceClass",
                    "classPath",
                ),
            )
            app = _pick_value(
                item,
                (
                    "app",
                    "appName",
                    "appId",
                    "module",
                    "moduleName",
                    "domain",
                    "product",
                    "productName",
                    "objectType",
                ),
            )
            desc = _pick_value(
                item,
                (
                    "description",
                    "desc",
                    "remark",
                    "memo",
                    "summary",
                    "scene",
                    "scenario",
                    "useScene",
                ),
            )
            sample_state = "✔️ 有" if _record_has_sample(item) else "—"
            rows.append(
                f"| {idx} | `{_md_escape(number)}` | {_md_escape(name)} | "
                f"`{_md_escape(interface)}` | {_md_escape(app)} | {sample_state} | {_md_escape(desc)} |"
            )

        if len(records) > self.max_display:
            rows.append(
                f"\n> *结果较多，仅展示前 {self.max_display} 条；请缩小关键词。"
                "只有需要生成代码或查看示例时才使用 --full。*"
            )

        return rows

    def render(self, result: Dict[str, Any], full: bool = False) -> str:
        if full:
            return json.dumps(result, ensure_ascii=False, indent=2)

        lines = [
            "## [ExtPoint] 业务拓展点查询",
            f"**关键词**: `{result.get('keyword') or '-'}`",
            f"**命中数**: `{result.get('count')}`",
            f"**hasSample**: `{str(bool(result.get('hasSample'))).lower()}`",
        ]
        total = result.get("total")
        if total is not None:
            lines.append(f"**远端 total**: `{total}`")
        trace_id = result.get("traceId")
        if trace_id:
            lines.append(f"**traceId**: `{trace_id}`")

        records = result.get("records")
        if isinstance(records, list) and records:
            lines.append("")
            lines.extend(self._render_record_table(records))
        else:
            lines.append("")
            lines.append("> 未从返回数据中识别出拓展点列表，以下输出原始 data 便于判断接口结构。")
            lines.append("```json")
            lines.append(json.dumps(result.get("data"), ensure_ascii=False, indent=2))
            lines.append("```")

        lines.append(
            "\n> 下一步：需要生成代码或查看示例时加 `--full`。"
            "如果示例内容足够清晰，可先按示例实现；示例缺失/不完整/签名不确定时，再用 "
            "`cosmic-api-knowledge.py detail <full.class.Name>` 确认方法签名。"
        )
        return "\n".join(lines)


def _load_config_for_query(config_path: Optional[str]) -> Dict[str, Any]:
    """
    Keep standard ok-cosmic config behavior when possible, but allow this online
    query tool to run with explicit --api-url / environment variables if the
    implicit cwd config is absent.
    Explicit --config still remains strict.
    """
    try:
        return load_project_config(config_path)
    except FileNotFoundError:
        if config_path:
            raise
        return {
            "__config_path__": "",
            "__config_dir__": os.getcwd(),
        }


def main():
    parser = FriendlyArgumentParser(
        description="Cosmic ExtPoints Query CLI — 苍穹业务拓展点在线查询",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
推荐用法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  # 按关键词查询业务拓展点
  %(prog)s --config ok-cosmic.json get --keyword 应付

  # keyword 也可作为位置参数
  %(prog)s --config ok-cosmic.json get 应付

  # 需要生成代码或查看示例时，输出完整 JSON
  %(prog)s --config ok-cosmic.json get --keyword 应付 --full

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AI 调用约束
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  - 本脚本只负责定位业务拓展点候选。
  - 摘要和 --full 都会输出 hasSample，便于判断是否需要查看完整示例。
  - 默认不要加 --full；只有需要生成实现代码或查看示例时才使用 --full。
  - --full 返回中示例足够清晰时，可先按示例实现；示例缺失/不完整/签名不确定时再用 cosmic-api-knowledge.py detail 确认方法签名。
  - 统一路由请求体固定为 {"data": {"type": "extpoint", "reqData": {"keyword": "..."}}}。
""",
    )
    parser.add_argument("--config", help="Path to ok-cosmic.json")
    parser.add_argument("--api-url", help="Override extpoints API URL for this invocation")
    parser.add_argument("--debug", action="store_true")

    sub_parser = parser.add_subparsers(dest="command")
    get_parser = sub_parser.add_parser("get")
    get_parser.add_argument("keyword_arg", nargs="?", help="业务关键词（也可用 --keyword）")
    get_parser.add_argument("--keyword", help="业务关键词，例如：应付")
    get_parser.add_argument("--full", action="store_true", help="需要生成代码或查看示例时，直接输出完整 JSON")

    args = parser.parse_args()
    if args.command != "get":
        parser.print_help()
        return

    keyword = (args.keyword or args.keyword_arg or "").strip()

    try:
        config = _load_config_for_query(args.config)
        query = ExtPointsQuery(config, api_url=args.api_url, debug=args.debug)
        result = query.query(keyword=keyword)
        print(query.render(result, full=args.full))
    except Exception as e:
        print(f"✖️ 错误: {str(e)}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    sys.exit(run_cli(main))
