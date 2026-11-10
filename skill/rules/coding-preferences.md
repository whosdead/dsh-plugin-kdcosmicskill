# 编码偏好、默认写法与治理方向

> 本文件为 `style_check.py`（STYLE-\*）、`resource_check.py`（RESOURCE-\*）、`verify_check.py`（VERIFY-\*）的**规则来源**。
> 幻觉禁忌和场景错配见 [anti-patterns.md](anti-patterns.md)；当前自动 lint 规则前缀为 SCENE-\*。

本文件承载 `ok-cosmic` 的 **B 层** 和 **C 层** 规则：

- **B 层：推荐项 / 新代码默认写法**
  - 新生成代码默认尽量遵守。
  - 历史存量代码若暂未满足，不因此直接判为错误。
  - **"新代码"与"历史修补"的判断标准**：如果当前任务是**新增文件或新增方法**，按新代码标准遵守 B 层；如果是**在已有方法内追加几行逻辑**，优先保持与周围代码风格一致，B 层不强制。不确定时问用户。
- **C 层：目标态治理 / 渐进优化**
  - 适合模板升级、专项治理、批量重构。
  - 默认不作为一次性交付阻断项。

真正的硬红线请看 [constraints.md](constraints.md)。

## B1. 新代码默认写法

### 插件基类与封装优先级

- **[B1.1]** **插件基类（封装层）**：新代码优先选择 `kd.cd.common.plugin` 包下的扩展基类：
  `AbstractBillPlugInExt`、`AbstractFormPluginExt`、`AbstractListPluginExt`、`AbstractOperationServicePlugInExt`、`AbstractValidatorExt`
- **[B1.2]** **原生插件基类**：BOTP 转换（`AbstractConvertPlugIn`）和反写（`AbstractWriteBackPlugIn`）没有 `*Ext` 封装版本，直接使用 `kd.bos.entity.botp.plugin` 包下的原生基类。
- **[B1.3]** 如果必须给出原生写法：
  先说明为什么仓库封装不适用，再给最小可行实现，不要把原生样板扩散成默认风格。
- **[B1.4]** 历史项目中大量原生基类写法是可预期的；评估现有代码时，不要因为“不是 Ext 基类”就直接判错。

### 工具类与实现风格

- **[B1.5]** 需要错误汇总时，优先使用 `OpUtils.addErrorMessage(...)`、`OpUtils.getCompleteFailMsg(...)`、`PushResult.failThenThrow()`
- **[B1.6]** 字符串判空优先使用 `CharSequenceUtils`；当其 API 未覆盖时（如 `trimToEmpty`、`substringBefore` 等），fallback 为 `org.apache.commons.lang3.StringUtils`；不要在**新示例代码**里混用 `ObjectUtils` 做字符串空白判断。
- **[B1.7]** 集合判空优先使用 `CollectionUtils`（`kd.cd.core.util`）；当其 API 未覆盖时（如 `partition`、`union` 等），fallback 为 `org.apache.commons.collections4.CollectionUtils`；不要在**新示例代码**里手写 `!= null && !isEmpty()`。
- **[B1.8]** 对异常优先使用日志框架记录；`*Ext` 基类里直接使用内置的 `public final Log log`，非插件类可直接使用 `kd.bos.logging.LogFactory`。
- **[B1.9]** 未经用户明确要求，不要偏离 `assets/FormPluginTemplate.java` 的代码风格；确需偏离时，在答案里说明原因。
- **[B1.10]** 若必须新增模板之外的 `import`，需仅新增最小集合，并说明新增原因。

### 业务封装优先级

- **[B1.11]** 对单据状态流转，失败即抛异常的场景优先使用 `OpUtils.executeOperateOrThrow`；需要自行解析操作结果的场景（如 MQ 消费、后台任务、失败后回填错误信息）可直接使用 `OperationServiceHelper.executeOperate` 并检查 `OperationResult`。
- **[B1.12]** 只有在需要连续调用多个操作时，才优先使用 `OperateChain`；单次 `save`、`submit`、`audit` 优先使用 `OpUtils`。
- **[B1.13]** 对单据转换，优先使用 `BotpUtils`；不要在**新代码**里手拼 `PushArgs`/`DrawArgs` 等重复样板。
- **[B1.14]** 处理基础资料（BaseData）相关业务动作时，先查 `BaseDataServiceHelper` 是否已有现成方法。
- **[B1.15]** 在操作插件里，除非需要准备的字段非常多，否则不要使用 `allFields()`；优先按实际场景显式准备字段。
- **[B1.16]** 对查询，优先使用 `QueryServiceHelper` + `AlgoUtils`；不要先写裸 SQL 或循环查库。
- **[B1.17]** `QueryServiceHelper.query(...)` 查出来的是扁平结果集，默认只用于读取、分组、聚合；不要直接 `set(...)` 后 `SaveServiceHelper.update/save(...)`。
- **[B1.18]** 查询基础资料时，优先使用 `BusinessDataServiceHelper.loadFromCache(...)`；不要对基础资料反复用普通查询接口查库。
- **[B1.19]** 对动态对象取值，优先使用 `DynamicObjectUtils`；不要直接深链式 `get("a.b.c")`。
- **[B1.20]** 对附件处理，优先使用 `AttachmentUtils` 和 uploader；不要直接散落调用 `AttachmentServiceHelper`。
- **[B1.21]** `references/base/*` 只用于补齐原生知识、事件签名和缺失能力；不要因为能写原生 API 就绕开现有封装。

### 查询、资源与提示语

- **[B1.22]** 判断"是否存在"时，**新代码优先**使用 `QueryServiceHelper.exists(...)`，不要使用 `queryOne(...) != null` 这种写法。
- **[B1.23]** 查询时只取实际需要的字段；除加载完整单据/基础资料外，不要默认 `allFields()` 或无选择地查整包数据。
- **[B1.24]** `DataSet` 使用后要在最靠近创建/转换的位置关闭；优先用清晰的生命周期包裹，避免跨方法悬挂。
- **[B1.25]** 需要参数化查询时，优先使用平台查询构造或 KSQL 参数，不要手拼 where 条件字符串。
- **[B1.26]** 面向用户的提示语要体现业务语义和下一步动作，不要只抛底层异常文本。
- **[B1.27]** 报表或大结果集处理时，优先复用已查询数据，并用 `algo` 做分组、去重、统计；不要把大聚合逻辑堆进 Java 循环。
- **[B1.28]** 大数据量查询（如批量同步、报表取数、后台任务处理）时，优先使用分页迭代（`setLimit` + 循环偏移）或流式 `DataSet` 处理；不要一次性全量加载到内存。

## B2. 元数据与脚本配合细则

- **[B2.1]** **元数据查询约束**：调用 `cosmic-form-metadata.py` 时，脚本会无条件展示所有字段；如果概览模式未显示所需字段，请主动在 `--fuzzy` 中增加搜索词进行精准匹配。
- **[B2.2]** 如果脚本未查到字段或表单元数据，必须提醒用户确认其提供的表单名称/标识是否正确，再继续处理。
- **[B2.3]** **字段元数据强制验证**：只要要生成/修改代码，且已知目标单据/表单，凡涉及字段（无论用户给中文名还是英文标识），都必须通过 `cosmic-form-metadata.py` 确认字段存在、字段类型、所属实体和特殊取值规则后再写入代码。
- **[B2.4]** **批量合并**：需要确认多个字段时，必须合并为一次 `--fuzzy` 调用（如 `--fuzzy qty price amount material org`），严禁逐个字段发起多次查询。
- **[B2.5]** **自动详情**：当 `--fuzzy` 传入 ≥3 个关键词时，脚本自动升级为详情模式（含枚举/refType），无需手动追加 `--show-detail`。
- **[B2.6]** **常规字段对齐**：仅确认 1-2 个字段标识时，不带 `--show-detail` 和 `--sql`。
- **[B2.7]** **深度实现（含枚举值）**：当需要编写枚举判断、手动赋值或基础资料关联查询前，带上 `--show-detail`。**枚举/下拉选项值的具体取值属于 A 层硬约束 [A1.8]，必须查 Ext 列确认后才能写入代码，详见 [constraints.md](constraints.md)。**
- **[B2.8]** **生成 SQL**：除非用户强依赖编写原生 JDBC/SQL 查询，否则默认不要使用 `--sql`。

## C1. 目标态治理项

以下内容仍然推荐，但默认作为长期治理方向，而不是当前一次性交付阻断项：

- **[C1.1]** **验证来源注释**：建议在关键 `@Override` 方法或 BOS SDK 关键调用附近补充“验证来源”注释，作为事实留痕。
- **[C1.2]** **异常体系统一**：建议逐步把历史项目中的 `RuntimeException`、`IllegalArgumentException` 等，收敛到更明确的业务异常或带 `cause` 的统一异常体系。
- **[C1.3]** **日志治理**：建议逐步清理历史代码中的 `printStackTrace()`，统一为 `logger.error("问题描述", e)`。
- **[C1.4]** **存在性判断治理**：建议在后续重构中逐步把 `queryOne(...) != null` 迁移为更明确的存在性判断或领域查询封装。
- **[C1.5]** **原生基类迁移**：对稳定运行的历史插件，不要求为了“改成 Ext 基类”而进行无收益重构；只有在功能演进、模板升级、公共封装复用收益明确时再迁移。

## C2. 评估历史代码时的默认口径

- **[C2.1]** 发现历史代码使用原生基类、`StringUtils`、`queryOne(...)`、`OperationServiceHelper.executeOperate(...)` 等写法时，先判断其是否触犯 [constraints.md](constraints.md) 中的硬红线。
- **[C2.2]** 若没有触犯 A 层红线，则应描述为：
  - “当前可运行的历史写法”
  - “建议新代码采用的替代写法”
  - “是否值得在本次需求中顺手收敛”
- **[C2.3]** 不要把所有历史写法一律描述成"错误"；要区分 **硬错误**、**推荐替换**、**后续治理** 三种层级。

## 自动检测规则速查（STYLE / RESOURCE）

> 以下规则由 `style_check.py` 和 `resource_check.py` 自动扫描。
> A 层规则 ID 定义在 [a-layer-rules.json](a-layer-rules.json)（单一可信源），报 ERROR 必须修复；B 层报 WARNING，新代码优先修复。

### STYLE-\*: 编码风格

| 规则 ID | 检测模式 | 正确做法 | 层级 |
|---|---|---|---|
| STYLE-001 | `StringUtils.isBlank/isEmpty/equals` 等判空方法 | `CharSequenceUtils`（仅 `isBlank/isNotBlank/isEmpty/isNotEmpty/equals`；`trimToEmpty` 等未覆盖方法 fallback `org.apache.commons.lang3.StringUtils`） | B |
| STYLE-002 | `!= null && !collection.isEmpty()` | `CollectionUtils.isNotEmpty(...)`；`partition` 等未覆盖方法 fallback `org.apache.commons.collections4.CollectionUtils` | B |
| STYLE-003 | 散落调用 `OperationServiceHelper.save/submit/audit` | `OpUtils` 或 `OperateChain` | B |
| STYLE-004 | `new PushArgs(...)` | `BotpUtils` | B |
| STYLE-005 | `new DrawArgs(...)` | `BotpUtils` | B |
| STYLE-006 | `dynamicObject.get("a.b.c")` 深链取值 | `DynamicObjectUtils` 安全取值 | B |
| STYLE-007 | 直接调用 `AttachmentServiceHelper` | `AttachmentUtils` + uploader | B |
| STYLE-008 | `queryOne(...) != null` 判存在 | `QueryServiceHelper.exists(...)` | B |
| STYLE-009 | `printStackTrace()` | `logger.error("描述", e)` | **A** |
| STYLE-010 | 操作插件使用 `allFields()` | 显式按场景准备字段 | B |
| STYLE-011 | SQL/KSQL 字符串拼接 | 参数化查询 | **A** |
| STYLE-012 | 数据库方言 SQL（`limit`/`rownum`/`nvl`/`isnull`） | KSQL 或平台查询接口 | **A** |
| STYLE-013 | 中文词条拼接（`"关于" + name + ...`） | 完整句模板 + `String.format(ResManager.loadKDString(...))` | B |
| STYLE-014 | 循环中 `view.updateView(...)` | 循环结束后统一局部刷新 | **A** |
| STYLE-015 | 循环中访问数据库（N+1 查询/写入），包括 `for`/`while` 及 `stream().forEach`/`map` 等 lambda 迭代；覆盖 `BusinessDataServiceHelper`/`QueryServiceHelper`/`SaveServiceHelper` | 分组 key → 批量加载 → 本地映射 | **A** |
| STYLE-016 | 循环中访问 Redis | 批量读取或本地缓存 | **A** |
| STYLE-017 | `query(...)` 结果直接 `set(...)` / `save` | 先 `query` 出 id，再 `load` 实体包后更新 | B |
| STYLE-018 | `throw new RuntimeException(...)` 等 | `KDBizException`，保留原始 `cause` | **A** |
| STYLE-019 | `new Thread(...)` | `kd.bos.threads.ThreadPools` | B |
| STYLE-020 | `Executors.*` | `ThreadPools.new*` / `ThreadPools.executeOnce*` | B |
| STYLE-021 | `SerializationUtils.toJsonString(args)` 打印大对象 | 只按需提取关键字段打印 | B |
| STYLE-022 | 循环中 `ORM.create(...)` | 批量组织数据，按批次处理 | **A** |
| STYLE-023 | 循环中 `DispatchServiceHelper.invoke*` | 合并/批量调用或先聚合参数 | **A** |
| STYLE-024 | `new QFilter(field, "=", value)` 第二个参数用字符串 | `new QFilter(field, QCP.equals, value)`，使用 QCP 枚举 | **A** |
| STYLE-025 | `BusinessDataServiceHelper.load(...)` 未指定查询字段（配合 `EntityUtils.getMainEntityType` / `BusinessDataServiceHelper.newDynamicObject` / `EntityMetadataCache.getDataEntityType` 全量加载） | 使用 `BusinessDataServiceHelper.load(entityName, selectFields, filters)` 指定所需字段 | B |
| STYLE-026 | 主键/id 用 `== null` / `!= null` / `== 0L` / `<= 0` 判空 | `EntityUtils.isEmptyPk(pk)` / `isNotEmptyPk(pk)`（兼容 null 和 0L） | B |
| STYLE-027 | BigDecimal 原生加减乘除与比较（`.add()` / `.subtract()` / `.multiply()` / `.divide()` / `.compareTo()`） | `BigDecimalUtils.add()` / `subtract()` / `multiply()` / `divide()` / `equals()` / `largeThan()` | B |

### RESOURCE-\*: 资源管理

| 规则 ID | 检测模式 | 正确做法 | 层级 |
|---|---|---|---|
| RESOURCE-001 | 插件成员变量持有 `DataSet`/`InputStream` 等 | 方法内短持有，用完关闭 | B |
| RESOURCE-002 | 非 `final` 的 `static` 状态变量 | `PageCache` 或实例变量 | B |
| RESOURCE-003 | `ResManager.loadKDString(...)` 固化为 `static final` | 改为方法内实时获取 | B |
| RESOURCE-004 | `DataSet` 声明但未 `close()` | try-with-resources 或就近关闭 | **A** |

RESOURCE-004 例外（不报错）：
- **报表插件等场景中 `return ds;`** — DataSet 作为方法返回值，由调用方/框架负责关闭。
- **DataSet 参与计算或合并** — `DataSet result = ds1.union(ds2)` 中 `ds1`、`ds2` 被消费，只需 close 最终结果 `result`。
