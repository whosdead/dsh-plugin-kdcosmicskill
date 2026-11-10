# -*- coding: utf-8 -*-
"""公共数据结构与工具函数，所有检查模块共享。"""

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

import tree_sitter
import tree_sitter_java


# ──────────────────────────────────────────────
# 数据结构
# ──────────────────────────────────────────────

class Severity(str, Enum):
    ERROR = "ERROR"      # 必定错误
    WARNING = "WARNING"  # 场景错配 / 强烈不推荐
    INFO = "INFO"        # 风格偏好 / 建议


@dataclass
class LintIssue:
    file: str
    line: int
    severity: Severity
    rule_id: str
    message: str
    fix_hint: str = ""
    source_line: str = ""


@dataclass
class LintReport:
    total_files: int = 0
    total_issues: int = 0
    errors: int = 0
    warnings: int = 0
    infos: int = 0
    issues: List[LintIssue] = field(default_factory=list)

    def add(self, issue: LintIssue):
        self.issues.append(issue)
        self.total_issues += 1
        if issue.severity == Severity.ERROR:
            self.errors += 1
        elif issue.severity == Severity.WARNING:
            self.warnings += 1
        else:
            self.infos += 1


# ──────────────────────────────────────────────
# 注释 / 字符串状态机
# ──────────────────────────────────────────────

def classify_lines(lines: List[str]) -> List[bool]:
    """对每行标记是否为"纯注释或空行"（True=非代码行，应跳过 lint）。

    使用状态机追踪多行注释块（/* ... */），
    单行内同时识别行注释、字符串字面量，避免误判。
    支持行中间开始的块注释（如 `code; /* comment start`）。
    """
    result: List[bool] = []
    in_block_comment = False
    in_text_block = False

    for line in lines:
        if in_text_block:
            end_idx = line.find('"""')
            if end_idx >= 0:
                in_text_block = False
                remainder = line[end_idx + 3:].strip()
                result.append(not bool(remainder))
            else:
                result.append(True)
            continue

        if in_block_comment:
            # 当前处于 /* ... */ 块注释中
            end_idx = line.find("*/")
            if end_idx >= 0:
                in_block_comment = False
                # 关闭后如果剩余部分有实质代码，视为代码行
                remainder = line[end_idx + 2:].strip()
                result.append(not bool(remainder))
            else:
                result.append(True)
            continue

        stripped = line.strip()

        # 检查是否进入 Text Block (""")
        if '"""' in stripped and stripped.count('"""') % 2 != 0:
            in_text_block = True
            result.append(False) # 开启"""的这一行本身属于代码声明
            continue
        stripped = line.strip()
        # 空行
        if not stripped:
            result.append(True)
            continue
        # 纯单行注释
        if stripped.startswith("//"):
            result.append(True)
            continue
        # Javadoc / 多行注释续行（行首 *）
        if stripped.startswith("*") and not stripped.startswith("*/"):
            result.append(True)
            continue
        # 检查块注释开始（行首或行中间）
        if stripped.startswith("/*"):
            close_idx = stripped.find("*/", 2)
            if close_idx >= 0:
                # 单行块注释 /* ... */，检查 */ 后是否有代码
                remainder = stripped[close_idx + 2:].strip()
                result.append(not bool(remainder))
            else:
                in_block_comment = True
                result.append(True)
            continue

        # 代码行：检查行中间是否有未闭合的 /* 开始块注释
        # 需要排除字符串字面量内的 /*
        if _has_unclosed_block_comment_start(stripped):
            in_block_comment = True
            # 行本身有代码部分，标记为代码行
            result.append(False)
            continue

        # 其他情况：代码行（可能包含行尾注释，但行本身有代码）
        result.append(False)

    return result


def _has_unclosed_block_comment_start(line: str) -> bool:
    """检测行中间是否有未闭合的 /* 块注释开始（排除字符串内的 /*）。"""
    i = 0
    in_str = False
    str_char = ''
    while i < len(line):
        c = line[i]
        if in_str:
            if c == '\\' and i + 1 < len(line):
                i += 2  # 跳过转义字符
                continue
            if c == str_char:
                in_str = False
            i += 1
            continue
        if c in ('"', "'"):
            in_str = True
            str_char = c
            i += 1
            continue
        if c == '/' and i + 1 < len(line):
            if line[i + 1] == '/':
                return False  # 行注释，不可能有 /* 了
            if line[i + 1] == '*':
                # 找到 /*，检查本行内是否有 */
                close_idx = line.find('*/', i + 2)
                if close_idx >= 0:
                    # 同行闭合 /* ... */，跳过继续
                    i = close_idx + 2
                    continue
                else:
                    return True  # 未闭合的 /* 块注释开始
        i += 1
    return False


def is_comment_or_string(line: str) -> bool:
    """粗略判断当前行是否为注释或字符串（排除误报）。

    注意：此函数为单行判断，无法追踪跨行 /* */ 状态。
    对跨行精确判断，请使用 classify_lines() 代替。
    """
    stripped = line.strip()
    if not stripped:
        return True
    return (stripped.startswith("//")
            or stripped.startswith("*")
            or stripped.startswith("/*"))


def strip_line_comment(line: str) -> str:
    """移除单行注释部分，保留代码主体（正确跳过字符串字面量内的 //）。"""
    i = 0
    in_str = False
    str_char = ''
    while i < len(line):
        c = line[i]
        if in_str:
            if c == '\\' and i + 1 < len(line):
                i += 2
                continue
            if c == str_char:
                in_str = False
            i += 1
            continue
        if c in ('"', "'"):
            in_str = True
            str_char = c
            i += 1
            continue
        if c == '/' and i + 1 < len(line) and line[i + 1] == '/':
            return line[:i]
        i += 1
    return line


def strip_string_literals(line: str) -> str:
    """移除字符串字面量，便于做结构分析。"""
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', '""', line)


def code_for_structure(line: str) -> str:
    """用于结构分析的代码行：去掉行注释与字符串。"""
    return strip_line_comment(strip_string_literals(line))


METHOD_DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public|protected|private)\s+"
    r"(?:static\s+|final\s+|synchronized\s+|abstract\s+|native\s+|default\s+)*"
    r"[\w<>\[\], ?.@]+\s+([A-Za-z_]\w*)\s*"
    r"\([^;{}]*\)\s*(?:throws\s+[^{]+)?\s*(?:\{)?\s*$"
)

LOOP_HEADER_RE = re.compile(r"\bfor\s*\(|\bwhile\s*\(|^\s*do\b")
LAMBDA_ITER_RE = re.compile(r"\.\s*(forEach|map|flatMap|peek)\s*\(")
CLASS_DECL_RE = re.compile(r"\bclass\s+[A-Za-z_]\w*")


def looks_like_loop_header(line: str) -> bool:
    """判断当前行是否看起来是 loop 头。"""
    return bool(LOOP_HEADER_RE.search(code_for_structure(line)))


def parse_java(lines: List[str]) -> Tuple[Any, Any]:
    """解析 Java 源码，返回 (tree, language) 供多处复用，避免重复解析。"""
    lang = tree_sitter.Language(tree_sitter_java.language())
    parser = tree_sitter.Parser(lang)
    src_bytes = "\n".join(lines).encode('utf-8')
    tree = parser.parse(src_bytes)
    return tree, lang


def analyze_java_context(lines: List[str], tree=None) -> Tuple[List[Optional[str]], List[bool]]:
    """
    基于 AST 构建上下文：
    - 每行所在方法名
    - 每行是否位于循环体中
    """
    if tree is None:
        tree, _ = parse_java(lines)

    method_ctx: List[Optional[str]] = [None] * len(lines)
    loop_ctx: List[bool] = [False] * len(lines)
    
    def walk(node, current_method=None, in_loop=False):
        m_name = current_method
        if node.type in ("method_declaration", "constructor_declaration"):
            name_node = node.child_by_field_name("name")
            if name_node:
                m_name = name_node.text.decode('utf-8')
        
        is_loop = in_loop
        if node.type in ("for_statement", "enhanced_for_statement", "while_statement", "do_statement"):
            is_loop = True
        elif node.type == "method_invocation":
            name_node = node.child_by_field_name("name")
            if name_node and name_node.text.decode('utf-8') in ("forEach", "map", "flatMap", "peek"):
                is_loop = True

        start_row = node.start_point[0]
        end_row = node.end_point[0]
        for r in range(start_row, end_row + 1):
            if r < len(lines):
                method_ctx[r] = m_name
                if is_loop:
                    loop_ctx[r] = True

        for child in node.children:
            walk(child, m_name, is_loop)
            
    walk(tree.root_node)
    return method_ctx, loop_ctx


# 操作插件的类标识模式
OP_PLUGIN_MARKERS = [
    r"extends\s+AbstractOperationServicePlugIn\b",
    r"extends\s+AbstractOperationServicePlugInExt\b",
    r"extends\s+AbstractValidatorExt\b",
    r"implements\s+.*OperationServicePlugIn\b",
]

# UI 插件标识模式
UI_PLUGIN_MARKERS = [
    r"extends\s+AbstractFormPluginExt\b",
    r"extends\s+AbstractBillPlugInExt\b",
    r"extends\s+AbstractListPluginExt\b",
    r"extends\s+AbstractFormPlugin\b",
    r"extends\s+AbstractBillPlugIn\b",
    r"extends\s+AbstractListPlugin\b",
    r"extends\s+AbstractTreeListPlugin\b",
    r"extends\s+StandardTreeListPlugin\b",
]

# 其他继承型插件标识模式（非操作、非 UI，但仍需检查 super 调用）
OTHER_INHERITABLE_MARKERS = [
    r"extends\s+AbstractConvertPlugIn\b",
    r"extends\s+AbstractWriteBackPlugIn\b",
    r"extends\s+AbstractReportFormPlugin\b",
    r"extends\s+AbstractReportListDataPlugin\b",
    r"extends\s+AbstractPrintPlugin\b",
    r"extends\s+AbstractTask\b",
    r"extends\s+BatchImportPlugin\b",
]


def detect_plugin_type(lines: List[str]) -> Tuple[bool, bool, bool]:
    """检测文件是操作插件、UI 插件还是其他继承型插件，返回 (is_op, is_ui, is_other_inheritable)"""
    full_text = "\n".join(lines)
    is_op = any(re.search(p, full_text) for p in OP_PLUGIN_MARKERS)
    is_ui = any(re.search(p, full_text) for p in UI_PLUGIN_MARKERS)
    is_other = any(re.search(p, full_text) for p in OTHER_INHERITABLE_MARKERS)
    return is_op, is_ui, is_other


def _detect_plugin_kind(text: str) -> Optional[str]:
    """基于类声明文本判断当前类属于哪种插件类型。"""
    if any(re.search(p, text) for p in OP_PLUGIN_MARKERS):
        return "op"
    if any(re.search(p, text) for p in UI_PLUGIN_MARKERS):
        return "ui"
    if any(re.search(p, text) for p in OTHER_INHERITABLE_MARKERS):
        return "other"
    return None


def analyze_plugin_context(lines: List[str]) -> List[Optional[str]]:
    """
    基于类声明与 brace 深度分析每一行所在的插件上下文：
    - op: 操作插件类
    - ui: UI 插件类
    - other: 其他继承型插件类
    - None: 非插件类 / 无插件上下文
    """
    contexts: List[Optional[str]] = [None] * len(lines)
    brace_depth = 0
    class_stack: List[Tuple[int, Optional[str]]] = []
    pending_class_decl: List[str] = []

    for i, line in enumerate(lines):
        code = code_for_structure(line)
        stripped = code.strip()
        contexts[i] = class_stack[-1][1] if class_stack else None

        if pending_class_decl:
            if stripped:
                pending_class_decl.append(stripped)
            if "{" in stripped:
                class_text = " ".join(pending_class_decl)
                class_stack.append((brace_depth + 1, _detect_plugin_kind(class_text)))
                pending_class_decl = []
        elif CLASS_DECL_RE.search(stripped):
            pending_class_decl = [stripped]
            if "{" in stripped:
                class_text = " ".join(pending_class_decl)
                class_stack.append((brace_depth + 1, _detect_plugin_kind(class_text)))
                pending_class_decl = []

        brace_depth += code.count("{") - code.count("}")

        while class_stack and brace_depth < class_stack[-1][0]:
            class_stack.pop()

    return contexts


def detect_listener_interfaces(lines: List[str]) -> List[str]:
    """检测文件实现了哪些 Listener 接口"""
    listeners = []
    for line in lines:
        m = re.search(r"implements\s+(.+?)(?:\s*\{|$)", line)
        if m:
            ifaces = [s.strip() for s in m.group(1).split(",")]
            listeners.extend(i for i in ifaces if "Listener" in i)
    return listeners