# -*- coding: utf-8 -*-
"""资源管理检查 (RESOURCE-*) — 来源: coding-preferences.md"""

import re
from typing import List, Dict, Optional, Set, Tuple

from .base import LintIssue, Severity, analyze_java_context, code_for_structure


# 资源持有规则列表
RESOURCE_RULES = [
    {
        "pattern": r"(private|protected|public)\s+(DataSet|InputStream|OutputStream|Connection|ResultSet)\s+\w+\s*;",
        "rule_id": "RESOURCE-001",
        "severity": Severity.WARNING,
        "message": "插件成员变量不应持有 DataSet/InputStream 等资源对象，有序列化问题",
        "fix_hint": "在方法内使用后立即关闭，不持有引用",
    },
    {
        "pattern": r"static\s+(?!final\b)\w+\s+\w+\s*=",
        "rule_id": "RESOURCE-002",
        "severity": Severity.WARNING,
        "message": "不应使用非 final 的 static 变量存储状态，多实例会冲突",
        "fix_hint": "使用 PageCache 或实例变量",
        "exclude_pattern": r"(static\s+final|static\s+\w+\s+[A-Z_]+\s*=|LogFactory|getLogger|Log\s+log)",
    },
    {
        "pattern": r"static\s+final\s+String\s+\w+\s*=\s*ResManager\s*\.\s*loadKDString\s*\(",
        "rule_id": "RESOURCE-003",
        "severity": Severity.WARNING,
        "message": "不要将 ResManager.loadKDString(...) 固化为 static final 常量，运行时语言切换会失效",
        "fix_hint": "改为方法内实时调用 ResManager.loadKDString(...)",
    },
]

DATASET_VAR_DECL_PATTERN = re.compile(r"\bDataSet\s+([A-Za-z_]\w*)\s*=")
DATASET_CLOSE_PATTERN = re.compile(r"\b([A-Za-z_]\w*)\s*\.\s*close\s*\(")
DATASET_TRY_WITH_RESOURCE_PATTERN = re.compile(r"\btry\s*\(\s*DataSet\s+([A-Za-z_]\w*)\s*=")
DATASET_RETURN_PATTERN = re.compile(r"\breturn\s+([A-Za-z_]\w*)\s*;")


def _collect_method_ranges(
    method_context: List[Optional[str]], total: int
) -> List[Tuple[Optional[str], int, int]]:
    """从 method_context 收集连续同方法行范围 [(method_name, start, end), ...]。"""
    ranges: List[Tuple[Optional[str], int, int]] = []
    if not method_context:
        return ranges
    cur_method = method_context[0]
    start = 0
    for i in range(1, total):
        if method_context[i] != cur_method:
            ranges.append((cur_method, start, i))
            cur_method = method_context[i]
            start = i
    ranges.append((cur_method, start, total))
    return ranges


def check(filepath: str, lines: List[str]) -> List[LintIssue]:
    """执行资源管理检查，返回问题列表。"""
    issues: List[LintIssue] = []
    method_context, _ = analyze_java_context(lines)

    # ── 逐行：RESOURCE-001 / 002 / 003（文件级即可） ──
    for i, line in enumerate(lines):
        lineno = i + 1
        code_line = code_for_structure(line)
        for rule in RESOURCE_RULES:
            exclude = rule.get("exclude_pattern")
            if exclude and re.search(exclude, code_line):
                continue
            if re.search(rule["pattern"], code_line):
                issues.append(LintIssue(
                    file=filepath, line=lineno,
                    severity=rule["severity"],
                    rule_id=rule["rule_id"],
                    message=rule["message"],
                    fix_hint=rule["fix_hint"],
                    source_line=line.strip(),
                ))

    # ── RESOURCE-004：按方法级隔离追踪 DataSet 生命周期 ──
    method_ranges = _collect_method_ranges(method_context, len(lines))
    for method_name, m_start, m_end in method_ranges:
        if method_name is None:
            continue
        _check_dataset_lifecycle(filepath, lines, m_start, m_end, issues)

    return issues


def _check_dataset_lifecycle(
    filepath: str, lines: List[str], m_start: int, m_end: int,
    issues: List[LintIssue],
) -> None:
    """在一个方法范围内追踪 DataSet 声明/关闭/消费状态。"""
    dataset_vars: Dict[str, int] = {}        # name -> declaration line
    closed_vars: Set[str] = set()
    consumed_vars: Set[str] = set()
    in_try_resource = False
    try_paren_depth = 0
    # 用于跨行消费检测：记录最近的 DataSet 声明变量名
    pending_dataset_decl: Optional[str] = None

    for i in range(m_start, m_end):
        lineno = i + 1
        code_line = code_for_structure(lines[i])

        # ── 声明检测 ──
        decl_match = DATASET_VAR_DECL_PATTERN.search(code_line)
        if decl_match:
            var_name = decl_match.group(1)
            # 同方法内同名变量取最新声明行
            dataset_vars[var_name] = lineno
            pending_dataset_decl = var_name

            # 同行 RHS 消费检测：DataSet result = ds1.union(ds2)
            eq_idx = code_line.find("=", code_line.find(var_name))
            if eq_idx >= 0:
                rhs = code_line[eq_idx + 1:]
                for tracked_var in list(dataset_vars):
                    if tracked_var != var_name and re.search(
                        rf"\b{re.escape(tracked_var)}\b", rhs
                    ):
                        consumed_vars.add(tracked_var)
                        # 派生链传递：如果源被管理（在 try 块或已被标记为消费），派生变量同享管理
                        if tracked_var in closed_vars or tracked_var in consumed_vars:
                            consumed_vars.add(var_name)
        else:
            # ── 跨行消费检测：上一行 DataSet xxx = ds1，本行 .union(ds2) ──
            if pending_dataset_decl and code_line.strip().startswith("."):
                for tracked_var in list(dataset_vars):
                    if tracked_var != pending_dataset_decl and re.search(
                        rf"\b{re.escape(tracked_var)}\b", code_line
                    ):
                        consumed_vars.add(tracked_var)
                        if tracked_var in closed_vars or tracked_var in consumed_vars:
                            consumed_vars.add(pending_dataset_decl)
            else:
                pending_dataset_decl = None

        # ── try-with-resources 检测 ──
        if re.search(r"\btry\s*\(", code_line):
            in_try_resource = True
            
        if in_try_resource:
            try_paren_depth += code_line.count("(") - code_line.count(")")
            for vn in DATASET_VAR_DECL_PATTERN.findall(code_line):
                closed_vars.add(vn)
            if try_paren_depth <= 0:
                in_try_resource = False
                try_paren_depth = 0

        # ── .close() 检测 ──
        for close_var in DATASET_CLOSE_PATTERN.findall(code_line):
            closed_vars.add(close_var)

        # ── return 消费检测 ──
        return_match = DATASET_RETURN_PATTERN.search(code_line)
        if return_match and return_match.group(1) in dataset_vars:
            consumed_vars.add(return_match.group(1))

    # ── 汇总本方法的 RESOURCE-004 ──
    for var_name, decl_line in dataset_vars.items():
        if var_name in closed_vars or var_name in consumed_vars:
            continue
        issues.append(LintIssue(
            file=filepath,
            line=decl_line,
            severity=Severity.ERROR,
            rule_id="RESOURCE-004",
            message=f"检测到 DataSet 变量 `{var_name}` 但未发现 close() 调用，也未被 return 或后续 DataSet 计算消费",
            fix_hint="在最靠近使用完成的位置调用 close()；若该 DataSet 是方法返回值或已参与计算/合并则无需关闭",
            source_line=lines[decl_line - 1].strip(),
        ))
