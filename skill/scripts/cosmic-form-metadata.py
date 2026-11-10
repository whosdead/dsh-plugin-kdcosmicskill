#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
cosmic-form-metadata.py — Cosmic form metadata query and cache tool.

Usage:
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formIdOrName>
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId1>,<formId2>,<中文名>   # batch, auto-detect
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --fuzzy qty price amount
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --fuzzy "组织 物料 批号"
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --fuzzy "数量|金额"
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --fuzzy status --show-detail
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --type BaseData
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --type "combo|check"
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --type decimal --fuzzy amount
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --tree
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --op
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --op 审核
    python3 cosmic-form-metadata.py --config ok-cosmic.json get <formId> --refresh

What it provides:
1. Form metadata lookup by formId or billName
2. Local SQLite cache for metadata payloads
3. Fuzzy field filtering for keys / names
4. Type-based fuzzy filtering (--type) for field type matching
5. Optional detail mode for enum mappings and reference types

What it does NOT do:
- It does not query the form_metadata_cache table directly for user-facing output semantics
- It does not infer field meaning beyond metadata returned by the configured API
- It does not rebuild the API knowledge graph database

Prerequisites:
- A valid ok-cosmic.json project config
- A reachable route.apiUrl (`runtime/route`) for cache misses
"""

import sys
import argparse
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional

from config_loader import get_int, load_project_config
from route_client import RouteClient, unwrap_route_payload
from sqlite_cache import JsonSqliteCache, resolve_graph_db_path
from script_utils import FriendlyArgumentParser, run_cli


def _looks_like_meta_payload(value: Any) -> bool:
    return isinstance(value, dict) and (
        "form" in value
        or "formFields" in value
        or "entityFields" in value
        or value.get("code") in ("MULTI_MATCH", "BILL_NOT_FOUND")
    )


def _unwrap_route_payload(data: Any) -> Any:
    return unwrap_route_payload(data, _looks_like_meta_payload)


class MetadataDbCache:
    def __init__(self, db_path: str, ttl: int, busy_timeout_ms: int):
        self._cache = JsonSqliteCache(
            db_path,
            table_name="form_metadata_cache",
            key_column="form_id",
            create_sql="""
                CREATE TABLE IF NOT EXISTS form_metadata_cache (
                    form_id TEXT PRIMARY KEY,
                    payload TEXT,
                    updated_at INTEGER
                )
            """,
            ttl=ttl,
            busy_timeout_ms=busy_timeout_ms,
            check_same_thread=False,
            init_error_message="初始化数据库失败",
            write_error_message="写入数据库失败",
        )

    def get(self, form_id: str) -> Optional[Dict[str, Any]]:
        return self._cache.get(form_id)

    def set(self, form_id: str, payload: Dict[str, Any]):
        self._cache.set_payload(form_id, payload)

    def remove(self, form_id: str):
        self._cache.remove(form_id)

    def close(self):
        """显式关闭数据库连接。"""
        cache = getattr(self, "_cache", None)
        if cache is not None:
            cache.close()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


class FormMetadata:
    def __init__(self, config: Dict[str, Any], debug: bool = False):
        self.debug = debug
        route_config = config.get("route", {})
        if not isinstance(route_config, dict):
            route_config = {}
        self.cache_ttl = get_int(config, "cache", "formMetadataTtlSeconds", 600)
        self.busy_timeout_ms = get_int(config, "cache", "sqliteBusyTimeoutMs", 5000)
        self.batch_workers = max(1, get_int(config, "limits", "batchWorkers", 4))
        self.max_display = max(1, get_int(config, "limits", "maxDisplayRecords", 120))
        self.db_path = resolve_graph_db_path(
            config,
            "未配置 knowledgeDbPath，请在 DSH 设置 → 苍穹开发 中指定知识库数据库路径。",
        )
        self.cache = MetadataDbCache(self.db_path, self.cache_ttl, self.busy_timeout_ms)
        self.route_client = RouteClient(
            route_config,
            debug=debug,
            missing_message=(
                "未配置表单元数据查询 API。请在 DSH 设置 → 苍穹开发 中配置统一路由地址，"
                "或设置 COSMIC_ROUTE_API / COSMIC_RUNTIME_ROUTE_API 环境变量。"
            ),
        )

    def _log_debug(self, msg: str):
        if self.debug:
            print(f" (DEBUG) {msg}", file=sys.stderr)

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self.route_client.post(payload)

    @staticmethod
    def _normalize_operates(raw_operates: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        兼容旧版 `buttons` 与新版 `operateMetas` 结构:
        - buttons: {name, key, ...}
        - operateMetas: {opName, opKey, opType}
        统一输出为: {name, key, type}
        """
        normalized: List[Dict[str, Any]] = []
        for op in raw_operates:
            if not isinstance(op, dict):
                continue
            key = op.get("key") or op.get("opKey")
            name = op.get("name") or op.get("opName")
            op_type = op.get("type") or op.get("opType")
            if not key and not name:
                continue
            normalized.append({
                "name": name or "-",
                "key": key or "-",
                "type": op_type or "-"
            })
        return normalized

    @staticmethod
    def _field_sort_key(field: Dict[str, Any], sort_by: str = "key") -> tuple:
        value = str(field.get(sort_by, "") or "").lower()
        key = str(field.get("key", "") or "").lower()
        name = str(field.get("name", "") or "").lower()
        node_type = str(field.get("type", "") or "").lower()
        db_key = str(field.get("dbKey", "") or "").lower()
        return (value, key, name, node_type, db_key)

    @classmethod
    def _sort_fields(cls, fields: List[Dict[str, Any]], sort_by: str = "key") -> List[Dict[str, Any]]:
        return sorted(fields, key=lambda f: cls._field_sort_key(f, sort_by=sort_by))

    @classmethod
    def _build_tree_lines(
        cls,
        fields: List[Dict[str, Any]],
        filter_patterns: Optional[List[str]] = None,
        sort_by: str = "key"
    ) -> List[str]:
        def is_hit(f: Dict[str, Any]) -> bool:
            if not filter_patterns:
                return True
            target_text = (
                str(f.get("key", ""))
                + "|"
                + str(f.get("name", ""))
                + "|"
                + str(f.get("type", ""))
                + "|"
                + str(f.get("dbKey", ""))
                + "|"
                + str(f.get("refType", ""))
            ).lower()
            for p in filter_patterns:
                try:
                    if re.search(p, target_text, re.IGNORECASE):
                        return True
                except Exception:
                    if p.lower() in target_text:
                        return True
            return False

        by_key: Dict[str, Dict[str, Any]] = {}
        for f in fields:
            key = str(f.get("key", "")).strip()
            if key and key not in by_key:
                by_key[key] = f

        if not by_key:
            return []

        children: Dict[Optional[str], List[str]] = {}
        for k, f in by_key.items():
            parent = f.get("parentKey")
            parent_key = str(parent).strip() if parent is not None else None
            if parent_key == "":
                parent_key = None
            children.setdefault(parent_key, []).append(k)

        for parent_key, ks in children.items():
            children[parent_key] = sorted(
                ks,
                key=lambda k: cls._field_sort_key(by_key.get(k, {}), sort_by=sort_by)
            )

        include_keys = set(by_key.keys())
        if filter_patterns:
            include_keys = {k for k, f in by_key.items() if is_hit(f)}
            # 补齐祖先节点，便于阅读路径
            queue = list(include_keys)
            while queue:
                cur = queue.pop()
                parent = by_key.get(cur, {}).get("parentKey")
                parent_key = str(parent).strip() if parent is not None else None
                if parent_key and parent_key in by_key and parent_key not in include_keys:
                    include_keys.add(parent_key)
                    queue.append(parent_key)

        def fmt_node(key: str) -> str:
            f = by_key[key]
            name = f.get("name", "-")
            node_type = f.get("type", "-")
            db_key = f.get("dbKey", "-")
            return f"{name} (`{key}`) [{node_type}] dbKey=`{db_key}`"

        lines: List[str] = []
        visited: set = set()

        def walk(node_key: str, prefix: str, is_last: bool):
            if node_key in visited or node_key not in include_keys:
                return
            visited.add(node_key)
            branch = "`- " if is_last else "|- "
            lines.append(f"{prefix}{branch}{fmt_node(node_key)}")
            child_keys = [ck for ck in children.get(node_key, []) if ck in include_keys]
            for i, ck in enumerate(child_keys):
                next_prefix = prefix + ("   " if is_last else "|  ")
                walk(ck, next_prefix, i == len(child_keys) - 1)

        roots = []
        for k in sorted(include_keys, key=lambda x: cls._field_sort_key(by_key.get(x, {}), sort_by=sort_by)):
            parent = by_key.get(k, {}).get("parentKey")
            parent_key = str(parent).strip() if parent is not None else None
            if not parent_key or parent_key not in include_keys:
                roots.append(k)

        roots = sorted(set(roots), key=lambda x: cls._field_sort_key(by_key.get(x, {}), sort_by=sort_by))
        for i, rk in enumerate(roots):
            walk(rk, "", i == len(roots) - 1)

        return lines

    def get_meta_fields(
        self,
        formId: Optional[str] = None,
        billName: Optional[str] = None,
        filter_patterns: Optional[List[str]] = None,
        raw_patterns: Optional[List[str]] = None,
        type_patterns: Optional[List[str]] = None,
        show_detail: bool = False,
        tree_view: bool = False,
        sql_mode: bool = False,
        sort_by: str = "key",
        view: str = "all",
        op_mode: bool = False,
        op_patterns: Optional[List[str]] = None
    ) -> str:
        if not formId and not billName:
            return "✖️ 错误: 必须提供 formId 或 billName"

        target_payload = None
        if formId:
            target_payload = self.cache.get(formId)
            if target_payload: self._log_debug(f"命中缓存 (FormId: {formId})")

        if not target_payload:
            self._log_debug("数据库无有效缓存，发起远程全量拉取...")
            # 始终拉取全量用于本地缓存
            payload = {
                "data": {
                    "type": "meta",
                    "reqData": {
                        "entityId": formId or "",
                        "formId": formId or "",
                        "billName": billName or "",
                        "full": True,
                    },
                }
            }
            try:
                resp = self._post(payload)
            except RuntimeError as e:
                return f"✖️ {e}"
            if not resp.get("status"):
                return f"✖️ 接口请求失败: {resp.get('message', '未知错误')}"

            data = _unwrap_route_payload(resp.get("data", {}))
            if data.get("code") in ("MULTI_MATCH", "BILL_NOT_FOUND"):
                msg = data.get("message", "单据未找到")
                cand_str = "\n".join([f"- {c.get('formName') or c.get('name') or '-'} (`{c.get('formId') or c.get('id') or '-'}`)" for c in data.get("candidates", [])])
                return f"✖️ {msg}\n{cand_str}"

            target_payload = data
            real_form_id = data.get("form", {}).get("formId")
            if real_form_id:
                self.cache.set(real_form_id, data)

        # 数据提取
        form = target_payload.get("form", {})
        form_fields = target_payload.get("formFields") or []
        entity_fields = target_payload.get("entityFields") or []
        raw_operates = (
            target_payload.get("operateMetas")
            or target_payload.get("buttons")
            or []
        )
        buttons = self._normalize_operates(raw_operates)

        # ── --op 模式：只输出操作按钮 ──
        if op_mode:
            _form_name = form.get('formName') or form.get('name') or form.get('title') or '-'
            _form_id = form.get('formId') or form.get('id') or form.get('key') or '-'
            md = [f"## [Op] 操作查询: {_form_name} ({_form_id})"]

            if op_patterns:
                def _op_hit(b):
                    target = (str(b.get('name', '')) + '|' + str(b.get('key', '')) + '|' + str(b.get('type', ''))).lower()
                    for p in op_patterns:
                        try:
                            if re.search(p, target, re.IGNORECASE):
                                return True
                        except Exception:
                            if p.lower() in target:
                                return True
                    return False
                matched = [b for b in buttons if _op_hit(b)]
                md.append(f"筛选: `{', '.join(op_patterns)}`，匹配 {len(matched)}/{len(buttons)} 个操作\n")
            else:
                matched = buttons
                md.append(f"共 {len(matched)} 个操作\n")

            if matched:
                md.append("| 名称 | 标识 (opKey) | 类型 (opType) |")
                md.append("| :--- | :--- | :--- |")
                for b in matched:
                    md.append(f"| {b.get('name')} | `{b.get('key')}` | {b.get('type')} |")
            else:
                md.append("> 未找到匹配的操作按钮")
            return "\n".join(md)

        # 过滤与搜索逻辑
        def is_hit(f):
            if not filter_patterns: return True
            target_text = (
                str(f.get('key', ''))
                + "|"
                + str(f.get('name', ''))
                + "|"
                + str(f.get('type', ''))
            ).lower()
            for p in filter_patterns:
                try:
                    if re.search(p, target_text, re.IGNORECASE): return True
                except Exception:
                    if p.lower() in target_text: return True
            return False

        def is_type_hit(f):
            """按 type 字段进行模糊匹配筛选。"""
            if not type_patterns:
                return True
            field_type = str(f.get('type', '')).lower()
            for p in type_patterns:
                try:
                    if re.search(p, field_type, re.IGNORECASE):
                        return True
                except Exception:
                    if p.lower() in field_type:
                        return True
            return False

        def is_combined_hit(f):
            """同时满足 fuzzy 和 type 两个筛选条件。"""
            return is_hit(f) and is_type_hit(f)

        # 应用过滤
        biz_form_fields = self._sort_fields(form_fields, sort_by=sort_by)
        biz_entity_fields = self._sort_fields(entity_fields, sort_by=sort_by)

        def get_match_score(f):
            if not filter_patterns: return 0
            k = str(f.get('key', '')).lower()
            n = str(f.get('name', '')).lower()
            t = str(f.get('type', '')).lower()
            db = str(f.get('dbKey', '')).lower()
            ref = str(f.get('refType', '')).lower()
            
            score = 99
            for p in filter_patterns:
                p_lower = p.lower()
                if p_lower == k or p_lower == n:
                    return 0
                if k.startswith(p_lower) or n.startswith(p_lower):
                    score = min(score, 1)
                elif p_lower in k or p_lower in n:
                    score = min(score, 2)
                else:
                    try:
                        if re.search(p_lower, f"{k}|{n}", re.IGNORECASE):
                            score = min(score, 3)
                        elif p_lower in t or p_lower in db or p_lower in ref or re.search(p_lower, f"{t}|{db}|{ref}", re.IGNORECASE):
                            score = min(score, 4)
                    except Exception:
                        pass
            return score

        display_form_fields = sorted([f for f in biz_form_fields if is_combined_hit(f)], key=get_match_score)
        display_entity_fields = sorted([f for f in biz_entity_fields if is_combined_hit(f)], key=get_match_score)
        display_buttons = sorted([b for b in buttons if is_combined_hit(b)], key=get_match_score)

        # ── 三级降级机制 ────────────────────────────────────────
        _used_fallback = False
        _fallback_hint = ""
        _no_results = lambda: not display_form_fields and not display_entity_fields and not display_buttons

        # 降级 1: 规范化后查不到 → 用原始输入重查
        #   场景: "物料 编码" 被拆成 ['物料','编码']，但字段名确实是 "物料 编码"
        if (filter_patterns and raw_patterns
                and filter_patterns != raw_patterns
                and _no_results()):
            filter_patterns = raw_patterns
            display_form_fields = sorted([f for f in biz_form_fields if is_combined_hit(f)], key=get_match_score)
            display_entity_fields = sorted([f for f in biz_entity_fields if is_combined_hit(f)], key=get_match_score)
            display_buttons = sorted([b for b in buttons if is_combined_hit(b)], key=get_match_score)
            if not _no_results():
                _used_fallback = True
                _fallback_hint = f"已降级为原始输入重查: `{' '.join(raw_patterns)}`"

        # 降级 2: 仍查不到 → 转义为纯文本匹配
        #   场景: "数量(基本)" 括号被当正则 / "C++标记" 加号被当量词
        if filter_patterns and _no_results():
            escaped = [re.escape(p) for p in (raw_patterns or filter_patterns)]
            if escaped != filter_patterns:
                filter_patterns = escaped
                display_form_fields = sorted([f for f in biz_form_fields if is_combined_hit(f)], key=get_match_score)
                display_entity_fields = sorted([f for f in biz_entity_fields if is_combined_hit(f)], key=get_match_score)
                display_buttons = sorted([b for b in buttons if is_combined_hit(b)], key=get_match_score)
                if not _no_results():
                    _used_fallback = True
                    _src = raw_patterns or filter_patterns
                    _fallback_hint = f"已降级为纯文本匹配: `{' '.join(_src)}`"

        view = (view or "all").strip()
        show_form = view in ("form", "all")
        show_entity = view in ("entity", "all")
        show_operate = view in ("operate", "all")

        selected_biz_form_fields = biz_form_fields if show_form else []
        selected_biz_entity_fields = biz_entity_fields if show_entity else []
        selected_display_form_fields = display_form_fields if show_form else []
        selected_display_entity_fields = display_entity_fields if show_entity else []
        selected_display_buttons = display_buttons if show_operate else []
        selected_buttons = buttons if show_operate else []

        _form_name = form.get('formName') or form.get('name') or form.get('title') or '-'
        _form_id = form.get('formId') or form.get('id') or form.get('key') or '-'
        _db_name = form.get('dbName') or target_payload.get('dbName') or '-'
        md = [f"## [Form] 单据: {_form_name} ({_form_id})"]
        md.append(
            "**模型信息**: "
            f"dbName=`{_db_name}`, "
            f"dbTableKey=`{form.get('dbTableKey', '-')}`, "
            f"dbRoute=`{form.get('dbRoute', '-')}`, "
            f"modelType=`{form.get('modelType', '-')}`"
        )
        md.append(f"**视图**: `{view}`")
        md.append(f"**元数据状态**: 已缓存本地 (DB驱动)")
        if _used_fallback:
            md.append(f"> [Warn] {_fallback_hint}")
        md.append("")

        # 根据是否有过滤词切换视图
        _has_filter = bool(filter_patterns or type_patterns)
        _filter_desc_parts = []
        if filter_patterns:
            _filter_desc_parts.append(f"fuzzy: {', '.join(filter_patterns)}")
        if type_patterns:
            _filter_desc_parts.append(f"type: {', '.join(type_patterns)}")
        _filter_desc = '；'.join(_filter_desc_parts)

        if tree_view:
            all_biz = []
            seen = set()
            for f in selected_biz_form_fields + selected_biz_entity_fields:
                k = f.get("key")
                if k and k not in seen:
                    all_biz.append(f)
                    seen.add(k)

            # tree 视图下 type 过滤: 先筛后建树
            if type_patterns:
                all_biz = [f for f in all_biz if is_type_hit(f)]
            tree_lines = self._build_tree_lines(all_biz, filter_patterns=filter_patterns, sort_by=sort_by) if all_biz else []
            if _has_filter:
                md.append(f"### [Tree] 字段树 (按条件筛选: {_filter_desc}，排序: {sort_by})")
            else:
                md.append(f"### [Tree] 字段树 (按 parentKey，排序: {sort_by})")

            if tree_lines:
                md.extend(tree_lines)
            else:
                md.append("> 未找到匹配字段（当前 view 可能不包含字段类型）")

            if selected_display_buttons:
                md.append("\n### [Op] 操作按钮 (匹配)")
                md.append(", ".join([f"{b.get('name')}(`{b.get('key')}`/{b.get('type')})" for b in selected_display_buttons]))
        elif _has_filter:
            md.append(f"### [Detail] 字段详情 (按条件筛选: {_filter_desc}，排序: {sort_by})")
            
            all_fields_by_key = {str(f.get('key', '')).lower(): f for f in form_fields + entity_fields}
            
            def get_entity_info(f):
                parent_key = f.get('parentKey')
                if not parent_key:
                    return "表头"
                parent_key_lower = str(parent_key).lower()
                parent = all_fields_by_key.get(parent_key_lower)
                if not parent:
                    return f"未知 (`{parent_key}`)"
                ptype = str(parent.get('type', '')).lower()
                if 'subentry' in ptype:
                    grandparent_key = parent.get('parentKey')
                    if grandparent_key:
                        return f"子表体 (`{grandparent_key}` -> `{parent_key}`)"
                    return f"子表体 (`{parent_key}`)"
                elif 'entry' in ptype:
                    return f"表体 (`{parent_key}`)"
                else:
                    return f"容器 (`{parent_key}`)"

            def get_db_table(f):
                parent_key = f.get('parentKey')
                if not parent_key:
                    ftype = str(f.get('type', '')).lower()
                    if 'entry' in ftype and f.get('dbKey'):
                        return f.get('dbKey')
                    return form.get('dbTableKey') or '-'
                parent = all_fields_by_key.get(str(parent_key).lower())
                return parent.get('dbKey') or '-' if parent else '-'

            def get_table_entity_info(f):
                parent_key = f.get('parentKey')
                ftype = str(f.get('type', '')).lower()
                if not parent_key and 'subentry' in ftype:
                    return f"子表体 (`{f.get('key')}`)"
                if not parent_key and 'entry' in ftype:
                    return f"表体 (`{f.get('key')}`)"
                return get_entity_info(f)

            if selected_display_form_fields or selected_display_entity_fields:
                if sql_mode:
                    md.append("### [SQL] 表所在数据库")
                    md.append(f"> dbName 来自 FormMeta；分录/子分录表一般与单头表位于同一数据库 `{_db_name}`。")
                    md.append("| 所属实体 | 表名 (dbTableName) | 数据库名 (dbName) |")
                    md.append("| :--- | :--- | :--- |")
                    seen_tables = set()
                    for tf in selected_display_form_fields + selected_display_entity_fields:
                        table_entity = get_table_entity_info(tf)
                        table_name = get_db_table(tf)
                        table_sig = (table_entity, table_name)
                        if table_sig in seen_tables:
                            continue
                        md.append(f"| {table_entity} | `{table_name}` | `{_db_name}` |")
                        seen_tables.add(table_sig)
                    md.append("")
                    md.append("### [SQL] 字段与数据库字段")
                    md.append("| 名称 | 标识 (Key) | 所属实体 | 表名 (dbTableName) | 数据库字段 (dbKey) |")
                    md.append("| :--- | :--- | :--- | :--- | :--- |")
                elif show_detail:
                    md.append("| 名称 | 标识 (Key) | 类型 | 所属实体 | 详情 (枚举/基础资料引用) |")
                    md.append("| :--- | :--- | :--- | :--- | :--- |")
                else:
                    md.append("| 名称 | 标识 (Key) | 类型 | 所属实体 | 附加信息 (Ext) |")
                    md.append("| :--- | :--- | :--- | :--- | :--- |")

                # 优先显示表单字段，再显示实体字段（去重）
                seen_keys = set()
                for f in selected_display_form_fields + selected_display_entity_fields:
                    if f.get('key') not in seen_keys:
                        ent_info = get_entity_info(f)
                        db_key = f.get('dbKey', '-')
                        
                        if sql_mode:
                            db_table = get_db_table(f)
                            md.append(f"| {f.get('name')} | `{f.get('key')}` | {ent_info} | `{db_table}` | `{db_key}` |")
                        elif show_detail:
                            detail_parts = []
                            ext_map = f.get('extMap')
                            ref_type = f.get('refType')
                            key = f.get('key')
                            ftype = str(f.get('type', ''))
                            if ext_map:
                                detail_parts.append("枚举: " + ", ".join([f"{k}:{v}" for k, v in ext_map.items()]))
                            if ref_type:
                                if ftype == 'BasedataPropField':
                                    detail_parts.append(f"[Note] 取值路径: `{ref_type}`（禁止用控件标识 `{f.get('key')}` 取值）")
                                else:
                                    detail_parts.append(f"基础资料引用: `{ref_type}`")
                            if ftype == 'LargeTextField' and key:
                                detail_parts.append(f"[Note] 完整取值: `{key}_tag`（LargeTextField 大文本禁止只用 `{key}` 获取完整内容）")
                            detail_str = "；".join(detail_parts) if detail_parts else "-"
                            md.append(f"| {f.get('name')} | `{f.get('key')}` | {f.get('type')} | {ent_info} | {detail_str} |")
                        else:
                            ext_map = f.get('extMap')
                            ref_type = f.get('refType')
                            key = f.get('key')
                            ftype = str(f.get('type', ''))
                            ext_parts = []
                            if ext_map:
                                ext_parts.append(", ".join([f"{k}:{v}" for k, v in ext_map.items()]))
                            if ref_type:
                                if ftype == 'BasedataPropField':
                                    ext_parts.append(f"[Note] 取值路径:`{ref_type}`（禁用`{f.get('key')}`）")
                                else:
                                    ext_parts.append(f"ref:`{ref_type}`")
                            if ftype == 'LargeTextField' and key:
                                ext_parts.append(f"[Note] 完整取值:`{key}_tag`（大文本禁用`{key}`取完整内容）")
                            ext_str = "；".join(ext_parts) if ext_parts else "-"
                            md.append(f"| {f.get('name')} | `{f.get('key')}` | {f.get('type')} | {ent_info} | {ext_str} |")
                        seen_keys.add(f.get('key'))
            else:
                md.append("> 当前 view 不包含字段类型或无匹配字段")

            if selected_display_buttons:
                md.append("\n### [Op] 操作按钮 (匹配)")
                md.append(", ".join([f"{b.get('name')}(`{b.get('key')}`/{b.get('type')})" for b in selected_display_buttons]))
        else:
            # 概览模式：只输出紧凑的 Key-Name 映射
            md.append(f"### [List] 字段概览 (共 {len(selected_biz_form_fields) + len(selected_biz_entity_fields)} 个字段，排序: {sort_by})")
            # 合并展示
            all_biz = []
            seen = set()
            for f in selected_biz_form_fields + selected_biz_entity_fields:
                if f.get('key') not in seen:
                    all_biz.append(f)
                    seen.add(f.get('key'))

            chunk_size = 3
            for i in range(0, min(len(all_biz), self.max_display), chunk_size):
                chunk = all_biz[i:i+chunk_size]
                line = "  ".join([f"• {f.get('name')}: `{f.get('key')}`" for f in chunk])
                md.append(line)

            if len(all_biz) > self.max_display:
                md.append(f"\n> *提示: 字段较多已截断。本地已缓存全量，请传入关键词（支持正则）获取特定字段详情。*")

            if selected_buttons:
                md.append("\n### [Op] 全部操作按钮")
                md.append(", ".join([f"{b.get('name')}(`{b.get('key')}`/{b.get('type')})" for b in selected_buttons]))

        return "\n".join(md)


_RE_META = re.compile(r'[|*+?\[\]()\\{}^$]')


def _normalize_fuzzy_patterns(raw_patterns: List[str]) -> List[str]:
    """
    智能拆分 fuzzy 参数，兼容各种 AI/Agent 的传参习惯:
      --fuzzy 组织 物料 批号       → ['组织', '物料', '批号']      (nargs 原生)
      --fuzzy "组织 物料 批号"     → ['组织', '物料', '批号']      (引号包裹，按空格拆)
      --fuzzy "数量|金额"          → ['数量|金额']                 (正则，保持原样)
      --fuzzy "qty.*amount"       → ['qty.*amount']               (正则，保持原样)
      --fuzzy "组织" "物料"        → ['组织', '物料']              (多引号，逐个处理)
    判定规则：含正则元字符 ``|*+?[](){}^$`` 的 token 视为正则，否则按空格拆分。
    """
    result: List[str] = []
    for p in raw_patterns:
        p = p.strip()
        if not p:
            continue
        if _RE_META.search(p):
            # 含正则元字符，保持原样
            result.append(p)
        else:
            # 纯文本：可能是 "组织 物料 批号" 这样的引号包裹，按空格拆
            result.extend(p.split())
    return [x for x in result if x]


def main():
    parser = FriendlyArgumentParser(
        description="Cosmic Form Metadata CLI — 苍穹表单元数据字段查询",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
推荐用法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  # 按 formId 查字段，模糊匹配多个关键词（空格分隔）
  %(prog)s --config ok-cosmic.json get <formId> --fuzzy qty price amount

  # 按中文名称确认基础资料的真实英文标识
  %(prog)s --config ok-cosmic.json get <中文名>

  # 批量查询：formId 与中文名可混合，逗号分隔，自动识别
  %(prog)s --config ok-cosmic.json get "ap_finapbill,物料,bd_supplier" --fuzzy number

  # 查枚举映射 / 基础资料引用类型 (refType)
  %(prog)s --config ok-cosmic.json get <formId> --fuzzy <字段> --show-detail

  # 按字段类型模糊筛选（如筛出所有 BaseData 类型字段）
  %(prog)s --config ok-cosmic.json get <formId> --type BaseData

  # 按多种类型筛选（正则 OR）
  %(prog)s --config ok-cosmic.json get <formId> --type "combo|check"

  # 类型 + 关键词交集筛选（如筛出 Decimal 类型中含 amount 的字段）
  %(prog)s --config ok-cosmic.json get <formId> --type decimal --fuzzy amount

  # 按字段树结构输出
  %(prog)s --config ok-cosmic.json get <formId> --tree

  # 查看所有操作按钮
  %(prog)s --config ok-cosmic.json get <formId> --op

  # 按关键词筛选操作
  %(prog)s --config ok-cosmic.json get <formId> --op 审核 提交

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AI 调用约束（摘要，完整约束见 SKILL.md 跨脚本硬约束与 coding-preferences.md B2）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  - formIdOrName 支持逗号分隔批量查询，英文标识与中文名可混合，自动识别。
  - --fuzzy 多关键词用空格分隔（如 --fuzzy qty price amount），严禁逐个字段多次查询。
  - ≥ 3 个关键词时自动升级为详情模式；编写枚举判断前必须带 --show-detail。
""",
    )
    parser.add_argument("--config", help="Path to ok-cosmic.json")

    sub_parser = parser.add_subparsers(dest="command")
    get_parser = sub_parser.add_parser("get")
    get_parser.add_argument("formIdOrName", help="formId 或中文名，支持逗号分隔批量查询（英文标识与中文名可混合，自动识别）")
    get_parser.add_argument("--fuzzy", nargs="*", help="筛选关键词或正则模式，触发详情视图。支持多种传参方式: --fuzzy a b c / --fuzzy 'a b c' / --fuzzy 'a|b'。≥ 3 个关键词时自动升级为详情模式（含枚举/refType）")
    get_parser.add_argument("--type", nargs="*", dest="type_filter", help="按字段类型模糊筛选，触发详情视图。支持正则: --type BaseData / --type 'combo|check' / --type decimal text。可与 --fuzzy 联用做交集筛选")
    get_parser.add_argument("--show-detail", action="store_true", help="显示枚举值映射(extMap)或基础资料引用类型(refType)")
    get_parser.add_argument("--tree", action="store_true", help="按 parentKey 输出字段树（可与 --fuzzy 联用）")
    get_parser.add_argument("--op", nargs="*", help="操作查询模式。不带值列出全部操作；带关键词按 name/key 模糊筛选（如 --op 审核 提交）")
    get_parser.add_argument("--sql", action="store_true", help="启用 SQL 模式，展示库名、表名与数据库字段名 (dbName/dbTableName/dbKey)")
    get_parser.add_argument("--sort", choices=["key", "name", "type", "dbKey"], default="key", help="字段排序方式")
    get_parser.add_argument("--view", choices=["form", "entity", "operate", "all"], default="all", help="元数据视图范围")
    get_parser.add_argument("--debug", action="store_true")
    get_parser.add_argument("--refresh", action="store_true")

    args = parser.parse_args()
    config = load_project_config(args.config)
    fm = FormMetadata(config, debug=getattr(args, 'debug', False))

    if args.command == "get":
        # 智能拆分 fuzzy 参数：纯关键词按空格拆开，正则模式保持原样
        raw_fuzzy = list(args.fuzzy) if args.fuzzy else None
        if args.fuzzy:
            args.fuzzy = _normalize_fuzzy_patterns(args.fuzzy)
        # 智能拆分 type 参数
        type_patterns = None
        if args.type_filter:
            type_patterns = _normalize_fuzzy_patterns(args.type_filter)
        # 当 fuzzy 关键词 ≥3 个时，自动升级为详情模式（含枚举/refType）
        auto_detail = False
        show_detail = args.show_detail
        if not show_detail and args.fuzzy and len(args.fuzzy) >= 3:
            show_detail = True
            auto_detail = True

        # ── 批量查询支持：逗号分隔，自动识别 formId / billName ──
        _RE_HAS_CJK = re.compile(r'[\u4e00-\u9fff\u3400-\u4dbf]')
        raw_targets = [t.strip() for t in re.split(r'[,\uff0c]', args.formIdOrName) if t.strip()] if args.formIdOrName else []

        if not raw_targets:
            print("✖️ 错误: 必须提供 formIdOrName")
            fm.cache.close()
            sys.exit(1)

        # 分类：含中文 → billName，否则 → formId
        query_targets = []
        for t in raw_targets:
            if _RE_HAS_CJK.search(t):
                query_targets.append({"formId": None, "billName": t})
            else:
                query_targets.append({"formId": t, "billName": None})

        # refresh 清缓存（串行，SQLite 写操作）
        if args.refresh:
            for qt in query_targets:
                if qt["formId"]:
                    fm.cache.remove(qt["formId"])

        # ── --op 模式 ──
        op_mode = args.op is not None
        op_patterns = None
        if op_mode and args.op:
            op_patterns = _normalize_fuzzy_patterns(args.op)

        def _query_one(qt):
            return fm.get_meta_fields(
                formId=qt["formId"],
                billName=qt["billName"],
                filter_patterns=args.fuzzy,
                raw_patterns=raw_fuzzy,
                type_patterns=type_patterns,
                show_detail=show_detail,
                tree_view=args.tree,
                sql_mode=args.sql,
                sort_by=args.sort,
                view=args.view,
                op_mode=op_mode,
                op_patterns=op_patterns
            )

        if len(query_targets) == 1:
            results = [_query_one(query_targets[0])]
        else:
            results = [None] * len(query_targets)
            with ThreadPoolExecutor(max_workers=min(len(query_targets), fm.batch_workers)) as pool:
                future_to_idx = {
                    pool.submit(_query_one, qt): i
                    for i, qt in enumerate(query_targets)
                }
                for future in as_completed(future_to_idx):
                    idx = future_to_idx[future]
                    try:
                        results[idx] = future.result()
                    except Exception as e:
                        t = query_targets[idx]
                        label = t["formId"] or t["billName"]
                        results[idx] = f"✖️ 查询 `{label}` 失败: {e}"

        output = "\n\n---\n\n".join(results)
        if auto_detail:
            output += "\n\n> [Tip] 已自动启用详情模式（fuzzy 关键词 ≥3），包含枚举映射和基础资料引用类型。"
        print(output)
    else:
        parser.print_help()

    fm.cache.close()

if __name__ == "__main__":
    sys.exit(run_cli(main))
