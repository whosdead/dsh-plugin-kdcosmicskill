# -*- coding: utf-8 -*-
"""场景错配检查 (SCENE-*) — 来源: anti-patterns.md 场景错配表"""

import re
from typing import List, Optional, Set, Tuple

from .base import (
    LintIssue,
    Severity,
    analyze_java_context,
    analyze_plugin_context,
    classify_lines,
    code_for_structure,
    detect_listener_interfaces,
    detect_plugin_type,
    parse_java,
)


INITIALIZE_UI_PATTERN = re.compile(
    r"getView\s*\(\)\s*\.\s*"
    r"(setEnable|setVisible|updateView|show\w+|setFocus|setMustInput|setCaption)\s*\("
)
INITIALIZE_LISTENER_PATTERN = re.compile(r"add\w*Listener[s]?\s*\(")
BIND_MUTATION_PATTERN = re.compile(
    r"(?:(?:getModel\s*\(\)|\bmodel)\s*\.\s*"
    r"(setValue|deleteEntryRow|createNewEntryRow|insertEntryRow|batchCreateNewEntryRow|setDataEntity)\s*\()"
    r"|(?:\.\s*setValueFast\s*\()"
)
META_TABLE_PATTERN = re.compile(r"\bt_meta_[A-Za-z0-9_]+\b", re.IGNORECASE)
ENTITY_METADATA_CREATE_PATTERN = re.compile(
    r"EntityMetadataCache\s*\.\s*getDataEntityType\s*\([^)]*\)\s*\.\s*createInstance\s*\("
)
# SCENE-012: propertyChanged 中无条件 setValue 检测
_PC_SETVALUE_PATTERN = re.compile(
    r"(?:getModel\s*\(\)\s*\.\s*setValue|model\s*\.\s*setValue|\.\s*setValueFast)\s*\("
)
_PC_GUARD_PATTERN = re.compile(r"getProperty\s*\(\s*\)|getChangeSet\s*\(\s*\)")


def check(filepath: str, lines: List[str]) -> List[LintIssue]:
    """执行场景错配检查，返回问题列表。"""
    issues: List[LintIssue] = []
    listeners = detect_listener_interfaces(lines)
    tree, lang = parse_java(lines)
    method_context, _ = analyze_java_context(lines, tree=tree)
    plugin_context = analyze_plugin_context(lines)
    skip_lines = classify_lines(lines)

    # 有状态上下文
    _model_alias_vars: Set[str] = set()  # 追踪操作插件中 getModel() 赋值的变量名
    current_method: Optional[str] = None

    # ── 逐行检查 ──
    for i, line in enumerate(lines):
        lineno = i + 1
        code_line = code_for_structure(line)
        method_name = (method_context[i] or "").lower()
        is_op_line = plugin_context[i] == "op"

        if method_context[i] != current_method:
            _model_alias_vars.clear()
            current_method = method_context[i]

        # SCENE-001: 操作插件不应调 getView()
        if is_op_line and not skip_lines[i]:
            if re.search(r"this\s*\.\s*getView\s*\(\s*\)", code_line) or re.search(r"getView\(\)", code_line):
                if not re.search(r"(log\.|getContextSample\s*\()", code_line):
                    issues.append(LintIssue(
                        file=filepath, line=lineno,
                        severity=Severity.ERROR,
                        rule_id="SCENE-001",
                        message="操作插件中调用 getView()：操作插件无 UI 上下文",
                        fix_hint="使用 log 记录日志或 OpUtils.addErrorMessage() 报告错误",
                        source_line=line.strip(),
                    ))

        # SCENE-002: 操作插件不应通过 model 操作
        if is_op_line and not skip_lines[i]:
            if re.search(r"this\s*\.\s*getModel\s*\(\s*\)\s*\.\s*setValue", code_line):
                issues.append(LintIssue(
                    file=filepath, line=lineno,
                    severity=Severity.ERROR,
                    rule_id="SCENE-002",
                    message="操作插件中调用 getModel().setValue()：操作插件不通过 model 操作",
                    fix_hint="直接操作 DynamicObject 数据包: dataEntity.set(\"field\", value)",
                    source_line=line.strip(),
                ))
            else:
                # 追踪 model 别名: Type varName = this.getModel() 或 getModel()
                _model_assign = re.search(
                    r'\b(\w+)\s*=\s*(?:this\s*\.\s*)?getModel\s*\(\s*\)', code_line
                )
                if _model_assign:
                    _model_alias_vars.add(_model_assign.group(1))
                # 检测 model 别名调用 setValue
                for _mvar in _model_alias_vars:
                    if re.search(rf'\b{re.escape(_mvar)}\s*\.\s*setValue\s*\(', code_line):
                        issues.append(LintIssue(
                            file=filepath, line=lineno,
                            severity=Severity.ERROR,
                            rule_id="SCENE-002",
                            message=f"操作插件中通过 model 变量 '{_mvar}' 调用 setValue()：操作插件不通过 model 操作",
                            fix_hint="直接操作 DynamicObject 数据包: dataEntity.set(\"field\", value)",
                            source_line=line.strip(),
                        ))
                        break

        # SCENE-003: registerListener 中读数据
        if method_name == "registerlistener" and re.search(r"(getModel|getValue)\s*\(", code_line) and not skip_lines[i]:
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.WARNING,
                rule_id="SCENE-003",
                message="在 registerListener 中调用 getValue()：此时数据尚未绑定",
                fix_hint="推迟到 afterBindData() 中处理",
                source_line=line.strip(),
            ))

        # SCENE-006: initialize 中注册监听
        if method_name == "initialize" and INITIALIZE_LISTENER_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="SCENE-006",
                message="禁止在 initialize() 中注册监听事件",
                fix_hint="将 addXxxListener(...) 挪到 registerListener() 中",
                source_line=line.strip(),
            ))

        # SCENE-007: initialize 中做 UI 状态逻辑
        if method_name == "initialize" and INITIALIZE_UI_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="SCENE-007",
                message="禁止在 initialize() 中处理 UI 状态或弹窗逻辑",
                fix_hint="将界面控制逻辑挪到 afterBindData() 或正确的 UI 事件中",
                source_line=line.strip(),
            ))

        # SCENE-008: before/afterBindData 中修改数据对象
        if method_name in {"beforebinddata", "afterbinddata"} and BIND_MUTATION_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="SCENE-008",
                message="禁止在 beforeBindData()/afterBindData() 中修改数据对象",
                fix_hint="将 setValue/分录增删等数据变更挪到 createNewData、afterCreateNewData、propertyChanged 或正确的业务事件",
                source_line=line.strip(),
            ))

        # SCENE-009: 直接访问平台元数据表
        if META_TABLE_PATTERN.search(code_line) and not skip_lines[i]:
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="SCENE-009",
                message="业务代码禁止直接访问平台元数据表 t_meta_xxx",
                fix_hint="使用元数据脚本或平台 API 获取字段/实体信息，不要直接查 t_meta_xxx",
                source_line=line.strip(),
            ))

        # SCENE-010: 用其他实体 createInstance() 构造引用对象
        if ENTITY_METADATA_CREATE_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="SCENE-010",
                message="直接用 EntityMetadataCache.getDataEntityType(...).createInstance() 构造对象，容易出现引用类型不一致",
                fix_hint="优先通过当前实体属性复杂类型或当前实体元数据 createInstance()",
                source_line=line.strip(),
            ))

    # ── 方法级检查: propertyChanged 无条件 setValue ──
    _check_unguarded_setvalue_in_property_changed(filepath, lines, method_context, skip_lines, issues)

    # ── 文件级检查: Listener 注册 ──
    if listeners:
        has_register = any(
            re.search(r"void\s+registerListener\s*\(", l) for l in lines
        )
        if not has_register:
            # SCENE-004: 实现了 Listener 但没有 registerListener 方法
            issues.append(LintIssue(
                file=filepath, line=1,
                severity=Severity.WARNING,
                rule_id="SCENE-004",
                message=f"实现了 Listener 接口 ({', '.join(listeners)}) 但未找到 registerListener 方法",
                fix_hint="在 registerListener() 中调用 addXxxListener() 注册监听",
            ))
        else:
            # SCENE-005: 有 registerListener 但没有 addXxxListener 调用
            # 使用 AST 树精确定位 registerListener 方法体
            has_add_listener = False
            try:
                import tree_sitter
                q = tree_sitter.Query(lang, "(method_declaration name: (identifier) @name body: (block) @body)")
                for match in q.matches(tree.root_node):
                    if len(match) > 1 and isinstance(match[1], dict):
                        match_dict = match[1]
                        m_name_nodes = match_dict.get("name", [])
                        m_body_nodes = match_dict.get("body", [])
                        if m_name_nodes and m_body_nodes:
                            if m_name_nodes[0].text.decode('utf-8') == "registerListener":
                                raw_body_text = m_body_nodes[0].text.decode('utf-8')
                                clean_body_lines = [code_for_structure(l) for l in raw_body_text.splitlines()]
                                body_text = "\n".join(clean_body_lines)
                                if re.search(r"add\w*Listener[s]?\s*\(", body_text):
                                    has_add_listener = True
            except Exception:
                # 降级：如果 AST 失败，假设通过避免误报
                has_add_listener = True

            if not has_add_listener:
                issues.append(LintIssue(
                    file=filepath, line=1,
                    severity=Severity.WARNING,
                    rule_id="SCENE-005",
                    message=f"实现了 Listener 接口 ({', '.join(listeners)}) 但 registerListener 中未调用 addXxxListener()",
                    fix_hint="在 registerListener() 中调用对应的 addXxxListener(this) 注册",
                ))

    # ── 文件级检查: 继承型插件 @Override 不调 super ──
    is_op, is_ui, is_other = detect_plugin_type(lines)
    is_inheritable = is_op or is_ui or is_other
    if is_inheritable:
        _check_missing_super(filepath, lines, plugin_context, issues, tree=tree, lang=lang)

    return issues


def _check_unguarded_setvalue_in_property_changed(
    filepath: str, lines: List[str],
    method_context: List, skip_lines: List[bool], issues: List[LintIssue],
):
    """SCENE-012: propertyChanged 中调用 setValue 但未判断 getProperty() 或 getChangeSet()，死循环风险。"""
    # 收集 propertyChanged 方法体的行范围
    ranges: List[Tuple[int, int]] = []
    start: int = -1
    for i, mc in enumerate(method_context):
        if mc and mc.lower() == "propertychanged":
            if start < 0:
                start = i
        else:
            if start >= 0:
                ranges.append((start, i))
                start = -1
    if start >= 0:
        ranges.append((start, len(lines)))

    for s, e in ranges:
        has_set_value = False
        has_guard = False
        first_sv_line: int = -1
        for i in range(s, e):
            if skip_lines[i]:
                continue
            code = code_for_structure(lines[i])
            if _PC_SETVALUE_PATTERN.search(code):
                if not has_set_value:
                    first_sv_line = i
                has_set_value = True
            if _PC_GUARD_PATTERN.search(code):
                has_guard = True
        if has_set_value and not has_guard:
            issues.append(LintIssue(
                file=filepath, line=first_sv_line + 1,
                severity=Severity.ERROR,
                rule_id="SCENE-012",
                message="propertyChanged 中调用 setValue 但未判断 e.getProperty().getName()，可能导致死循环（栈溢出）",
                fix_hint="先用 e.getProperty().getName().equals(\"目标字段\") 守卫，赋值前比对新旧值避免无限递归",
                source_line=lines[first_sv_line].strip(),
            ))


# 继承型插件生命周期方法（需要调 super 的方法名，全小写）
_LIFECYCLE_METHODS = {
    "initialize", "registerlistener", "preopenform",
    "createnewdata", "aftercreatenewdata", "loaddata", "afterloaddata",
    "beforebinddata", "afterbinddata", "aftercopydata",
    "propertychanged", "beforedooperation", "afterdooperation",
    "beforeitemclick", "itemclick", "beforeclick", "click",
    "beforef7select", "confirmcallback", "closedcallback",
    "clientcallback", "beforeclosed", "customevent",
    "afteraddrow", "afterdeleterow", "afterdeleteentry", "destory",
    "onpreparepropertys", "onaddvalidators",
    "beforeexecuteoperationtransaction", "beginoperationtransaction",
    "endoperationtransaction", "afterexecuteoperationtransaction",
    "onreturnoperation",
    "setfilter", "beforedoselectrow",
}

_OVERRIDE_PATTERN = re.compile(r"@Override")
_METHOD_NAME_PATTERN = re.compile(
    r"(?:public|protected)\s+\w[\w<>\[\], ?.]*\s+([A-Za-z_]\w*)\s*\("
)
_SUPER_CALL_PATTERN_TEMPLATE = r"\bsuper\s*\.\s*{method}\s*\("


def _check_missing_super(filepath: str, lines: List[str],
                         plugin_context: List, issues: List[LintIssue],
                         tree=None, lang=None):
    """检测继承型插件中 @Override 生命周期方法是否遗漏 super 调用。"""
    try:
        import tree_sitter
        if tree is None or lang is None:
            tree, lang = parse_java(lines)
        
        q = tree_sitter.Query(lang, """
            (method_declaration
                name: (identifier) @name
                body: (block) @body
            ) @method
        """)
        
        for match in q.matches(tree.root_node):
            if len(match) < 2 or not isinstance(match[1], dict):
                continue
            match_dict = match[1]
            
            method_nodes = match_dict.get("method", [])
            if not method_nodes: continue
            method_node = method_nodes[0]
            
            # Check @Override
            is_override = False
            for child in method_node.children:
                if child.type == "modifiers":
                    for mod in child.children:
                        if mod.type == "marker_annotation" and "Override" in mod.text.decode('utf-8'):
                            is_override = True
            
            if not is_override:
                continue

                
            # Get Method Name
            name_nodes = match_dict.get("name", [])
            if not name_nodes: continue
            method_name = name_nodes[0].text.decode('utf-8')
            
            if method_name.lower() not in _LIFECYCLE_METHODS:
                continue
                
            # Check Plugin Context for this method
            # Just take the context of the line where method name is
            method_line = name_nodes[0].start_point[0]
            if method_line < len(plugin_context) and plugin_context[method_line] not in {"op", "ui", "other"}:
                continue
                
            # Check if super.methodName() exists in the body
            body_nodes = match_dict.get("body", [])
            if not body_nodes: continue
            raw_body_text = body_nodes[0].text.decode('utf-8')
            
            clean_body_lines = [code_for_structure(l) for l in raw_body_text.splitlines()]
            body_text = "\n".join(clean_body_lines)
            
            super_pattern = re.compile(
                _SUPER_CALL_PATTERN_TEMPLATE.format(method=re.escape(method_name))
            )
            
            if not super_pattern.search(body_text):
                issues.append(LintIssue(
                    file=filepath, line=method_line + 1,
                    severity=Severity.WARNING,
                    rule_id="SCENE-011",
                    message=f"继承型插件 @Override {method_name}() 未调用 super.{method_name}()，基类初始化逻辑可能不执行",
                    fix_hint=f"在方法体首行添加 super.{method_name}(...)；接口型插件（如 IWorkflowPlugin）无需此调用",
                    source_line=method_name,
                ))
    except Exception:
        # 降级：如果 AST 失败，不做检查，避免由于解析失败导致的误报
        pass