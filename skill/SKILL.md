---
name: ok-cosmic
description: "金蝶云苍穹开发主 Skill（DSH 插件版），优先复用 kd-cd-cosmic-commons 封装。适用于插件开发、单据/列表/表单逻辑、操作服务、BOTP 转换、后台视图打开、附件处理、DynamicObject 与元数据处理、弹性域解析及 OpenAPI 集成。所有配置由 DSH 插件设置驱动。"
---

# 苍穹开发（DSH 插件版）

默认按“封装优先，原生兜底”工作，避免在仓库已封装的场景里退回 BOS 原生低层 API。

## 路径约定

- `<SKILL_ROOT>` = 本 `SKILL.md` 所在目录；`references/`、`rules/`、`assets/` 均相对它解析。
- 所有可变配置（知识库、统一路由、扩展代码库、超时、缓存、限额、校验策略）都在 **DSH 设置 → 苍穹开发** 中维护；保存后下一次工具调用立即生效。**不要创建或修改 `ok-cosmic.json`，也不要直接执行 `scripts/*.py`**，统一走 `cosmic_*` 工具。

## Step 0. 配置预检（先于一切）

1. 第一个动作调用 `cosmic_config_check`（可加 `strict` / `json`）。
2. 返回 `ERROR`：停止生成代码，提示用户在 DSH 设置中补齐缺失项。
3. 仅有 `WARNING`：先告知用户哪些在线能力不可用，再决定是否继续。后续限制：

| WARNING 类型 | 限制 |
|---|---|
| “API 不可达”或“在线服务不可用” | **禁止调用** `cosmic_query_basedata`；`cosmic_get_meta_fields` 仅允许使用本地缓存，**禁止 `refresh`** |
| “知识库不存在” | 知识库查询不可用，改为仅依赖 `rules/cheat-sheet.md` 和模板中的方法签名 |

## 自然语言意图路由

先把用户原话翻译成“**时机 + 载体 + 能力**”，再进入决策矩阵。详细映射、例句和误判修正见 `rules/intent-routing.md`。

## 决策矩阵（精简索引）

先命中 **1 行主场景**（通常是插件行），再按需补 **0-1 行能力行**。完整矩阵与未命中兜底见 `rules/decision-matrix.md`。命中后只读对应锚点附近 1-3 行，不要整表通读。

### 插件类

| 需求关键词 | 场景 | 详见 |
|---|---|---|
| 表单 UI / 字段联动 / 控件交互 | 表单插件 | `#form-plugin` |
| 单据 UI / 审核提交按钮 | 单据插件 | `#bill-plugin` |
| 列表 / 多选操作 / 批量 | 列表插件 | `#list-plugin` |
| 左树右表（单据 / 基础资料） | 树列表插件 | `#tree-plugins` |
| 审核 / 保存 / 状态流转 / 校验 / 回滚 | 操作插件 | `#op-plugin` |
| 下推 / 选单 / 来源追踪 / 转换 | 转换插件 | `#convert-plugin` |
| 明确指定反写插件 / BOTP 回写阶段 | 反写插件 | `#writeback-plugin` |
| 报表 / 数据分析 | 报表插件 | `#report-form-plugin` |
| 报表取数 / 动态列 / 自定义数据源 | 报表取数插件 | `#report-data-plugin` |
| 打印 / 套打 | 打印插件 | `#print-plugin` |
| OpenAPI / 外部集成 | OpenAPI 控制器 | `#openapi-plugin` |
| 后台任务 / 定时 / 调度作业 | 后台任务 | `#task-plugin` |
| 工作流 / 审批流 | 工作流插件 | `#workflow-plugin` |
| 导入 / 批量导入 | 导入插件 | `#import-plugin` |

### 能力类

| 需求关键词 | 能力 | 详见 |
|---|---|---|
| 后台打开表单 / 列表 / 新页签跳转 | 视图打开与跳转 | `#view-handler` |
| 表单控件 / UI 消息 / 元数据读取 | 界面工具 | `#form-utils` |
| 数据加载 / 保存 / 查询 / ORM / DataSet | 查询与存取 | `#query` |
| 基础资料 / 管控策略 / 分配 / 个性化 | 基础资料 | `#basedata` |
| DynamicObject / 安全取值 / 序列化 | 数据包操作 | `#dynamic-object` |
| 实体元数据 / 字段路径 / DBRoute | 元数据与结构 | `#entity-metadata` |
| 弹性域字段 / 值解析 | 弹性域 | `#flex-prop` |
| 消息 / 邮件 / 短信 / 企业微信 | 消息通知 | `#message` |
| 附件上传 / 下载 / 复制 / 面板绑定 | 附件与文件 | `#attachment` |
| 组织 / 权限 / 上下文 | 上下文判定 | `#request-context` |
| 跨线程身份 / 上下文恢复 | 并发上下文恢复 | `#thread-context` |
| 业务拓展点 / SDK 扩展接口 / Java 示例 | 业务拓展点查询 | `#business-extpoints` |

仍未命中时，按 `rules/decision-matrix.md#fallback` 处理；若设置中配置了扩展代码库，再进入下方“扩展代码库探索”。

## 开发前规范预检（涉及平台对象时必做）

先读 `rules/platform-baseline.md`：新增/修改对象、表、字段、索引、默认值、主键前确认对象编码、`Key`、PDM 命名和删除/隐藏策略；扩展场景只做垂直扩展，不改源字段 `Key`、必录属性和基础资料类型；微服务先确认工厂前缀、接口命名和应用归属；报表/大数据量先设计字段裁剪、批量取数、子单据体拆分和 `algo` 聚合。

## 开发决策路径（完成 Step 0 后执行）

> **并行优先**：标注 **‖** 的操作互不依赖，应在同一轮同时发起。

1. 判断插件类型或能力类别（查上方矩阵）。有交互界面 → 区分“表单事件”和“控件事件”；控件事件需 `implements XxxListener` + `registerListener`。无交互界面（操作、转换、反写、任务）→ 按生命周期选正确事件，不套用 UI 插件写法。
2. **‖ 并行加载**参考与模板：读对应 `references/*.md` 的 TL;DR + `assets/*.java` 骨架；同时在用户项目中搜索相似插件，保持代码风格一致。事件先后顺序不清时先读 `references/adv/event-lifecycle.md`。
3. **‖ 并行验证**字段与 API 签名：
   - `rules/cheat-sheet.md` 已列出的 API → 直接使用，无需查询。
   - 字段 / `entityId` / 枚举值 → `cosmic_get_meta_fields`。只要要生成代码且已知目标单据/表单，凡涉及字段（中文名或英文标识）都必须验证字段存在性、类型、所属实体和特殊取值规则；多个字段合并到同一次调用。
   - SDK 类名/方法签名不在 cheat-sheet → `cosmic_search_classes` / `cosmic_search_methods` / `cosmic_get_class_detail`。
   - `cosmic_query_basedata` 必须等 `entityId` 确认后才能调用，**不可并行**。
4. 只有“插件类型 + 事件方法 + 字段/签名”都确认后，才开始生成代码。
5. 编写代码时遵守最小干预：插件只做当前事件该做的事；能用封装就不退回原生。
6. **代码生成后必须执行 `cosmic_post_check`**，详见下方。

## 写代码前的最小确认卡

| # | 确认项 | 来源 |
|---|---|---|
| 1 | 插件类型 | 决策矩阵 |
| 2 | 目标事件方法 | 模板 / references |
| 3 | 使用模板 | `assets/*.java` |
| 4 | 已验证字段 / refType / entityId | `cosmic_get_meta_fields` |
| 5 | 已确认枚举/下拉选项值（有使用时） | `cosmic_get_meta_fields` 的 `showDetail` |
| 6 | 已验证类/方法签名 | cheat-sheet / `cosmic_get_class_detail` / 业务拓展点 `full` 示例 |

任一为空则继续查证，不要猜。

## 工具路由

| 场景 | 工具 | 关键参数 |
|---|---|---|
| Step 0 预检 | `cosmic_config_check` | `strict` / `json` |
| SDK 类名、继承链 | `cosmic_search_classes` | `query`、`classPrefix`、`kinds`、`pageSize` |
| 方法名跨类搜索 | `cosmic_search_methods` | `query`、`classPrefix`、`kinds` |
| 类详情与方法签名 | `cosmic_get_class_detail` | `className`（逗号分隔批量）、`method`、`compact` |
| 字段 / 枚举 / refType / 操作 / SQL | `cosmic_get_meta_fields` | `formIdOrName`（逗号分隔批量）、`fuzzy`、`types`、`showDetail`、`sql`、`refresh` |
| 基础资料编码/名称/完整数据 | `cosmic_query_basedata` | `entityId`（必须先确认）、`numberOrName`、`full` |
| 业务拓展点候选 | `cosmic_query_extpoints` | `keyword`、`full` |
| 生成或修改 Java 后校验 | `cosmic_post_check` | `path`、`fixHint`、`strict`、`minSeverity` |

## 跨工具硬约束

- **字段生成代码强制验证**：只要要生成/修改代码，且已知目标单据/表单，凡涉及字段都必须先用 `cosmic_get_meta_fields` 验证字段是否存在、类型、所属实体和特殊取值规则（如 `BasedataPropField` 的 `refType`、`LargeTextField` 的 `{fieldKey}_tag`、Combo/下拉字段的 Ext 映射）。目标不明确时先确认目标，不要把用户给的字段直接写进代码。
- **枚举/下拉选项值禁猜 [A1.8]**：使用 ComboField / 下拉字段的具体选项值前，必须先查 `showDetail` 的 Ext 列确认真实映射，详见 `rules/constraints.md` A1.8。
- **entityId 来源**：只允许来自“用户明确给出的准确英文标识”或元数据工具确认出的真实标识；来源不明时先查 `cosmic_get_meta_fields`，不得直接查基础资料。
- 查询超时或远端失败时，只能基于当前会话已确认的字段、`refType`、`entityId`、接口返回继续；若本次查询结果是继续前提，则停止并报告。
- 只有在用户已修正配置/环境，或本次明确修改了查询条件后，才允许重试同类查询；不要原样重复执行。
- 元数据批量查询：`formIdOrName` 支持逗号分隔多个目标，跨单据场景优先批量查询，减少调用次数。

## 扩展代码库探索（Fallback）

仅当决策矩阵未命中、`rules/cheat-sheet.md` 和 `assets/snippets/` 均无覆盖，且当前项目中没有可满足需求的实现时触发。在设置中的 `extensionRepos` 路径下搜索 `.java`，多个路径并行探索。扩展代码只作为**实现参考**，若与 ok-cosmic A/B 层规范冲突，以 ok-cosmic 规范为准。未配置扩展代码库时跳过本流程。

## 代码生成后自动校验（Post-Check）

修改任何苍穹 `.java` 文件后，**{{POST_CHECK_REQUIRED}}** 执行：

`cosmic_post_check`（`path` 传文件或目录，`fixHint` 默认开启）

- `ERROR` → 必须修复并复检；`WARNING` → 优先修复；`INFO` → 治理参考，不阻断。
- Gradle 项目编译成功后仍串联 post-lint 静态校验；非 Gradle 直接回退静态 lint。
- 最多连续 **{{POST_CHECK_MAX_ROUNDS}}** 轮“修复 → 复检”；之后仍有 `ERROR`，向用户报告。
- 规则 ID 映射、修复示例、历史项目解释口径见 `rules/post-check.md`。

## 规则分层

- **A 层（交付红线）**：影响正确性、事件时机、插件上下文、事实准确性；新代码必须满足，命中即阻断。见 `rules/constraints.md`、`rules/anti-patterns.md`、`rules/platform-baseline.md`。
- **B 层（新代码默认）**：团队希望优先采用的封装、工具类、查询方式和实现风格；历史代码不因未完全满足直接判错。见 `rules/coding-preferences.md`。
- **C 层（渐进治理）**：补验证来源注释、统一异常体系、减少原生样板；用于重构和专项治理，不作为默认阻断条件。见 `rules/coding-preferences.md`、`rules/post-check.md`。

处理历史项目时优先识别 A 层问题；对 B/C 层区分“当前可接受的历史写法”和“建议后续收敛的目标写法”。

## 子文件引用

优先读文档开头的 `TL;DR`，再决定是否展开全文。

| 层级 | 文件 | 用途 |
|---|---|---|
| A 层 | `rules/constraints.md` | 硬约束 / 交付红线 |
| A 层 | `rules/a-layer-rules.json` | 规则 ID 配置（单一可信源） |
| A 层 | `rules/anti-patterns.md` | 禁忌清单 |
| 路由 | `rules/intent-routing.md` / `rules/decision-matrix.md` | 意图路由与完整决策矩阵 |
| 参考 | `references/adv/event-lifecycle.md` | 插件事件生命周期图 |
| B/C 层 | `rules/coding-preferences.md` | 编码偏好与 STYLE/RESOURCE/VERIFY 速查 |
| 参考 | `rules/platform-baseline.md` | 平台总规范摘要 |
| 速查 | `rules/cheat-sheet.md` | 高频 API（无需查询验证） |
| 校验 | `rules/post-check.md` | 生成后校验规则（A/B/C 分层） |
| 片段 | `assets/snippets/snippets-guide.md` | 场景化代码片段（推荐 / 原生两层） |
