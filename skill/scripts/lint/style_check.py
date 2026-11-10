# -*- coding: utf-8 -*-
"""编码偏好检查 (STYLE-*) — 来源: coding-preferences.md"""

import re
from typing import List

from .base import (
    LintIssue,
    Severity,
    analyze_java_context,
    analyze_plugin_context,
    classify_lines,
    code_for_structure,
    detect_plugin_type,
    looks_like_loop_header,
    strip_line_comment,
)


# 编码偏好规则列表
STYLE_RULES = [
    {
        "pattern": r"\bStringUtils\s*\.\s*(isBlank|isNotBlank|isEmpty|isNotEmpty|equals)\b",
        "rule_id": "STYLE-001",
        "severity": Severity.WARNING,
        "message": "字符串判空应使用 CharSequenceUtils 而非 StringUtils",
        "fix_hint": "使用 CharSequenceUtils.isBlank() / isNotBlank()",
    },
    {
        "pattern": r"(?:!=\s*null\s*&&\s*!\w+\.isEmpty\(\))|(?:==\s*null\s*\|\|\s*\w+\.isEmpty\(\))",
        "rule_id": "STYLE-002",
        "severity": Severity.WARNING,
        "message": "手写 null/isEmpty 判空应使用工具类封装",
        "fix_hint": "字符串用 CharSequenceUtils.isBlank() / isNotBlank()；集合用 CollectionUtils.isEmpty() / isNotEmpty()",
    },
    {
        "pattern": r"\bOperationServiceHelper\s*\.\s*(save|submit|audit)\b",
        "rule_id": "STYLE-003",
        "severity": Severity.WARNING,
        "message": "不应散落调用 OperationServiceHelper，缺少错误聚合",
        "fix_hint": "使用 OpUtils.executeOperateOrThrow() 或 OperateChain",
    },
    {
        "pattern": r"new\s+PushArgs\s*\(",
        "rule_id": "STYLE-004",
        "severity": Severity.WARNING,
        "message": "不应手拼 PushArgs，有封装可用",
        "fix_hint": "使用 BotpUtils 封装下推逻辑",
    },
    {
        "pattern": r"new\s+DrawArgs\s*\(",
        "rule_id": "STYLE-005",
        "severity": Severity.WARNING,
        "message": "不应手拼 DrawArgs，有封装可用",
        "fix_hint": "使用 BotpUtils 封装选单逻辑",
    },
    {
        "pattern": r"\.\s*get\(\s*\"[\w]+\.[\w.]+\"",
        "rule_id": "STYLE-006",
        "severity": Severity.WARNING,
        "message": "不应直接深链式 .get(\"a.b.c\")，中间节点为空时会抛异常",
        "fix_hint": "使用 DynamicObjectUtils.nullSafeGet(dynamicObject, \"field\")",
    },
    {
        "pattern": r"\bAttachmentServiceHelper\s*\.",
        "rule_id": "STYLE-007",
        "severity": Severity.WARNING,
        "message": "附件处理应优先使用 AttachmentUtils 封装",
        "fix_hint": "使用 AttachmentUtils 和 uploader",
    },
    {
        "pattern": r"\bQueryServiceHelper\s*\.\s*queryOne\s*\([^)]*\)\s*(?:!=|==)\s*null",
        "rule_id": "STYLE-008",
        "severity": Severity.WARNING,
        "message": "判断数据是否存在时，优先使用 QueryServiceHelper.exists(...)",
        "fix_hint": "将 queryOne(...) != null / == null 改为 QueryServiceHelper.exists(...)",
    },
    {
        "pattern": r"\bprintStackTrace\s*\(",
        "rule_id": "STYLE-009",
        "severity": Severity.ERROR,
        "message": "不要直接调用 printStackTrace()，应使用统一日志框架",
        "fix_hint": "改为 logger.error(\"错误分析描述\", e) 或插件内的 log.error(...)",
    },
    {
        "pattern": r"\bthrow\s+new\s+(?:(?:java\.lang\.)?RuntimeException|(?:java\.lang\.)?IllegalArgumentException|(?:java\.lang\.)?IllegalStateException)\b",
        "rule_id": "STYLE-018",
        "severity": Severity.ERROR,
        "message": "业务异常应统一使用 KDBizException，不要直接抛 RuntimeException/IllegalArgumentException/IllegalStateException",
        "fix_hint": "改为 throw new KDBizException(new ErrorCode(...))；若为包装异常，保留原始 cause",
    },
    {
        "pattern": r"\bnew\s+Thread\s*\(",
        "rule_id": "STYLE-019",
        "severity": Severity.WARNING,
        "message": "禁止直接 new Thread()，绕开了平台线程池的统一监控与资源回收",
        "fix_hint": "使用 kd.bos.threads.ThreadPools.newXxx() 或 ThreadPools.executeOnceXxx()",
    },
    {
        "pattern": r"\bExecutors\s*\.\s*(newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor|newScheduledThreadPool)\b",
        "rule_id": "STYLE-020",
        "severity": Severity.WARNING,
        "message": "禁止直接使用 JDK Executors 创建线程池，无法遵守平台线程治理约束",
        "fix_hint": "使用 kd.bos.threads.ThreadPools.newXxx() 或 ThreadPools.executeOnceXxx()",
    },
    {
        "pattern": r"\bSerializationUtils\s*\.\s*toJsonString\s*\([^)]*\b(args|e|event|evt|dataEntity|dataEntities|view)\b",
        "rule_id": "STYLE-021",
        "severity": Severity.WARNING,
        "message": "不要对页面对象/事件对象/数据对象整包 JSON 序列化打印，成本高且可能打印大对象",
        "fix_hint": "只按需提取关键字段打印，如 log.info(\"billNo={}\", data.getString(\"billno\"))",
    },
    {
        "pattern": r"\bBusinessDataServiceHelper\s*\.\s*load\s*\([^,]+,\s*(?:EntityUtils\.getMainEntityType|BusinessDataServiceHelper\.newDynamicObject|EntityMetadataCache\.getDataEntityType)",
        "rule_id": "STYLE-025",
        "severity": Severity.WARNING,
        "message": "加载实体时应指定必要字段，避免全量加载",
        "fix_hint": "使用 BusinessDataServiceHelper.load(entityName, selectFields, filters) 指定查询字段",
    },
]

# 主键/id 判空检测（STYLE-026）
# 检测模式：xxxId/xxxPk 变量与 null/0/0L 比较，或 .get("id"/"xxx_id") 与 null 比较
PK_NULL_CHECK_PATTERNS = [
    # xxxId == null / xxxId != null / xxxPk == null / xxxPk != null
    re.compile(r'\b\w*(?:[Ii]d|[Pp]k)\s*(?:==|!=)\s*null\b'),
    # xxxId == 0L / xxxId == 0 / xxxId <= 0L / xxxId <= 0 / xxxId < 1
    re.compile(r'\b\w*(?:[Ii]d|[Pp]k)\s*(?:==|<=|<)\s*(?:0L?|1)\b'),
    # .get("id") == null / .get("id") != null
    re.compile(r'\.\s*get\s*\(\s*"\w*[Ii]d"\s*\)\s*(?:==|!=)\s*null'),
]

# BigDecimal 原生运算检测（STYLE-027）
# 检测加减乘除 + compareTo 比较
BIGDECIMAL_RAW_PATTERNS = [
    # 链式 .add/.subtract/.multiply/.divide + BigDecimal/new 上下文
    re.compile(r'\.\s*(?:add|subtract|multiply|divide)\s*\(\s*(?:new\s+BigDecimal|BigDecimal\.)'),
    # .divide(..., RoundingMode) —— 有 RoundingMode 参数必定是 BigDecimal 除法
    re.compile(r'\.\s*divide\s*\([^)]*RoundingMode'),
    # .compareTo(BigDecimal → BigDecimalUtils.equals/largeThan
    re.compile(r'\.\s*compareTo\s*\(\s*BigDecimal'),
]

# QFilter 字符串运算符检测（P0：编译通过但运行时崩溃）
QFILTER_STRING_OP_PATTERN = re.compile(
    r'new\s+QFilter\s*\([^,]+,\s*"[^"]*"\s*,'
)

SQL_CONCAT_PATTERN = re.compile(
    r'("[^"]*\b(select|insert|update|delete|from|where)\b[^"]*"\s*\+)'
    r'|(\+\s*"[^"]*\b(from|where|and|or|order\s+by|group\s+by)\b[^"]*")',
    re.IGNORECASE,
)
SQL_DIALECT_PATTERN = re.compile(
    r'"[^"]*\b(select|insert|update|delete)\b[^"]*\b(limit|rownum|nvl|isnull|ifnull|top\s+\d+)\b[^"]*"',
    re.IGNORECASE,
)
CHINESE_CONCAT_PATTERN = re.compile(
    r'("[^"]*[\u4e00-\u9fff][^"]*"\s*\+)|(\+\s*"[^"]*[\u4e00-\u9fff][^"]*")'
)
LOOP_UPDATE_VIEW_PATTERN = re.compile(r"\bupdateView\s*\(")
LOOP_DB_PATTERN = re.compile(
    r"\b(BusinessDataServiceHelper|QueryServiceHelper|SaveServiceHelper)\s*\.\s*"
    r"(load|loadSingle|loadSingleFromCache|loadFromCache|query|queryOne|queryDataSet|exists?|save|update)\b"
)
LOOP_DB_HELPER_PATTERN = re.compile(r"\b(BusinessDataServiceHelper|QueryServiceHelper|SaveServiceHelper)\b")
LOOP_DB_METHOD_PATTERN = re.compile(
    r"\.\s*(load|loadSingle|loadSingleFromCache|loadFromCache|query|queryOne|queryDataSet|exists?|save|update)\b"
)
LOOP_REDIS_PATTERN = re.compile(r"\b(RedisTemplate|StringRedisTemplate|Jedis|Redisson|redisTemplate|redisClient)\b")
LOOP_ORM_CREATE_PATTERN = re.compile(r"\bORM\s*\.\s*create\s*\(")
LOOP_DISPATCH_PATTERN = re.compile(r"\bDispatchServiceHelper\s*\.\s*invoke\w*\s*\(")
QUERY_RESULT_DECL_PATTERN = re.compile(
    r"\b(?:DynamicObjectCollection|var)\s+([A-Za-z_]\w*)\s*=\s*QueryServiceHelper\s*\.\s*query\b"
)
QUERY_RESULT_HELPER_DECL_PATTERN = re.compile(
    r"\b(?:DynamicObjectCollection|var)\s+([A-Za-z_]\w*)\s*=\s*QueryServiceHelper\b"
)
QUERY_RESULT_METHOD_PATTERN = re.compile(r"\.\s*query\b")
QUERY_RESULT_GET_ALIAS_PATTERN = re.compile(
    r"\b(?:DynamicObject|var)\s+([A-Za-z_]\w*)\s*=\s*([A-Za-z_]\w*)\s*\.\s*get\s*\("
)
ENHANCED_FOR_PATTERN = re.compile(
    r"\bfor\s*\(\s*(?:final\s+)?(?:DynamicObject|var)\s+([A-Za-z_]\w*)\s*:\s*([A-Za-z_]\w*)\s*\)"
)
SAVE_SERVICE_PATTERN = re.compile(r"\bSaveServiceHelper\s*\.\s*(save|update)\s*\(")


def check(filepath: str, lines: List[str]) -> List[LintIssue]:
    """执行编码偏好检查，返回问题列表。"""
    issues: List[LintIssue] = []
    _, loop_context = analyze_java_context(lines)
    plugin_context = analyze_plugin_context(lines)
    skip_lines = classify_lines(lines)
    query_result_vars: dict[str, int] = {}
    query_result_entity_vars: dict[str, int] = {}
    active_query_loop_vars: dict[str, int] = {}
    single_line_query_loop_vars: dict[str, int] = {}
    brace_depth = 0

    for i, line in enumerate(lines):
        lineno = i + 1
        code_line = code_for_structure(line)
        raw_code_line = strip_line_comment(line)
        prev_code_line = code_for_structure(lines[i - 1]) if i > 0 else ""
        current_depth = brace_depth
        active_query_loop_vars = {
            var_name: depth
            for var_name, depth in active_query_loop_vars.items()
            if current_depth >= depth
        }
        single_line_query_loop_vars = {
            var_name: target_line
            for var_name, target_line in single_line_query_loop_vars.items()
            if lineno <= target_line
        }

        decl_match = QUERY_RESULT_DECL_PATTERN.search(code_line)
        if decl_match:
            query_result_vars.setdefault(decl_match.group(1), lineno)
        elif QUERY_RESULT_METHOD_PATTERN.search(code_line):
            helper_decl_match = QUERY_RESULT_HELPER_DECL_PATTERN.search(prev_code_line)
            if helper_decl_match:
                query_result_vars.setdefault(helper_decl_match.group(1), lineno - 1)

        alias_match = QUERY_RESULT_GET_ALIAS_PATTERN.search(code_line)
        if alias_match and alias_match.group(2) in query_result_vars:
            query_result_entity_vars.setdefault(alias_match.group(1), query_result_vars[alias_match.group(2)])

        for_match = ENHANCED_FOR_PATTERN.search(code_line)
        if for_match and for_match.group(2) in query_result_vars:
            loop_var = for_match.group(1)
            if re.search(rf"\b{re.escape(loop_var)}\s*\.\s*set\s*\(", code_line):
                query_result_entity_vars.setdefault(loop_var, query_result_vars[for_match.group(2)])
            elif "{" in code_line:
                active_query_loop_vars[loop_var] = current_depth + 1
            else:
                single_line_query_loop_vars[loop_var] = lineno + 1

        if skip_lines[i]:
            brace_depth += code_line.count("{") - code_line.count("}")
            continue

        loop_line = loop_context[i] or looks_like_loop_header(line)
        for rule in STYLE_RULES:
            exclude = rule.get("exclude_pattern")
            match_source = raw_code_line if rule["rule_id"] == "STYLE-006" else code_line
            if exclude and re.search(exclude, match_source):
                continue
            if re.search(rule["pattern"], match_source):
                issues.append(LintIssue(
                    file=filepath, line=lineno,
                    severity=rule["severity"],
                    rule_id=rule["rule_id"],
                    message=rule["message"],
                    fix_hint=rule["fix_hint"],
                    source_line=line.strip(),
                ))

        if plugin_context[i] == "op" and re.search(r"\ballFields\s*\(", code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.WARNING,
                rule_id="STYLE-010",
                message="操作插件里除非字段很多，否则不要直接使用 allFields()",
                fix_hint="按实际业务显式准备字段，优先 e.getFieldKeys().add(...) 或 entryFields(...)",
                source_line=line.strip(),
            ))

        if SQL_CONCAT_PATTERN.search(line):
            # 降低误报：移除所有字符串字面量后，检查 + 附近是否有变量/标识符
            # 纯字面量拼接（如 "Please select" + " an option"）不构成 SQL 注入风险
            line_without_strings = re.sub(r'"[^"]*"', '', line)
            has_var_concat = bool(re.search(r'\w\s*\+|\+\s*\w', line_without_strings))
            if has_var_concat:
                issues.append(LintIssue(
                    file=filepath, line=lineno,
                    severity=Severity.ERROR,
                    rule_id="STYLE-011",
                    message="SQL/KSQL 传参不要通过字符串拼接构造",
                    fix_hint="改为参数化查询或平台查询构造，避免手拼 SQL 条件",
                    source_line=line.strip(),
                ))

        if SQL_DIALECT_PATTERN.search(line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-012",
                message="检测到可能的数据库方言 SQL 关键字，跨库兼容性差",
                fix_hint="改为 KSQL 或平台查询接口，避免 limit/rownum/nvl/isnull/top 等方言写法",
                source_line=line.strip(),
            ))

        # STYLE-024: QFilter 使用字符串运算符代替 QCP 枚举（编译通过但运行时崩溃）
        if QFILTER_STRING_OP_PATTERN.search(raw_code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-024",
                message="QFilter 第二个参数必须使用 QCP 枚举，不能用字符串（编译通过但运行时崩溃）",
                fix_hint='\u5c06 new QFilter(field, "=", value) \u6539\u4e3a new QFilter(field, QCP.equals, value)\uff1b\u5e38\u7528\u679a\u4e3e: QCP.equals / not_equals / large_equals / less_equals / like / in',
                source_line=line.strip(),
            ))
        
        # STYLE-027: BigDecimal 原生运算，应优先使用 BigDecimalUtils
        if 'BigDecimalUtils' not in code_line:
            for bd_pat in BIGDECIMAL_RAW_PATTERNS:
                if bd_pat.search(code_line):
                    issues.append(LintIssue(
                        file=filepath, line=lineno,
                        severity=Severity.WARNING,
                        rule_id="STYLE-027",
                        message="BigDecimal 原生运算应优先使用 BigDecimalUtils 工具方法",
                        fix_hint="使用 BigDecimalUtils.valueOf() / add() / subtract() / multiply() / divide() / largeThanZero() / nullToZero() 等",
                        source_line=line.strip(),
                    ))
                    break

        # STYLE-026: 主键/id 用 == null / != null / == 0L 等方式判空
        if not any(kw in code_line for kw in ('isEmptyPk', 'isNotEmptyPk')):
            # 前两个 pattern 检测变量名，用 code_line；第三个 pattern 检测 .get("id")，用 raw_code_line（保留字符串字面量）
            pk_sources = [code_line, code_line, raw_code_line]
            for pk_pat, src in zip(PK_NULL_CHECK_PATTERNS, pk_sources):
                if pk_pat.search(src):
                    issues.append(LintIssue(
                        file=filepath, line=lineno,
                        severity=Severity.WARNING,
                        rule_id="STYLE-026",
                        message="主键/id 判空不应直接用 == null / != null / == 0L，苍穹主键默认值为 0L",
                        fix_hint="使用 EntityUtils.isEmptyPk(pk) 或 EntityUtils.isNotEmptyPk(pk)，兼容 null 和 0L",
                        source_line=line.strip(),
                    ))
                    break

        if "ResManager.loadKDString" not in line and "String.format" not in line and CHINESE_CONCAT_PATTERN.search(line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.INFO,
                rule_id="STYLE-013",
                message="中文提示语存在拼接，后续多语言翻译时语序容易失真",
                fix_hint="使用完整句模板 + String.format(ResManager.loadKDString(...), ...)",
                source_line=line.strip(),
            ))

        if loop_line and LOOP_UPDATE_VIEW_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-014",
                message="禁止在循环中调用 updateView()，界面刷新成本高",
                fix_hint="循环结束后统一执行局部刷新",
                source_line=line.strip(),
            ))

        loop_db_hit = LOOP_DB_PATTERN.search(code_line) or (
            LOOP_DB_METHOD_PATTERN.search(code_line) and LOOP_DB_HELPER_PATTERN.search(prev_code_line)
        )
        if loop_line and loop_db_hit:
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-015",
                message="在循环中访问数据库（包括 BusinessDataServiceHelper.loadSingle(...) 等），存在明显的 N+1 查询风险",
                fix_hint="先看 skills/ok-cosmic/assets/snippets/query/BatchQuerySample.java，按“分组 key -> 批量查询 -> 本地映射”改写，避免循环里逐条查库",
                source_line=line.strip(),
            ))

        if loop_line and LOOP_REDIS_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-016",
                message="禁止在循环中频繁访问 Redis，容易造成缓存压力",
                fix_hint="减少访问次数，优先批量读取或增加本地缓存",
                source_line=line.strip(),
            ))

        if loop_line and LOOP_ORM_CREATE_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-022",
                message="禁止在循环中频繁调用 ORM.create()，容易造成性能问题",
                fix_hint="先批量组织数据，按批次处理",
                source_line=line.strip(),
            ))

        if loop_line and LOOP_DISPATCH_PATTERN.search(code_line):
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.ERROR,
                rule_id="STYLE-023",
                message="禁止在循环中调用 DispatchServiceHelper.invoke*()，远程调用放进循环容易放大时延和失败面",
                fix_hint="合并调用、批量调用或先聚合参数",
                source_line=line.strip(),
            ))

        query_update_hit = False
        query_update_var = ""
        if SAVE_SERVICE_PATTERN.search(code_line):
            for var_name in list(query_result_vars) + list(query_result_entity_vars) + list(active_query_loop_vars) + list(single_line_query_loop_vars):
                if re.search(rf"\b{re.escape(var_name)}\b", code_line):
                    query_update_hit = True
                    query_update_var = var_name
                    break

        if not query_update_hit:
            for var_name in list(query_result_entity_vars) + list(active_query_loop_vars) + list(single_line_query_loop_vars):
                if re.search(rf"\b{re.escape(var_name)}\s*\.\s*set\s*\(", code_line):
                    query_update_hit = True
                    query_update_var = var_name
                    break

        if not query_update_hit:
            for var_name in query_result_vars:
                if re.search(rf"\b{re.escape(var_name)}\s*\.\s*get\s*\([^)]*\)\s*\.\s*set\s*\(", code_line):
                    query_update_hit = True
                    query_update_var = var_name
                    break

        if query_update_hit:
            issues.append(LintIssue(
                file=filepath, line=lineno,
                severity=Severity.WARNING,
                rule_id="STYLE-017",
                message="QueryServiceHelper.query(...) 返回的是扁平查询结果，不应直接当成可更新实体使用",
                fix_hint="先看 skills/ok-cosmic/assets/snippets/query/BatchQuerySample.java 的“query -> id -> load -> update”桥接样例，先查 id，再 load 实体包后更新保存",
                source_line=line.strip(),
            ))

        brace_depth += code_line.count("{") - code_line.count("}")

    return issues