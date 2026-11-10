#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""Shared SQLite JSON cache helpers for ok-cosmic scripts."""

import json
import os
import re
import sqlite3
import sys
import time
from typing import Any, Dict, Iterable, Optional, Sequence


IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def resolve_graph_db_path(config: Dict[str, Any], missing_message: str) -> str:
    """Resolve graph.dbPath relative to ok-cosmic.json's directory."""
    graph_config = config.get("graph", {})
    if not isinstance(graph_config, dict):
        graph_config = {}

    db_path = str(graph_config.get("dbPath", "")).strip()
    if not db_path:
        raise ValueError(missing_message)

    raw_db_path = os.path.expanduser(db_path)
    if os.path.isabs(raw_db_path):
        return os.path.normpath(raw_db_path)

    base_dir = str(config.get("__config_dir__", "")).strip() or os.getcwd()
    return os.path.normpath(os.path.abspath(os.path.join(base_dir, raw_db_path)))


def _validate_identifier(value: str, label: str) -> str:
    if not IDENTIFIER_RE.match(value):
        raise ValueError(f"{label} 不是安全的 SQLite 标识符: {value}")
    return value


class JsonSqliteCache:
    """Small SQLite cache storing JSON payload + updated_at with TTL."""

    def __init__(
        self,
        db_path: str,
        *,
        table_name: str,
        key_column: str,
        create_sql: str,
        ttl: int,
        busy_timeout_ms: int = 5000,
        check_same_thread: bool = True,
        init_error_message: str = "初始化缓存表失败",
        write_error_message: str = "写入缓存失败",
    ):
        self.db_path = db_path
        self.table_name = _validate_identifier(table_name, "table_name")
        self.key_column = _validate_identifier(key_column, "key_column")
        self.ttl = ttl
        self.init_error_message = init_error_message
        self.write_error_message = write_error_message
        self._conn = None
        try:
            self._conn = sqlite3.connect(self.db_path, check_same_thread=check_same_thread)
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute(f"PRAGMA busy_timeout={int(busy_timeout_ms)}")
            self._conn.row_factory = sqlite3.Row
            self._init_db(create_sql)
        except Exception as e:
            print(f" (DEBUG) {self.init_error_message}: {e}（缓存已禁用，查询仍会继续）", file=sys.stderr)
            self._conn = None

    def _init_db(self, create_sql: str) -> None:
        try:
            self._conn.execute(create_sql)
            self._conn.commit()
        except Exception as e:
            print(f" (DEBUG) {self.init_error_message}: {e}", file=sys.stderr)

    def get(self, key: str) -> Optional[Dict[str, Any]]:
        if self._conn is None:
            return None
        try:
            row = self._conn.execute(
                f"SELECT payload, updated_at FROM {self.table_name} WHERE {self.key_column} = ?",
                (key,),
            ).fetchone()
            if not row or (time.time() - row["updated_at"] > self.ttl):
                return None
            return json.loads(row["payload"])
        except Exception:
            return None

    def set_payload(
        self,
        key: str,
        payload: Dict[str, Any],
        *,
        extra_columns: Optional[Sequence[str]] = None,
        extra_values: Optional[Sequence[Any]] = None,
    ) -> None:
        if self._conn is None:
            return
        extra_columns = tuple(extra_columns or ())
        extra_values = tuple(extra_values or ())
        if len(extra_columns) != len(extra_values):
            raise ValueError("extra_columns 和 extra_values 数量不一致")
        for column in extra_columns:
            _validate_identifier(column, "extra_column")

        columns = (self.key_column, *extra_columns, "payload", "updated_at")
        placeholders = ", ".join("?" for _ in columns)
        column_sql = ", ".join(columns)
        values = (
            key,
            *extra_values,
            json.dumps(payload, ensure_ascii=False),
            int(time.time()),
        )

        try:
            self._conn.execute(
                f"""
                INSERT OR REPLACE INTO {self.table_name}
                ({column_sql})
                VALUES ({placeholders})
                """,
                values,
            )
            self._conn.commit()
        except Exception as e:
            print(f" (DEBUG) {self.write_error_message}: {e}", file=sys.stderr)

    def remove(self, key: str) -> None:
        if self._conn is None:
            return
        try:
            self._conn.execute(
                f"DELETE FROM {self.table_name} WHERE {self.key_column} = ?",
                (key,),
            )
            self._conn.commit()
        except Exception:
            pass

    def close(self) -> None:
        if self._conn is None:
            return
        try:
            self._conn.close()
        except Exception:
            pass

    def __del__(self):
        self.close()
