# 自然语言意图到场景路由

当用户不是直接说“操作插件 / 表单插件 / 基础资料能力”，而是用业务话术描述需求时，先把需求翻译为：

1. **时机**：事情发生在什么时候
2. **载体**：发生在哪个界面 / 后台 / 接口
3. **能力**：还需要补哪种公共能力

完成翻译后，再去 [decision-matrix.md](decision-matrix.md) 命中 **1 行主场景** 和 **0-1 行能力行**。

## 路由三问

### 1. 先看“时机词”

| 用户常说的话                                   | 优先翻译为 | 对应矩阵 |
|------------------------------------------|---|---|
| 保存前 / 提交时 / 审核时 / 反审核时 / 操作时 / 校验不通过不让继续 | 操作链上的校验、补值、拦截 | [`#op-plugin`](decision-matrix.md#op-plugin) |
| 下推时 / 选单时 / 来源单据带出 / 转换规则                | 单据转换阶段处理 | [`#convert-plugin`](decision-matrix.md#convert-plugin) |
| 用户口头说“反写” / 回写上游 / 更新来源状态                | 默认先按“更新关联实体”理解，不直接等于反写插件 | 继续结合主动作判断是操作插件、转换插件，还是明确的反写插件 |
| 明确指定“反写插件” / 明确要求挂在 BOTP 回写阶段            | 反写阶段处理 | [`#writeback-plugin`](decision-matrix.md#writeback-plugin) |
| 打开页面时 / 页面初始化时 / 字段值改变 / 点击按钮时 / F7 过滤时  | 前端交互阶段处理 | 继续看“载体词”决定是表单、单据还是列表插件 |
| 定时执行 / 每天跑一次 / 后台批处理 / 异步任务              | 后台任务 | [`#task-plugin`](decision-matrix.md#task-plugin) |
| 外部系统调用 / 提供接口 / 回调地址                     | OpenAPI 控制器 | [`#openapi-plugin`](decision-matrix.md#openapi-plugin) |
| 打印时 / 套打时                                | 打印插件 | [`#print-plugin`](decision-matrix.md#print-plugin) |
| 导入时 / 批量导入时                              | 导入插件 | [`#import-plugin`](decision-matrix.md#import-plugin) |
| 审批流节点 / 工作流动作                            | 工作流插件 | [`#workflow-plugin`](decision-matrix.md#workflow-plugin) |

### 2. 再看“载体词”

当前一步落到“前端交互阶段处理”时，再用载体词确定主场景：

| 用户常说的话 | 优先翻译为 | 对应矩阵 |
|---|---|---|
| 表单上 / 页面上 / 打开窗口 / 字段联动 / 控件显示隐藏 | 表单插件 | [`#form-plugin`](decision-matrix.md#form-plugin) |
| 单据界面 / 单据头体 / 审核提交按钮 / 单据字段联动 / 分录操作（增行、删行、遍历分录） | 单据插件 | [`#bill-plugin`](decision-matrix.md#bill-plugin) |
| 列表上 / 列表按钮 / 多选批量 / 批量处理 | 列表插件 | [`#list-plugin`](decision-matrix.md#list-plugin) |
| 左边树右边表 / 树节点切换右侧数据 | 树列表插件 | [`#tree-plugins`](decision-matrix.md#tree-plugins) |
| 报表过滤条件界面 / 报表表单 | 报表插件 | [`#report-form-plugin`](decision-matrix.md#report-form-plugin) |
| 报表取数 / 动态列 / 自定义数据源 | 报表取数插件 | [`#report-data-plugin`](decision-matrix.md#report-data-plugin) |

### 3. 最后补“能力词”

主场景命中后，再按需补 0-1 行能力行：

| 用户常说的话 | 追加能力 | 对应矩阵 |
|---|---|---|
| 打开页面 / 弹窗 / 新页签 / 跳详情页 | 视图打开与跳转 | [`#view-handler`](decision-matrix.md#view-handler) |
| 查数据 / 聚合 / 统计 / ORM / DataSet | 查询与存取 | [`#query`](decision-matrix.md#query) |
| 基础资料编码名称 / 引用类型 / 管控策略 / 分配 | 基础资料 | [`#basedata`](decision-matrix.md#basedata) |
| DynamicObject / 安全取值 / 序列化 | 数据包操作 | [`#dynamic-object`](decision-matrix.md#dynamic-object) |
| 实体元数据 / 字段路径 / DBRoute | 元数据与结构 | [`#entity-metadata`](decision-matrix.md#entity-metadata) |
| 分录行数据 / 增删分录行 / 遍历分录 / createNewEntryRow / getEntryRowEntity | 数据包操作 | [`#dynamic-object`](decision-matrix.md#dynamic-object) |
| 弹性域 / Flex | 弹性域 | [`#flex-prop`](decision-matrix.md#flex-prop) |
| 附件上传下载复制 | 附件与文件 | [`#attachment`](decision-matrix.md#attachment) |
| 邮件 / 消息 / 企业微信 / 短信 | 消息通知 | [`#message`](decision-matrix.md#message) |
| 组织权限 / 登录组织 / 上下文判定 | 上下文判定 | [`#request-context`](decision-matrix.md#request-context) |
| 异步线程 / 跨线程身份恢复 | 并发上下文恢复 | [`#thread-context`](decision-matrix.md#thread-context) |
| 业务拓展点 / 业务扩展点 / SDK 扩展接口 / 扩展场景编码 / Java 示例 | 业务拓展点查询 | [`#business-extpoints`](decision-matrix.md#business-extpoints) |

## 常见例句翻译

| 用户原话 | 推荐翻译 | 命中方式 |
|---|---|---|
| 我想在审核的时候校验金额不能超过 100 万 | 时机=`审核时`，载体=`单据操作`，能力=`校验` | 主场景命中 [`#op-plugin`](decision-matrix.md#op-plugin) |
| 页面上选了供应商以后自动过滤物料 | 时机=`字段值改变`，载体=`单据/表单界面`，能力=`基础资料 + 界面工具` | 主场景命中 [`#bill-plugin`](decision-matrix.md#bill-plugin) 或 [`#form-plugin`](decision-matrix.md#form-plugin)，补 [`#basedata`](decision-matrix.md#basedata) |
| 列表里加个批量审核按钮 | 时机=`点击按钮时`，载体=`列表`，能力=`操作链` | 主场景命中 [`#list-plugin`](decision-matrix.md#list-plugin)，必要时补 [`#op-plugin`](decision-matrix.md#op-plugin) |
| 下推后把来源单据状态改成已处理 | 时机=`下推后`，意图=`更新来源实体` | 先按转换链路中的实体更新理解；只有明确指定反写插件 / BOTP 回写点时才命中 [`#writeback-plugin`](decision-matrix.md#writeback-plugin) |
| 每天凌晨同步一次基础资料 | 时机=`定时执行`，载体=`后台`，能力=`基础资料` | 主场景命中 [`#task-plugin`](decision-matrix.md#task-plugin)，补 [`#basedata`](decision-matrix.md#basedata) |
| 提供一个接口给 WMS 回传结果 | 时机=`外部系统调用`，载体=`接口` | 主场景命中 [`#openapi-plugin`](decision-matrix.md#openapi-plugin) |
| 查一下应付相关的业务拓展点，并给一个 Java 示例 | 意图=`查询型`，能力=`业务拓展点查询` | 直接命中 [`#business-extpoints`](decision-matrix.md#business-extpoints)，先查候选，再按 `interfaceName` 查签名 |

## 查询型意图补充

如果用户不是要立刻写插件，而是在问“有没有 / 查一下 / 找一下 / 列出 / 给我示例”某类标准产品预留能力，可直接命中能力卡片。

| 用户常说的话 | 优先翻译为 | 对应矩阵 |
|---|---|---|
| 查业务拓展点 / 找业务扩展点 / 有没有标准扩展点 / SDK 扩展接口 / 扩展场景编码 / 业务扩展插件 / 给我拓展点 Java 示例 | 查询标准产品预留的业务拓展点候选 | [`#business-extpoints`](decision-matrix.md#business-extpoints) |
| 查类 / 查方法 / 看签名 / 这个接口有哪些方法 | SDK API 事实查询 | 先走 `cosmic-api-knowledge.py`；已命中具体实现需求时再回到矩阵 |

## 常见误判修正

- 用户说“审核页面上”但真正诉求是“审核动作发生时拦截”，优先按 [`#op-plugin`](decision-matrix.md#op-plugin) 处理，不要因为提到“页面”就误判成表单/单据插件。
- 用户说“页面上实时提示”“字段改了马上联动”，优先按表单 / 单据插件处理，不要误判成操作插件。
- “反写”相关（见上方路由三问）：口头“反写”/“下推后更新来源”默认理解为业务语义的“更新关联实体”；只有用户明确说“反写插件”“WriteBackPlugIn”“BOTP 回写阶段”时才命中 [`#writeback-plugin`](decision-matrix.md#writeback-plugin)。
- 用户说“后台自动跑”“定时同步”，优先按 [`#task-plugin`](decision-matrix.md#task-plugin)，不要误判成列表按钮或操作插件。
- 用户只说“查一批数据统计结果”，如果没有插件时机词，优先从 [`#query`](decision-matrix.md#query) 开始，而不是先猜某种插件。
- 用户说“业务拓展点 / 业务扩展点 / SDK 扩展接口 / 扩展场景编码”时，优先按 [`#business-extpoints`](decision-matrix.md#business-extpoints) 查询候选；不要误判成“OpenAPI 控制器”或直接套用 BOS 表单/操作插件模板。

## 仍然模糊时

- 先用本文件把用户原话翻成“时机 + 载体 + 能力”。
- 再去 [decision-matrix.md](decision-matrix.md) 读对应 1-3 行。
- 还是模糊时，按 [decision-matrix.md#fallback](decision-matrix.md#fallback) 的兜底流程继续。
