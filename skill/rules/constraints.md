# 硬约束与交付红线

> 本文件为**流程与原则守卫**（guidance-only），不直接对应 lint 脚本。
> 可自动检测的规则见：[anti-patterns.md](anti-patterns.md)（SCENE）、[coding-preferences.md](coding-preferences.md)（STYLE/RESOURCE/VERIFY）。

本文件只承载 `ok-cosmic` 的 **A 层规则**：会直接影响事实准确性、插件上下文、事件阶段和交付正确性的硬约束。

- 命中本文件中的问题，默认按 **阻断交付** 处理。
- 不属于本文件的"推荐写法"与"目标态治理"，请分别查看：
  - [coding-preferences.md](coding-preferences.md)
  - [post-check.md](post-check.md)

## A1. 事实校验与模板约束

- **[A1.1] 拒绝幻觉**：严禁凭记忆或猜测生成任何 API 签名、事件方法名或单据字段标识。
- **[A1.2] 模板强制先行**：AI 在生成任何苍穹插件代码前，必须先读取对应的 `assets/*.java` 模板文件，并严格遵守模板中的方法签名。
- **[A1.3] 签名强制校验**：在编写任何 `@Override` 方法或调用 BOS SDK 关键方法前，应通过 `cosmic-api-knowledge.py` 的 `detail` 子命令验证该方法确实存在且签名准确（已在 `rules/cheat-sheet.md` 中列出的 API 可直接使用，无需脚本验证）。`API-*` 事后 lint 规则已废弃，若事前校验被跳过，只能依赖编译、脚本查询或人工确认补救。
- **[A1.4] 入口对齐协议**：
   - **UI 控制 (只读/隐藏/弹窗)**：必须通过 `IFormView`（由 `this.getView()` 获取）。
   - **数据操作 (取值/赋值/结构)**：必须通过 `IDataModel`（由 `this.getModel()` 获取）。
- **[A1.5] 验证优先级**：在使用原生 SDK 前，必须先查本地 `.md`，再通过脚本验证。
- **[A1.6] 继承溯源**：利用脚本返回的继承树确认方法是否在基类中。
- **[A1.7] 代码风格基线**：新生成代码必须以对应模板为主，不得脱离模板随意改写方法签名、生命周期方法或基础骨架。
- **[A1.8] 枚举/下拉选项值禁猜**：凡是需要使用 `ComboField`、下拉列表等字段的**具体选项值**（无论用于条件判断、状态赋值、QFilter 构造、数据反写还是任何其他场景），**严禁凭空编造或凭经验猜测**。必须先通过 `cosmic-form-metadata.py` 查询该字段，读取返回结果中「附加信息 (Ext)」列的真实枚举映射（如 `A:已审核, B:暂存`），确认后才能将对应的值写入代码。
- **[A1.9] 字段生成代码强制验证**：只要要生成或修改代码，且已知目标单据/表单（`formId` 或 `billName`），凡涉及字段（无论用户给的是中文名还是英文标识），都必须先通过 `cosmic-form-metadata.py` 验证字段是否存在、字段类型、所属实体（表头/表体/子表体/容器）和特殊取值规则（如 `BasedataPropField` 的 `refType`、`LargeTextField` 的 `{fieldKey}_tag`、Combo/下拉字段 Ext 映射）。未验证前不得直接把用户给出的字段写入代码。

## A2. 平台开发红线

> 以下条目的平台背景详见 [platform-baseline.md](platform-baseline.md)，对应的自动化 lint 规则主要见 [anti-patterns.md](anti-patterns.md)。

- **[A2.1] 绑定阶段禁改数据**：禁止在 `beforeBindData`、`afterBindData` 中修改数据对象。（→ `SCENE-008`）
- **[A2.2] initialize 边界**：禁止在 `initialize()` 中注册控件事件或做 UI 逻辑。（→ `SCENE-006`, `SCENE-007`）
- **[A2.3] 元数据访问边界**：业务代码禁止直接访问平台元数据表 `t_meta_xxx`。（→ `SCENE-009`）
- **[A2.4] 标准产品保护**：禁止继承标准产品表单/单据插件；禁止禁用原厂插件。
- **[A2.5] 引用对象类型一致**：创建引用对象时必须使用属性复杂类型或当前实体正确类型。（→ `SCENE-010`）
- **[A2.6] 元数据单例保护**：从缓存获取到的实体元数据必须先 `clone` 再修改。
- **[A2.7] 设计器/PDM 一致性**：业务对象、字段、默认值、可空性等定义以 PDM 和设计器为准。

## A3. 运行时与运维红线

- **[A3.1] 数据访问红线**：查询必须参数化；`DataSet` 使用完必须关闭；禁止把 SQL/KSQL 条件当字符串随意拼接。（→ `STYLE-011`, `STYLE-012`, `RESOURCE-004`）
- **[A3.2] 性能红线**：禁止在循环中访问数据库、Redis、`view.updateView()`、`ORM.create()`、`DispatchServiceHelper.invoke*()`。（→ `STYLE-014`, `STYLE-015`, `STYLE-016`, `STYLE-022`, `STYLE-023`）
- **[A3.3] 日志红线**：统一使用 `kd.bos.logging.Log`；禁止 `printStackTrace()`。（→ `STYLE-009`）
- **[A3.4] 异常红线**：业务流程中的业务异常不得直接用 `RuntimeException` 伪装。（→ `STYLE-018`）

## A4. 与 B/C 层的边界

- **不属于 A 层**：
  - "优先使用 `Ext` 基类而不是原生基类"
  - "优先使用 `OpUtils` / `BotpUtils`"
  - "优先使用 `QueryServiceHelper.exists(...)` 替代 `queryOne(...)`"
  - "给每个 `@Override` 都补验证来源注释"
- 上述内容仍然是有价值的，但默认分别归入：
  - **B 层推荐项**：新代码默认遵守
  - **C 层治理项**：适合逐步清理历史项目，不作为当前一次性交付的硬阻断

## A5. 当前默认交付判断

- 命中 `SCENE-*` 中的硬错误，默认必须修复。
- 命中本文件列出的平台红线或运行时红线，默认必须修复。
- 若用户明确要求"按历史代码风格补丁式修改"，可保留 **B/C 层** 历史写法，但仍不得突破 **A 层** 红线。
