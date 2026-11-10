#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
cosmic-basedata-query.py — Cosmic base data query and cache tool.

Usage:
    python3 cosmic-basedata-query.py --config ok-cosmic.json get --entity-id bd_supplier
    python3 cosmic-basedata-query.py --config ok-cosmic.json get --entity-id bd_supplier --number-or-name 1001
    python3 cosmic-basedata-query.py --config ok-cosmic.json get --entity-id bd_supplier --number-or-name 华东 --full
    python3 cosmic-basedata-query.py --config ok-cosmic.json get --entity-id bd_supplier --refresh
    python3 cosmic-basedata-query.py --config ok-cosmic.json get --entity-id bd_supplier --json

What it provides:
1. Query one base data result by entityId plus number/name keyword
2. Local SQLite cache in a dedicated table with a fixed 10-minute TTL
3. Config-first API resolution with environment-variable fallback
4. Human-readable summary output with optional JSON mode

What it does NOT do:
- It does not infer entityId from Chinese labels or form field names
- It only accepts entityId that is already confirmed by the user or by the metadata script
- It does not auto-query metadata; when entityId is unknown, query metadata for refType or look up the real English identifier by Chinese name first
- It does not batch query multiple entityIds in one request

Prerequisites:
- A valid ok-cosmic.json or equivalent project config
- A configured graph.dbPath for SQLite cache storage
- A reachable route.apiUrl (`runtime/route`) configured by config or environment
"""

import argparse
import json
import sys
from typing import Any, Dict, List, Optional

from config_loader import get_int, load_project_config
from route_client import RouteClient, unwrap_route_raw
from sqlite_cache import JsonSqliteCache, resolve_graph_db_path
from script_utils import FriendlyArgumentParser, run_cli


TABLE_NAME = "basedata_query_cache"


def contains_cjk(text: str) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def _looks_like_basedata_payload(value: Any) -> bool:
    return isinstance(value, dict) and (
        "entityId" in value
        or "numberOrName" in value
        or "traceId" in value
        or "data" in value
    )


def _unwrap_route_raw(raw: Dict[str, Any]) -> Dict[str, Any]:
    return unwrap_route_raw(raw, _looks_like_basedata_payload)


class BaseDataDbCache:
    def __init__(self, db_path: str, ttl: int, busy_timeout_ms: int):
        self._cache = JsonSqliteCache(
            db_path,
            table_name=TABLE_NAME,
            key_column="cache_key",
            create_sql=f"""
                CREATE TABLE IF NOT EXISTS {TABLE_NAME} (
                    cache_key TEXT PRIMARY KEY,
                    entity_id TEXT NOT NULL,
                    number_or_name TEXT NOT NULL,
                    full INTEGER NOT NULL,
                    payload TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """,
            ttl=ttl,
            busy_timeout_ms=busy_timeout_ms,
            init_error_message="初始化基础资料缓存表失败",
            write_error_message="写入基础资料缓存失败",
        )

    def get(self, cache_key: str) -> Optional[Dict[str, Any]]:
        return self._cache.get(cache_key)

    def set(
        self,
        cache_key: str,
        entity_id: str,
        number_or_name: str,
        full: bool,
        payload: Dict[str, Any],
    ):
        self._cache.set_payload(
            cache_key,
            payload,
            extra_columns=("entity_id", "number_or_name", "full"),
            extra_values=(entity_id, number_or_name, 1 if full else 0),
        )

    def remove(self, cache_key: str):
        self._cache.remove(cache_key)


class BaseDataQuery:
    def __init__(self, config: Dict[str, Any], debug: bool = False):
        self.debug = debug

        route_config = config.get("route", {})
        if not isinstance(route_config, dict):
            route_config = {}
        self.db_path = resolve_graph_db_path(
            config,
            "未配置 knowledgeDbPath，请在 DSH 设置 → 苍穹开发 中指定 SQLite 数据库路径。",
        )
        cache_ttl = get_int(config, "cache", "basedataTtlSeconds", 600)
        busy_timeout_ms = get_int(config, "cache", "sqliteBusyTimeoutMs", 5000)
        self.cache = BaseDataDbCache(self.db_path, ttl=cache_ttl, busy_timeout_ms=busy_timeout_ms)
        self.route_client = RouteClient(
            route_config,
            debug=debug,
            missing_message=(
                "未配置基础资料查询 API。请在 DSH 设置 → 苍穹开发 中配置统一路由地址，"
                "或设置 COSMIC_ROUTE_API / COSMIC_RUNTIME_ROUTE_API 环境变量。"
            ),
        )

    def _log_debug(self, msg: str):
        if self.debug:
            print(f" (DEBUG) {msg}", file=sys.stderr)

    @staticmethod
    def _cache_key(entity_id: str, number_or_name: str, full: bool) -> str:
        return f"{entity_id}|{number_or_name}|{1 if full else 0}"

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self.route_client.post(payload)

    @staticmethod
    def _normalize_result(
        raw: Dict[str, Any],
        entity_id: str,
        number_or_name: str,
        full: bool,
    ) -> Dict[str, Any]:
        payload_data = raw.get("data")
        if not isinstance(payload_data, dict):
            return {
                "entityId": entity_id,
                "numberOrName": number_or_name,
                "full": full,
                "traceId": None,
                "data": payload_data,
            }

        return {
            "entityId": payload_data.get("entityId", entity_id),
            "numberOrName": payload_data.get("numberOrName", number_or_name),
            "full": bool(payload_data.get("full", full)),
            "traceId": payload_data.get("traceId"),
            "data": payload_data.get("data"),
        }

    def query_one(self, entity_id: str, number_or_name: str = "", full: bool = False) -> Dict[str, Any]:
        normalized_entity_id = (entity_id or "").strip()
        normalized_keyword = (number_or_name or "").strip()
        normalized_full = bool(full)

        if not normalized_entity_id:
            raise ValueError(
                "entityId 不能为空。只允许使用用户明确给出的准确英文标识或元数据脚本确认出的真实标识；如不清楚，请先用元数据脚本查 refType 或按中文名称查英文标识。"
            )
        if contains_cjk(normalized_entity_id):
            raise ValueError(
                "entityId 必须是已确认的英文基础资料标识；只允许使用用户明确给出的准确英文标识或元数据脚本确认出的真实标识，不能直接传中文名称。"
            )

        cache_key = self._cache_key(normalized_entity_id, normalized_keyword, normalized_full)
        cached = self.cache.get(cache_key)
        if cached:
            self._log_debug(f"命中基础资料缓存: {cache_key}")
            return {"status": "ok", "source": "cache", **cached}

        req_data = {
            "entityId": normalized_entity_id,
            "numberOrName": normalized_keyword,
            "full": normalized_full,
        }
        payload = {
            "data": {
                "type": "basedata",
                "reqData": req_data,
            }
        }
        raw = _unwrap_route_raw(self._post(payload))

        if raw.get("status") is False:
            message = str(raw.get("message") or "remote api status=false")
            error_code = raw.get("errorCode")
            if " is not exist" in message:
                return {
                    "status": "not_found",
                    "message": message,
                    "errorCode": error_code,
                    "entityId": normalized_entity_id,
                    "numberOrName": normalized_keyword,
                    "full": normalized_full,
                }
            raise RuntimeError(f"{message} (errorCode={error_code})")

        result = self._normalize_result(raw, normalized_entity_id, normalized_keyword, normalized_full)
        self.cache.set(cache_key, normalized_entity_id, normalized_keyword, normalized_full, result)
        return {"status": "ok", "source": "api", **result}

    def refresh(self, entity_id: str, number_or_name: str = "", full: bool = False):
        cache_key = self._cache_key((entity_id or "").strip(), (number_or_name or "").strip(), bool(full))
        self.cache.remove(cache_key)

    @staticmethod
    def _maybe_render_mapping_table(data: Any) -> List[str]:
        if not isinstance(data, list) or not data:
            return []

        rows: List[Dict[str, str]] = []
        for item in data:
            if not isinstance(item, dict):
                return []
            number = item.get("number") or item.get("Number") or item.get("code") or item.get("Code")
            name = item.get("name") or item.get("Name")
            item_id = item.get("id") or item.get("Id") or item.get("pkId") or item.get("masterId")
            if number is None and name is None and item_id is None:
                return []
            rows.append(
                {
                    "number": str(number or "-"),
                    "name": str(name or "-"),
                    "id": str(item_id or "-"),
                }
            )

        md = [
            "### [List] 编码名称映射",
            "| 编码 | 名称 | ID |",
            "| :--- | :--- | :--- |",
        ]
        for row in rows:
            md.append(f"| `{row['number']}` | {row['name']} | `{row['id']}` |")
        return md

    def render(self, result: Dict[str, Any], json_mode: bool = False) -> str:
        if json_mode:
            return json.dumps(result, ensure_ascii=False, indent=2)

        status = result.get("status")
        if status == "not_found":
            message = str(result.get("message") or "未找到基础资料")
            error_code = result.get("errorCode")
            lines = [
                "## ✖️ 未找到基础资料",
                f"**基础资料标识**: `{result.get('entityId') or '-'}`",
                f"**查询词**: `{result.get('numberOrName') or '-'}`",
                f"**完整模式**: `{bool(result.get('full'))}`",
                f"**说明**: {message}",
            ]
            if error_code is not None:
                lines.append(f"**错误码**: `{error_code}`")
            return "\n".join(lines)

        lines = [
            f"## [BaseData] 基础资料: `{result.get('entityId') or '-'}`",
            f"**查询词**: `{result.get('numberOrName') or '(空，通常返回前50条映射)'}`",
            f"**完整模式**: `{bool(result.get('full'))}`",
            f"**结果来源**: `{result.get('source') or '-'}`",
        ]

        trace_id = result.get("traceId")
        if trace_id:
            lines.append(f"**traceId**: `{trace_id}`")

        data = result.get("data")
        mapping_table = self._maybe_render_mapping_table(data)
        if mapping_table:
            lines.append("")
            lines.extend(mapping_table)
        else:
            lines.append("\n### [Data] 返回数据")
            lines.append("```json")
            lines.append(json.dumps(data, ensure_ascii=False, indent=2))
            lines.append("```")

        return "\n".join(lines)


def main():
    parser = FriendlyArgumentParser(
        description="Cosmic BaseData Query CLI — 苍穹基础资料在线查询（带本地缓存）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
推荐用法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  # 按 entityId + 编码/名称查询
  %(prog)s --config ok-cosmic.json get --entity-id <entityId> --number-or-name <keyword>

  # 不传 --number-or-name 时返回前 50 条编码名称映射
  %(prog)s --config ok-cosmic.json get --entity-id <entityId>

  # 返回完整数据包
  %(prog)s --config ok-cosmic.json get --entity-id <entityId> --full

  # 清除缓存后重新请求
  %(prog)s --config ok-cosmic.json get --entity-id <entityId> --refresh

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AI 调用约束（摘要，完整约束见 SKILL.md 跨脚本硬约束）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  - entityId 必须来自"用户明确给出"或"元数据脚本确认"；来源不清时停止，先查元数据。
  - 默认精确查询，先传编码或精确名称；--full 仅在需要完整结构时使用。

缓存说明:
  - 使用 graph.dbPath 对应的 SQLite 文件，独立表 basedata_query_cache
  - 固定缓存时长 10 分钟，可用 --refresh 手动刷新
""",
    )
    parser.add_argument("--config", help="Path to ok-cosmic.json")
    parser.add_argument("--debug", action="store_true")

    sub_parser = parser.add_subparsers(dest="command")
    get_parser = sub_parser.add_parser("get")
    get_parser.add_argument(
        "--entity-id",
        required=True,
        help="基础资料标识（英文 entityId，且来源必须已确认；不确定时先用元数据脚本查 refType 或按中文名称查英文标识）",
    )
    get_parser.add_argument("--number-or-name", default="", help="编码或名称；为空时通常返回前50条映射")
    get_parser.add_argument("--keyword", dest="number_or_name", help="--number-or-name 的别名")
    get_parser.add_argument("--full", action="store_true", help="返回完整字段")
    get_parser.add_argument("--json", action="store_true", help="直接输出 JSON")
    get_parser.add_argument("--refresh", action="store_true", help="删除当前查询条件对应的本地缓存后再请求")
    get_parser.add_argument("--debug", action="store_true")

    args = parser.parse_args()
    if args.command != "get":
        parser.print_help()
        return

    try:
        config = load_project_config(args.config)
        query = BaseDataQuery(config, debug=(args.debug or getattr(args, "debug", False)))
        if args.refresh:
            query.refresh(args.entity_id, args.number_or_name, args.full)
        result = query.query_one(args.entity_id, args.number_or_name, args.full)
        print(query.render(result, json_mode=args.json))
    except Exception as e:
        print(f"✖️ 错误: {str(e)}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    sys.exit(run_cli(main))
