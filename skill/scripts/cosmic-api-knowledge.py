#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""
cosmic-api-knowledge.py - Offline Cosmic API knowledge graph query tool.

Usage:
    python3 cosmic-api-knowledge.py --config ok-cosmic.json search <queryA>,<queryB>              # batch search
    python3 cosmic-api-knowledge.py --config ok-cosmic.json search <query...>
    python3 cosmic-api-knowledge.py --config ok-cosmic.json search <query...> --class-prefix kd.bos.servicehelper
    python3 cosmic-api-knowledge.py --config ok-cosmic.json search-method <queryA>,<queryB>       # batch search-method
    python3 cosmic-api-knowledge.py --config ok-cosmic.json search-method <query...>
    python3 cosmic-api-knowledge.py --config ok-cosmic.json detail <full.class.Name1>,<full.class.Name2>   # batch detail
    python3 cosmic-api-knowledge.py --config ok-cosmic.json detail <full.class.Name>
    python3 cosmic-api-knowledge.py --config ok-cosmic.json detail <full.class.Name> --method <keyword>
    python3 cosmic-api-knowledge.py --config ok-cosmic.json detail <full.class.Name> --method <keyword> --declared-only
    python3 cosmic-api-knowledge.py --config ok-cosmic.json detail <full.class.Name> --method <keyword> --compact

What it provides:
1. Fuzzy / regex class search against ok-cosmic-knowledge.db
2. Cross-class method search with ranking and package/category filters
3. Class detail lookup with signatures, return types, comments and inherited methods
4. Compact method fact output for downstream AI consumption

What it does NOT do:
- It does not inspect live jars directly; it only queries the built knowledge database
- It does not verify runtime availability of a class or method in the current environment
- It does not rebuild the knowledge database; use ok-cosmic-knowledge for that

Prerequisites:
- A valid ok-cosmic.json or equivalent graph config
- A built ok-cosmic-knowledge.db reachable from config / environment / project paths
"""

import json
import sys
import os
import argparse
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from config_loader import get_int, load_project_config
from script_utils import FriendlyArgumentParser, run_cli


CHEAT_SHEET_PATH = Path(__file__).resolve().parent.parent / "rules" / "cheat-sheet.md"


def _load_cheat_sheet_index() -> Tuple[Set[str], Set[Tuple[str, str]]]:
    """解析 cheat-sheet.md，提取已收录的类简称和 (类简称, 方法名) 对。"""
    classes: Set[str] = set()
    methods: Set[Tuple[str, str]] = set()
    try:
        text = CHEAT_SHEET_PATH.read_text(encoding="utf-8")
    except (FileNotFoundError, OSError):
        return classes, methods

    in_code_block = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("```"):
            in_code_block = not in_code_block
            continue
        if not in_code_block or stripped.startswith("//") or stripped.startswith("import "):
            continue
        # 匹配 ClassName.methodName( 模式
        for m in re.finditer(r'\b([A-Z][A-Za-z0-9_]+)\.([a-z][A-Za-z0-9_]*)\s*\(', stripped):
            cls_name, method_name = m.group(1), m.group(2)
            classes.add(cls_name)
            methods.add((cls_name, method_name))
        # 匹配 new ClassName( 模式
        for m in re.finditer(r'\bnew\s+([A-Z][A-Za-z0-9_]+)\s*\(', stripped):
            classes.add(m.group(1))
    return classes, methods


PRIMITIVE_DESCRIPTOR_TYPES = {
    "V": "void",
    "Z": "boolean",
    "B": "byte",
    "C": "char",
    "S": "short",
    "I": "int",
    "F": "float",
    "J": "long",
    "D": "double",
}


class ApiGraph:
    def __init__(self, config: Dict[str, Any]):
        graph_config = config.get("graph") if isinstance(config.get("graph"), dict) else config
        self.config_dir = str(config.get("__config_dir__", "")).strip()
        self.script_dir = str(Path(__file__).resolve().parent)
        db_path = str(graph_config.get("dbPath", "")).strip()

        self.db_path = ""
        if db_path:
            raw_db_path = os.path.expanduser(db_path)
            if os.path.isabs(raw_db_path):
                self.db_path = os.path.normpath(raw_db_path)
            else:
                base_dir = self.config_dir or os.getcwd()
                self.db_path = os.path.normpath(os.path.abspath(os.path.join(base_dir, raw_db_path)))

        self._cheat_classes, self._cheat_methods = _load_cheat_sheet_index()

        self.sqlite_error: Optional[str] = None



    def _get_conn(self):
        if hasattr(self, '_conn_cache') and self._conn_cache is not None:
            return self._conn_cache
        if self.sqlite_error:
            return None
        try:
            import sqlite3
            import re
        except Exception as e:
            self.sqlite_error = f"sqlite3 or re unavailable: {e}"
            return None

        if not self.db_path or not os.path.exists(self.db_path):
            return None
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")

        def regexp(expr, item):
            try:
                reg = re.compile(expr, re.IGNORECASE)
                return reg.search(item) is not None
            except Exception:
                return False
        conn.create_function("REGEXP", 2, regexp)

        conn.row_factory = sqlite3.Row
        self._conn_cache = conn
        return conn

    @staticmethod
    def _decode_descriptor_type(descriptor: str, index: int) -> Tuple[str, int]:
        array_depth = 0
        while index < len(descriptor) and descriptor[index] == "[":
            array_depth += 1
            index += 1

        if index >= len(descriptor):
            return "Object" + "[]" * array_depth, index

        token = descriptor[index]
        if token == "L":
            end = descriptor.find(";", index)
            if end == -1:
                return "Object" + "[]" * array_depth, len(descriptor)
            class_name = descriptor[index + 1:end].replace("/", ".").replace("$", ".")
            return class_name + "[]" * array_depth, end + 1

        pretty = PRIMITIVE_DESCRIPTOR_TYPES.get(token, token)
        return pretty + "[]" * array_depth, index + 1

    @classmethod
    def _normalize_type_name(cls, type_name: Optional[str], *, keep_full_path: bool = False) -> str:
        raw = (type_name or "").strip()
        if not raw:
            return "void"
        if raw.endswith("[]"):
            return cls._normalize_type_name(raw[:-2], keep_full_path=keep_full_path) + "[]"
        if raw.startswith("("):
            return raw
        normalized = raw.replace("$", ".")
        if keep_full_path:
            return normalized
        return normalized.split(".")[-1]

    @classmethod
    def _format_method_signature(cls, method_name: str, method_signature: Optional[str], return_type: Optional[str] = None) -> str:
        signature = (method_signature or "").strip()
        if not signature:
            return f"{method_name}()"
        if signature.startswith("(") and ("/" not in signature and ";" not in signature):
            suffix = f" -> {cls._normalize_type_name(return_type, keep_full_path=True)}" if return_type else ""
            return f"{signature}{suffix}"
        if not signature.startswith("("):
            return signature

        index = 1
        params: List[str] = []
        while index < len(signature) and signature[index] != ")":
            param_type, index = cls._decode_descriptor_type(signature, index)
            params.append(param_type)

        if index >= len(signature) or signature[index] != ")":
            return f"{method_name}{signature}"

        decoded_return_type, _ = cls._decode_descriptor_type(signature, index + 1)
        return f"{method_name}({', '.join(params)}) -> {decoded_return_type}"

    @classmethod
    def _format_method_signature_for_search(cls, method_name: str, method_signature: Optional[str], return_type: Optional[str] = None) -> str:
        signature = cls._format_method_signature(method_name, method_signature, return_type)
        if " -> " not in signature:
            return signature
        prefix, suffix = signature.rsplit(" -> ", 1)
        if suffix.startswith("java."):
            return f"{prefix} -> {cls._normalize_type_name(suffix)}"
        return signature

    @staticmethod
    def _parse_method_comment(comment: Optional[str]) -> Tuple[str, List[Tuple[str, str]], Optional[str], List[str]]:
        text = (comment or "").strip()
        if not text:
            return "", [], None, []

        description_lines: List[str] = []
        params: List[Tuple[str, str]] = []
        return_desc: Optional[str] = None
        throws_desc: List[str] = []

        for raw_line in text.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith("@param"):
                match = re.match(r"@param\s+`?([A-Za-z_$][A-Za-z0-9_$]*)`?\s*(.*)", line)
                if match:
                    params.append((match.group(1), match.group(2).strip()))
                else:
                    params.append(("", line[len("@param"):].strip()))
                continue
            if line.startswith("@return"):
                return_desc = line[len("@return"):].strip() or None
                continue
            if line.startswith("@throws"):
                throws_desc.append(line[len("@throws"):].strip())
                continue
            description_lines.append(line)

        description = "\n".join(description_lines).strip()
        return description, params, return_desc, throws_desc

    @classmethod
    def _summarize_method_comment(cls, comment: Optional[str], limit: int = 48) -> str:
        description, _, _, _ = cls._parse_method_comment(comment)
        summary = description.replace("\n", " ").strip()
        if len(summary) > limit:
            summary = summary[:limit].rstrip() + "..."
        return summary

    @staticmethod
    def _normalize_terms(query: Sequence[str] | str) -> List[str]:
        if isinstance(query, str):
            raw_terms = [query]
        else:
            raw_terms = list(query)

        terms: List[str] = []
        for item in raw_terms:
            if item is None:
                continue
            text = str(item).strip()
            if not text:
                continue
            for part in text.split():
                part = part.strip()
                if part:
                    terms.append(part)
        return terms

    @staticmethod
    def _build_term_clause(
        column_expr: str,
        terms: List[str],
        *,
        match_all: bool,
        use_regex: bool = False,
    ) -> Tuple[str, List[str]]:
        if not terms:
            return "", []

        operator = "REGEXP" if use_regex else "LIKE"
        glue = " AND " if match_all else " OR "
        clause_parts: List[str] = []
        params: List[str] = []
        for term in terms:
            clause_parts.append(f"{column_expr} {operator} ?")
            params.append(term if use_regex else f"%{term}%")
        return "(" + glue.join(clause_parts) + ")", params

    @staticmethod
    def _build_prefix_clause(column_expr: str, prefixes: Optional[Sequence[str]]) -> Tuple[str, List[str]]:
        if not prefixes:
            return "", []
        clean_prefixes = [str(prefix).strip() for prefix in prefixes if str(prefix).strip()]
        if not clean_prefixes:
            return "", []

        parts = [f"{column_expr} LIKE ?" for _ in clean_prefixes]
        params = [f"{prefix}%" for prefix in clean_prefixes]
        return "(" + " OR ".join(parts) + ")", params

    @staticmethod
    def _build_exact_keyword_clause(column_expr: str, values: Optional[Sequence[str]]) -> Tuple[str, List[str]]:
        if not values:
            return "", []
        clean_values = [str(value).strip() for value in values if str(value).strip()]
        if not clean_values:
            return "", []

        parts = [f"LOWER({column_expr}) LIKE ?" for _ in clean_values]
        params = [f"%{value.lower()}%" for value in clean_values]
        return "(" + " OR ".join(parts) + ")", params

    def _compose_class_filters(
        self,
        *,
        term_column_expr: str,
        query_terms: Sequence[str] | str,
        match_all_terms: bool = False,
        use_regex: bool = False,
        class_prefixes: Optional[Sequence[str]] = None,
        class_regex: Optional[str] = None,
        class_keywords: Optional[Sequence[str]] = None,
    ) -> Tuple[str, List[str]]:
        clauses: List[str] = []
        params: List[str] = []

        normalized_terms = self._normalize_terms(query_terms)
        term_clause, term_params = self._build_term_clause(
            term_column_expr,
            normalized_terms,
            match_all=match_all_terms,
            use_regex=use_regex,
        )
        if term_clause:
            clauses.append(term_clause)
            params.extend(term_params)

        prefix_clause, prefix_params = self._build_prefix_clause("class_name", class_prefixes)
        if prefix_clause:
            clauses.append(prefix_clause)
            params.extend(prefix_params)

        if class_regex and str(class_regex).strip():
            clauses.append("(class_name REGEXP ?)")
            params.append(str(class_regex).strip())

        keyword_clause, keyword_params = self._build_exact_keyword_clause("class_name", class_keywords)
        if keyword_clause:
            clauses.append(keyword_clause)
            params.extend(keyword_params)

        where_sql = " AND ".join(clauses) if clauses else "1=1"
        return where_sql, params

    @staticmethod
    def _build_method_relevance_expressions(normalized_terms: Sequence[str], query_label: str) -> Tuple[str, str, List[str]]:
        select_parts: List[str] = [
            "CASE WHEN LOWER(method_name) = LOWER(?) THEN 0 ELSE 1 END AS exact_rank",
            "CASE WHEN LOWER(method_name) LIKE LOWER(?) THEN 0 ELSE 1 END AS prefix_rank",
        ]
        params: List[str] = [query_label, f"{query_label}%"]

        if normalized_terms:
            all_match_parts = ["LOWER(method_name) LIKE LOWER(?)" for _ in normalized_terms]
            select_parts.append(
                "CASE WHEN " + " AND ".join(all_match_parts) + " THEN 0 ELSE 1 END AS all_terms_rank"
            )
            params.extend([f"%{term}%" for term in normalized_terms])

            ordered_pattern = "%" + "%".join(normalized_terms) + "%"
            select_parts.append("CASE WHEN LOWER(method_name) LIKE LOWER(?) THEN 0 ELSE 1 END AS ordered_rank")
            params.append(ordered_pattern)

            comment_match_parts = ["LOWER(COALESCE(method_comment, '')) LIKE LOWER(?)" for _ in normalized_terms]
            select_parts.append(
                "CASE WHEN " + " AND ".join(comment_match_parts) + " THEN 0 ELSE 1 END AS comment_rank"
            )
            params.extend([f"%{term}%" for term in normalized_terms])
        else:
            select_parts.extend([
                "0 AS all_terms_rank",
                "0 AS ordered_rank",
                "0 AS comment_rank",
            ])

        order_sql = ", ".join([
            "exact_rank ASC",
            "all_terms_rank ASC",
            "ordered_rank ASC",
            "prefix_rank ASC",
            "comment_rank ASC",
            "LENGTH(method_name) ASC",
            "class_name ASC",
            "method_name ASC",
        ])
        return ", ".join(select_parts), order_sql, params

    def search_methods(
        self,
        method_query,
        page=1,
        page_size=20,
        *,
        match_all_terms: bool = False,
        class_prefixes: Optional[Sequence[str]] = None,
        class_regex: Optional[str] = None,
        class_keywords: Optional[Sequence[str]] = None,
    ):
        conn = self._get_conn()
        if not conn: return f"✖️ 未配置 knowledgeDbPath，或未在路径 '{self.db_path}' 下找到有效数据库（请在 DSH 设置 → 苍穹开发 中配置）"

        offset = (page - 1) * page_size
        try:
            with conn:
                normalized_terms = self._normalize_terms(method_query)
                where_sql, where_params = self._compose_class_filters(
                    term_column_expr="method_name",
                    query_terms=normalized_terms,
                    match_all_terms=match_all_terms,
                    class_prefixes=class_prefixes,
                    class_regex=class_regex,
                    class_keywords=class_keywords,
                )

                count_sql = f"SELECT COUNT(*) as total FROM method_node WHERE {where_sql}"
                total = conn.execute(count_sql, tuple(where_params)).fetchone()['total']
                query_label = " ".join(normalized_terms) or str(method_query).strip()
                relevance_select_sql, order_sql, order_params = self._build_method_relevance_expressions(normalized_terms, query_label)

                sql = """
                    SELECT class_name, method_name, method_signature, return_type, method_comment,
                           {relevance_select_sql}
                    FROM method_node
                    WHERE {where_sql}
                    ORDER BY {order_sql}
                    LIMIT ? OFFSET ?
                """
                rows = conn.execute(
                    sql.format(where_sql=where_sql, order_sql=order_sql, relevance_select_sql=relevance_select_sql),
                    tuple(order_params + where_params + [page_size, offset]),
                ).fetchall()

                if not rows:
                    extra = []
                    if class_prefixes:
                        extra.append(f"class-prefix={','.join(class_prefixes)}")
                    if class_keywords:
                        extra.append(f"kind={','.join(class_keywords)}")
                    if class_regex:
                        extra.append(f"class-regex={class_regex}")
                    suffix = f" (filter: {'; '.join(extra)})" if extra else ""
                    return f"[Search] No matching methods for `{query_label}`{suffix}."

                def relevance_bucket(row):
                    simple_cls = row['class_name'].rsplit('.', 1)[-1]
                    cheat_bonus = 0 if (simple_cls, row['method_name']) in self._cheat_methods else 32
                    return (
                        cheat_bonus
                        + int(row["exact_rank"]) * 16
                        + int(row["all_terms_rank"]) * 8
                        + int(row["ordered_rank"]) * 4
                        + int(row["prefix_rank"]) * 2
                        + int(row["comment_rank"])
                    )

                if page == 1 and len(rows) >= 3 and all(relevance_bucket(row) <= 4 for row in rows[:3]):
                    strong_rows = [row for row in rows if relevance_bucket(row) <= 4]
                    if strong_rows and len(strong_rows) < len(rows):
                        rows = strong_rows

                total_pages = (total + page_size - 1) // page_size
                md = [f"### [Method] 方法搜索结果: {query_label} ({page}/{total_pages} 页, 共 {total} 条)\n"]
                if class_prefixes or class_keywords or class_regex:
                    filter_desc: List[str] = []
                    if class_prefixes:
                        filter_desc.append(f"class-prefix={','.join(class_prefixes)}")
                    if class_keywords:
                        filter_desc.append(f"kind={','.join(class_keywords)}")
                    if class_regex:
                        filter_desc.append(f"class-regex={class_regex}")
                    md.append(f"> 过滤条件: {'; '.join(filter_desc)}")
                    md.append("")
                full_match_rows = []
                partial_match_rows = []
                if len(normalized_terms) >= 2:
                    for row in rows:
                        if int(row["all_terms_rank"]) == 0:
                            full_match_rows.append(row)
                        else:
                            partial_match_rows.append(row)
                else:
                    full_match_rows = list(rows)

                def append_rows(title: Optional[str], result_rows):
                    if not result_rows:
                        return
                    if title:
                        md.append(title)
                    md.append("| 定义类 (Class) | 签名 (Signature) | 说明 (Comment) |")
                    md.append("| :--- | :--- | :--- |")
                    for row in result_rows:
                        comment = self._summarize_method_comment(row["method_comment"])
                        pretty_signature = self._format_method_signature_for_search(row["method_name"], row["method_signature"], row["return_type"])
                        simple_cls = row['class_name'].rsplit('.', 1)[-1]
                        cheat_tag = " ✔️" if (simple_cls, row['method_name']) in self._cheat_methods else ""
                        md.append(f"| `{row['class_name']}` | `#{row['method_name']}{pretty_signature}`{cheat_tag} | {comment or '-'} |")
                    md.append("")

                if len(normalized_terms) >= 2 and full_match_rows:
                    append_rows("#### 全关键词命中", full_match_rows)
                    if partial_match_rows:
                        append_rows("#### Partial keyword matches", partial_match_rows)
                else:
                    append_rows(None, rows)

                md.append("\n*提示: 使用 `detail <class_name>` 查看类完整继承树和注释。*")
                return "\n".join(md)
        except Exception as e: return f"✖️ 搜索失败: {str(e)}"

    def fuzzy_search_classes(
        self,
        query,
        page=1,
        page_size=20,
        use_regex=False,
        *,
        match_all_terms: bool = False,
        class_prefixes: Optional[Sequence[str]] = None,
        class_regex: Optional[str] = None,
        class_keywords: Optional[Sequence[str]] = None,
    ):
        conn = self._get_conn()
        if not conn:
            if self.sqlite_error:
                return f"✖️ 运行环境缺少依赖: {self.sqlite_error}"
            return f"✖️ 未配置 knowledgeDbPath，或未在路径 '{self.db_path}' 下找到有效数据库（请在 DSH 设置 → 苍穹开发 中配置）"

        offset = (page - 1) * page_size

        try:
            with conn:
                where_sql, where_params = self._compose_class_filters(
                    term_column_expr="class_name",
                    query_terms=query,
                    match_all_terms=match_all_terms,
                    use_regex=use_regex,
                    class_prefixes=class_prefixes,
                    class_regex=class_regex,
                    class_keywords=class_keywords,
                )
                count_sql = f"SELECT COUNT(*) as total FROM class_node WHERE {where_sql}"
                total_row = conn.execute(count_sql, tuple(where_params)).fetchone()
                total = total_row['total']

                data_sql = """
                    SELECT class_name, class_comment
                    FROM class_node
                    WHERE {where_sql}
                    ORDER BY class_name ASC LIMIT ? OFFSET ?
                """
                rows = conn.execute(data_sql.format(where_sql=where_sql), tuple(where_params + [page_size, offset])).fetchall()

                query_label = " ".join(self._normalize_terms(query)) or str(query).strip()
                if not rows:
                    msg = f"[Search] No matching classes for `{query_label}`"
                    if use_regex: msg += " (使用正则模式)"
                    extra = []
                    if class_prefixes:
                        extra.append(f"class-prefix={','.join(class_prefixes)}")
                    if class_keywords:
                        extra.append(f"kind={','.join(class_keywords)}")
                    if class_regex:
                        extra.append(f"class-regex={class_regex}")
                    if extra:
                        msg += f" (filter: {'; '.join(extra)})"
                    return msg + f" (page {page})."

                total_pages = (total + page_size - 1) // page_size
                mode_str = "正则" if use_regex else "模糊"
                md = [f"### [Search] {mode_str}搜索结果 (关键词: {query_label}, 第 {page}/{total_pages} 页, 共 {total} 条)\n"]
                if class_prefixes or class_keywords or class_regex:
                    filter_desc: List[str] = []
                    if class_prefixes:
                        filter_desc.append(f"class-prefix={','.join(class_prefixes)}")
                    if class_keywords:
                        filter_desc.append(f"kind={','.join(class_keywords)}")
                    if class_regex:
                        filter_desc.append(f"class-regex={class_regex}")
                    md.append(f"> 过滤条件: {'; '.join(filter_desc)}")
                    md.append("")
                for row in rows:
                    class_comment = (row['class_comment'] or '').strip()
                    simple_name = row['class_name'].rsplit('.', 1)[-1]
                    cheat_tag = " `[速查已收录]`" if simple_name in self._cheat_classes else ""
                    class_line = f"- **`{row['class_name']}`**{cheat_tag}"
                    if class_comment:
                        class_line += f"  \n  > {class_comment}"
                    md.append(class_line)

                if page < total_pages:
                    md.append(f"\n*提示: 还有更多结果 (page={page+1})。使用 `detail <classname>` 查看详情。*")
                else:
                    md.append("\n*提示: 已显示全部结果。使用 `detail <classname>` 查看详情。*")

                return "\n".join(md)
        except Exception as e: return f"✖️ 搜索失败: {str(e)}"

    def get_class_details(self, class_name, method_filter: Optional[str] = None, declared_only: bool = False, compact: bool = False):
        conn = self._get_conn()
        if not conn:
            if self.sqlite_error:
                return f"✖️ 运行环境缺少 sqlite3 依赖: {self.sqlite_error}"
            return f"✖️ 未配置 knowledgeDbPath，或未在路径 '{self.db_path}' 下找到有效数据库（请在 DSH 设置 → 苍穹开发 中配置）"

        # SQL with optional method filtering
        method_clause = ""
        params = [class_name]
        if method_filter:
            method_clause = "AND m.method_name LIKE ?"
            params.append(f"%{method_filter}%")

        hierarchy_cte = """
        WITH RECURSIVE hierarchy(class_name, super_class_name, depth) AS (
            SELECT class_name, super_class_name, 0 FROM class_node WHERE class_name = ?
        """
        if not declared_only:
            hierarchy_cte += """
            UNION ALL
            SELECT c.class_name, c.super_class_name, h.depth + 1
            FROM class_node c JOIN hierarchy h ON c.class_name = h.super_class_name
            WHERE c.class_name != 'java.lang.Object' AND c.class_name IS NOT NULL
            """
        hierarchy_cte += ")"

        sql = f"""
        {hierarchy_cte}
        SELECT h.class_name, h.depth, c.class_comment, m.method_name, m.method_signature, m.return_type, m.method_comment
        FROM hierarchy h JOIN class_node c ON h.class_name = c.class_name
        LEFT JOIN method_node m ON h.class_name = m.class_name {method_clause}
        ORDER BY h.depth ASC, m.method_name ASC;
        """
        try:
            with conn:
                rows = conn.execute(sql, tuple(params)).fetchall()
                if not rows:
                    return f"✖️ Class not found: `{class_name}`" + (f" or matching method `{method_filter}`" if method_filter else "") + "."

                # Check if any methods were found if a filter was provided
                has_methods = any(r['method_name'] for r in rows)
                if method_filter and not has_methods:
                    return f"✖️ No method matching `{method_filter}` in `{class_name}` or its superclasses."

                chain = []
                seen_classes = set()
                for r in rows:
                    if r['class_name'] not in seen_classes:
                        chain.append(r['class_name'])
                        seen_classes.add(r['class_name'])

                method_names = [r['method_name'] for r in rows if r['method_name']]
                unique_method_names = []
                seen_method_names = set()
                for name in method_names:
                    if name not in seen_method_names:
                        unique_method_names.append(name)
                        seen_method_names.add(name)

                md = [f"## `{class_name}`"]
                class_comment = (rows[0]['class_comment'] or '').strip()
                if class_comment:
                    md.append(f"> {class_comment}")
                if not compact:
                    if declared_only:
                        md.append("Scope: methods declared by this class only")
                    elif len(chain) > 1:
                        md.append(f"继承链: `{' -> '.join(reversed(chain))}`")
                    if method_filter and len(unique_method_names) == 1:
                        md.append(f"方法: `{unique_method_names[0]}`")

                last_class = None
                single_class = len(chain) == 1
                single_filtered_method = method_filter and len(unique_method_names) == 1
                for row in rows:
                    if row['class_name'] != last_class:
                        if not compact and not single_class:
                            title = "### Current class" if row['depth'] == 0 else f"### Superclass L{row['depth']}"
                            md.append(title)
                            if row['class_name'] != class_name:
                                class_comment = (row['class_comment'] or '').strip()
                                md.append(f"`{row['class_name']}`")
                                if class_comment:
                                    md.append(f"> {class_comment}")
                        last_class = row['class_name']
                    if row['method_name']:
                        pretty_signature = self._format_method_signature(row["method_name"], row["method_signature"], row["return_type"])
                        detail_class_simple = row['class_name'].rsplit('.', 1)[-1]
                        cheat_tag = " `[速查已收录·免验证]`" if (detail_class_simple, row['method_name']) in self._cheat_methods else ""
                        if compact:
                            method_label = row['method_name'] if not (single_filtered_method and len(unique_method_names) == 1) else None
                            if method_label:
                                md.append(f"- `{method_label}{pretty_signature}`{cheat_tag}")
                            else:
                                md.append(f"- `{pretty_signature}`{cheat_tag}")
                        elif single_filtered_method:
                            md.append(f"- `{pretty_signature}`{cheat_tag}")
                        else:
                            md.append(f"- **{row['method_name']}** `{pretty_signature}`{cheat_tag}")
                        description, params, return_desc, throws_desc = self._parse_method_comment(row["method_comment"])
                        if description:
                            md.append(f"  - 说明: {description}")
                        for param_name, param_desc in params:
                            if param_name and param_desc:
                                md.append(f"  - 参数 `{param_name}`: {param_desc}")
                            elif param_name:
                                md.append(f"  - 参数 `{param_name}`")
                            elif param_desc:
                                md.append(f"  - 参数: {param_desc}")
                        if return_desc:
                            md.append(f"  - 返回: {return_desc}")
                        for throws_item in throws_desc:
                            if throws_item:
                                md.append(f"  - 异常: {throws_item}")
                return "\n".join(md)
        except Exception as e: return f"✖️ 查询失败: {str(e)}"


def _split_batch_queries(query_args: list) -> list:
    """Detect comma-separated batch queries and return query groups.
    - 无逗号 → [["kw1", "kw2"]]  (单次搜索)
    - 有逗号 → [["kwA"], ["kwB", "kwC"]]  (批量搜索)
    """
    joined = " ".join(query_args)
    if "," not in joined:
        return [query_args]
    groups = [g.strip() for g in joined.split(",") if g.strip()]
    return [g.split() for g in groups]


def main():
    parser = FriendlyArgumentParser(
        description="Cosmic API Knowledge CLI — 苍穹 SDK 类名/方法签名离线查询",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
Commands:
  %(prog)s --config ok-cosmic.json search Helper --class-prefix kd.bos.servicehelper --kind helper
  %(prog)s --config ok-cosmic.json search-method send email --class-prefix kd.bos.servicehelper.message
  %(prog)s --config ok-cosmic.json search plugin operation --kind plugin --all
  %(prog)s --config ok-cosmic.json detail "kd.bos.servicehelper.QueryServiceHelper" --method loadSingle
  %(prog)s --config ok-cosmic.json detail "kd.bos.servicehelper.QueryServiceHelper" --method loadSingle --declared-only
  %(prog)s --config ok-cosmic.json detail "kd.bos.servicehelper.QueryServiceHelper" --method loadSingle --compact
""",
    )
    parser.add_argument("--config", help="Path to ok-cosmic.json config file")
    
    subparsers = parser.add_subparsers(dest="command", help="Commands")
    
    # Search class command
    search_parser = subparsers.add_parser("search", help="Search for classes (fuzzy or regex)")
    search_parser.add_argument("query", nargs="+", help="Search keyword(s) or regex pattern")
    search_parser.add_argument("--regex", action="store_true", help="Use regex for searching")
    search_parser.add_argument("--all", action="store_true", help="Require all keywords to match. Default is any keyword match")
    search_parser.add_argument("--class-prefix", action="append", default=[], help="Only include classes under package/class prefix, e.g. kd.bos.servicehelper")
    search_parser.add_argument("--class-regex", help="Further filter classes by regex on full class name")
    search_parser.add_argument(
        "--kind",
        action="append",
        default=[],
        choices=["helper", "servicehelper", "plugin", "service", "utils", "runtime", "entity", "const", "enum", "controller"],
        help="Common class category filter on class name",
    )
    search_parser.add_argument("--page", type=int, default=1, help="Page number")
    search_parser.add_argument("--page-size", type=int, default=20, help="Items per page")
    
    # Search method command
    msearch_parser = subparsers.add_parser("search-method", help="Search for methods across all classes")
    msearch_parser.add_argument("query", nargs="+", help="Method name keyword(s)")
    msearch_parser.add_argument("--all", action="store_true", help="Require all keywords to match. Default is any keyword match")
    msearch_parser.add_argument("--class-prefix", action="append", default=[], help="Only include methods defined in classes under this prefix")
    msearch_parser.add_argument("--class-regex", help="Further filter method results by class-name regex")
    msearch_parser.add_argument(
        "--kind",
        action="append",
        default=[],
        choices=["helper", "servicehelper", "plugin", "service", "utils", "runtime", "entity", "const", "enum", "controller"],
        help="Common class category filter on class name",
    )
    msearch_parser.add_argument("--page", type=int, default=1, help="Page number")
    msearch_parser.add_argument("--page-size", type=int, default=20, help="Items per page")
    
    # Detail command
    detail_parser = subparsers.add_parser("detail", help="Get class details")
    detail_parser.add_argument("classname", help="Full class name(s), comma-separated for batch query")
    detail_parser.add_argument("--method", help="Filter methods by name (fuzzy)")
    detail_parser.add_argument("--declared-only", action="store_true", help="Only show methods declared on the target class")
    detail_parser.add_argument("--compact", action="store_true", help="Compact output for AI consumption")
    
    args = parser.parse_args()
    
    config = load_project_config(args.config)
    batch_workers = max(1, get_int(config, "limits", "batchWorkers", 4))
    api = ApiGraph(config)
    
    if args.command == "search":
        query_groups = _split_batch_queries(args.query)

        def _search_one(q):
            return api.fuzzy_search_classes(
                q, args.page, args.page_size,
                use_regex=args.regex,
                match_all_terms=args.all,
                class_prefixes=args.class_prefix,
                class_regex=args.class_regex,
                class_keywords=args.kind,
            )

        if len(query_groups) == 1:
            results = [_search_one(query_groups[0])]
        else:
            results = [None] * len(query_groups)
            with ThreadPoolExecutor(max_workers=min(len(query_groups), batch_workers)) as pool:
                fut = {pool.submit(_search_one, q): i for i, q in enumerate(query_groups)}
                for f in as_completed(fut):
                    idx = fut[f]
                    try:
                        results[idx] = f.result()
                    except Exception as e:
                        results[idx] = f"✖️ 搜索 `{' '.join(query_groups[idx])}` 失败: {e}"
        print("\n\n---\n\n".join(results))

    elif args.command == "search-method":
        query_groups = _split_batch_queries(args.query)

        def _msearch_one(q):
            return api.search_methods(
                q, args.page, args.page_size,
                match_all_terms=args.all,
                class_prefixes=args.class_prefix,
                class_regex=args.class_regex,
                class_keywords=args.kind,
            )

        if len(query_groups) == 1:
            results = [_msearch_one(query_groups[0])]
        else:
            results = [None] * len(query_groups)
            with ThreadPoolExecutor(max_workers=min(len(query_groups), batch_workers)) as pool:
                fut = {pool.submit(_msearch_one, q): i for i, q in enumerate(query_groups)}
                for f in as_completed(fut):
                    idx = fut[f]
                    try:
                        results[idx] = f.result()
                    except Exception as e:
                        results[idx] = f"✖️ 搜索 `{' '.join(query_groups[idx])}` 失败: {e}"
        print("\n\n---\n\n".join(results))
    elif args.command == "detail":
        class_names = [c.strip() for c in args.classname.split(",") if c.strip()]

        def _detail_one(cn):
            return api.get_class_details(
                cn,
                method_filter=args.method,
                declared_only=args.declared_only,
                compact=args.compact,
            )

        if len(class_names) == 1:
            results = [_detail_one(class_names[0])]
        else:
            results = [None] * len(class_names)
            with ThreadPoolExecutor(max_workers=min(len(class_names), batch_workers)) as pool:
                future_to_idx = {
                    pool.submit(_detail_one, cn): i
                    for i, cn in enumerate(class_names)
                }
                for future in as_completed(future_to_idx):
                    idx = future_to_idx[future]
                    try:
                        results[idx] = future.result()
                    except Exception as e:
                        results[idx] = f"✖️ 查询 `{class_names[idx]}` 失败: {e}"

        print("\n\n---\n\n".join(results))
    else:
        parser.print_help()


if __name__ == "__main__":
    sys.exit(run_cli(main))
