# 完整统一决策矩阵

从 [SKILL.md](../SKILL.md) 的精简索引命中场景后，只读对应 **1 个主场景卡片** 和 **0-1 个能力卡片**；不要整文通读。

如果用户原话还是业务描述、还没翻译成“操作插件 / 表单插件 / 基础资料能力”等技术场景，先读 [intent-routing.md](intent-routing.md) 做语义翻译。

> **提示**：`入口类` 主要用于缩小 `cosmic-api-knowledge.py` 的搜索范围，不代表必须直接依赖原生 API；`封装文档` 和 `原生文档` 都应先读 `TL;DR`，确实需要时再展开全文。

---

## 插件类

### 表单插件 {#form-plugin}
- **关键词**: 表单 UI / 字段联动 / 控件交互
- **入口类**: `AbstractFormPluginExt` · `kd.bos.form.plugin.*` · `kd.bos.form` / `kd.bos.form.control`
- **先读**: [plugin-base.md](../references/adv/plugin-base.md) · [form-utils.md](../references/adv/form-utils.md)
- **兜底**: [plugin-form.md](../references/base/plugin/plugin-form.md)
- **模板**: [FormPluginTemplate.java](../assets/FormPluginTemplate.java)
- **片段**: [ViewControlOpsSample](../assets/snippets/form/ViewControlOpsSample.java) · [F7FilterSample](../assets/snippets/form/F7FilterSample.java)

### 单据插件 {#bill-plugin}
- **关键词**: 单据 UI / 审核提交按钮
- **入口类**: `AbstractBillPlugInExt` · `kd.bos.form.plugin.*`
- **先读**: [plugin-base.md](../references/adv/plugin-base.md) · [form-utils.md](../references/adv/form-utils.md)
- **兜底**: [plugin-bill.md](../references/base/plugin/plugin-bill.md)
- **模板**: [BillPlugInTemplate.java](../assets/BillPlugInTemplate.java)
- **片段**: [OpenBillModalSample](../assets/snippets/form/OpenBillModalSample.java)

### 列表插件 {#list-plugin}
- **关键词**: 列表 / 多选操作 / 批量
- **入口类**: `AbstractListPluginExt` · `AbstractListPlugin` · `kd.bos.list`
- **先读**: [plugin-base.md](../references/adv/plugin-base.md)
- **兜底**: [plugin-list.md](../references/base/plugin/plugin-list.md)
- **模板**: [ListPluginTemplate.java](../assets/ListPluginTemplate.java)
- **片段**: [ListPluginBasicSample](../assets/snippets/list/ListPluginBasicSample.java)

### 树列表插件 {#tree-plugins}
- **关键词**: 左树右表（单据 / 基础资料）
- **入口类**: `AbstractTreeListPlugin` · `kd.bos.list`
- **先读**: [plugin-base.md](../references/adv/plugin-base.md)
- **兜底**: [plugin-tree-list.md](../references/base/plugin/plugin-tree-list.md)
- **模板（单据）**: [TreeListPluginTemplate.java](../assets/TreeListPluginTemplate.java)
- **模板（基础资料）**: [StandardTreeListPluginTemplate.java](../assets/StandardTreeListPluginTemplate.java)
- **片段**: [TreeControlSample](../assets/snippets/form/TreeControlSample.java)

### 操作插件 {#op-plugin}
- **关键词**: 审核 / 保存 / 状态流转 / 校验 / 回滚
- **入口类**: `OpUtils` · `OperateChain` · `OperationServiceHelper`（MQ/后台任务） · `AbstractOperationServicePlugIn` · `kd.bos.entity.plugin.*`
- **先读**: [operate-chain.md](../references/adv/operate-chain.md)
- **兜底**: [plugin-operation.md](../references/base/plugin/plugin-operation.md) · [sdk-dynamic-model-svc.md](../references/base/sdk/sdk-dynamic-model-svc.md)
- **模板**: [OpPluginTemplate.java](../assets/OpPluginTemplate.java)
- **片段**: [OpAddValidatorsSample](../assets/snippets/operation/OpAddValidatorsSample.java) · [OperationOptionBridgeSample](../assets/snippets/operation/OperationOptionBridgeSample.java)

### 转换插件 {#convert-plugin}
- **关键词**: 下推 / 选单 / 来源追踪 / 转换
- **入口类**: `BotpUtils` · `kd.bos.entity.botp.runtime.*` · `AbstractConvertPlugIn`
- **先读**: [botp-convert.md](../references/adv/botp-convert.md)
- **兜底**: [plugin-botp.md](../references/base/plugin/plugin-botp.md)
- **模板**: [ConvertPlugInTemplate.java](../assets/ConvertPlugInTemplate.java)
- **片段**: [BotpTracePushSample](../assets/snippets/botp/BotpTracePushSample.java)

### 反写插件 {#writeback-plugin}
- **关键词**: 明确指定反写插件 / BOTP 回写阶段
- **入口类**: `AbstractWriteBackPlugIn` · `ConvertServiceHelper`
- **先读**: [botp-convert.md](../references/adv/botp-convert.md)
- **兜底**: [plugin-writeback.md](../references/base/plugin/plugin-writeback.md)
- **模板**: [WriteBackPlugInTemplate.java](../assets/WriteBackPlugInTemplate.java)
- **片段**: [BatchQuerySample](../assets/snippets/query/BatchQuerySample.java)

### 报表插件 {#report-form-plugin}
- **关键词**: 报表 / 数据分析 / 过滤容器
- **入口类**: `AbstractReportFormPlugin`
- **兜底**: [plugin-report-form.md](../references/base/plugin/plugin-report-form.md)
- **模板**: [ReportFormPluginTemplate.java](../assets/ReportFormPluginTemplate.java)
- **片段**: [SampleReportFormPlugin](../assets/snippets/report/SampleReportFormPlugin.java)

### 报表取数插件 {#report-data-plugin}
- **关键词**: 报表取数 / 动态列 / 自定义数据源
- **入口类**: `AbstractReportListDataPlugin` · `DataSet`
- **先读**: [query-dataset.md](../references/adv/query-dataset.md)
- **兜底**: [plugin-report-data.md](../references/base/plugin/plugin-report-data.md) · [sdk-algo.md](../references/base/sdk/sdk-algo.md)
- **模板**: [ReportListDataPluginTemplate.java](../assets/ReportListDataPluginTemplate.java)
- **片段**: [SampleReportListDataPlugin](../assets/snippets/report/SampleReportListDataPlugin.java) · [DataSetQueryStatSample](../assets/snippets/query/DataSetQueryStatSample.java)

### 打印插件 {#print-plugin}
- **关键词**: 打印 / 套打
- **入口类**: `AbstractPrintPlugin`
- **兜底**: [plugin-print.md](../references/base/plugin/plugin-print.md)
- **模板**: [PrintPluginTemplate.java](../assets/PrintPluginTemplate.java)

### OpenAPI 控制器 {#openapi-plugin}
- **关键词**: OpenAPI / 外部集成
- **入口类**: `@ApiController` · `@ApiMapping` · `kd.bos.openapi.common.custom.annotation`
- **兜底**: [plugin-openapi.md](../references/base/plugin/plugin-openapi.md)
- **模板**: [OpenApiControllerTemplate.java](../assets/OpenApiControllerTemplate.java)

### 后台任务 {#task-plugin}
- **关键词**: 后台任务 / 定时 / 调度作业
- **入口类**: `AbstractTask`
- **兜底**: [plugin-task.md](../references/base/plugin/plugin-task.md)
- **模板**: [TaskTemplate.java](../assets/TaskTemplate.java)
- **片段**: [ScheduleTaskSample](../assets/snippets/task/ScheduleTaskSample.java)

### 工作流插件 {#workflow-plugin}
- **关键词**: 工作流 / 审批流
- **入口类**: `IWorkflowPlugin`
- **兜底**: [plugin-workflow.md](../references/base/plugin/plugin-workflow.md)
- **模板**: [IWorkflowPluginTemplate.java](../assets/IWorkflowPluginTemplate.java)
- **片段**: [SampleWorkflowPlugin](../assets/snippets/workflow/SampleWorkflowPlugin.java)

### 导入插件 {#import-plugin}
- **关键词**: 导入 / 批量导入
- **入口类**: `BatchImportPlugin`
- **兜底**: [plugin-import.md](../references/base/plugin/plugin-import.md)
- **模板**: [BatchImportPluginTemplate.java](../assets/BatchImportPluginTemplate.java)

---

## 能力类

### 视图打开与跳转 {#view-handler}
- **关键词**: 后台打开表单 / 列表 / 新页签跳转
- **入口类**: `ViewHandler` · `AutoCloseViewHandler` · `FormShowParameter`
- **先读**: [view-handler.md](../references/adv/view-handler.md)

### 界面工具 {#form-utils}
- **关键词**: 表单控件 / UI 消息 / 元数据读取
- **入口类**: `FormUtils` · `this.getView()` / `this.getModel()` · `kd.bos.form.control`
- **先读**: [form-utils.md](../references/adv/form-utils.md)
- **兜底**: [plugin-form.md](../references/base/plugin/plugin-form.md) · [plugin-bill.md](../references/base/plugin/plugin-bill.md) · [plugin-list.md](../references/base/plugin/plugin-list.md)
- **片段**: [ViewControlOpsSample](../assets/snippets/form/ViewControlOpsSample.java) · [ConfirmDialogSample](../assets/snippets/form/ConfirmDialogSample.java) · [F7FilterSample](../assets/snippets/form/F7FilterSample.java)

### 查询与存取 {#query}
- **关键词**: 数据加载 / 保存 / 查询 / ORM / DataSet
- **入口类**: `AlgoUtils` · `BusinessDataServiceHelper` · `QueryServiceHelper` · `QFilter`
- **先读**: [query-dataset.md](../references/adv/query-dataset.md)
- **兜底**: [sdk-dynamic-model-svc.md](../references/base/sdk/sdk-dynamic-model-svc.md) · [sdk-orm-access.md](../references/base/sdk/sdk-orm-access.md) · [sdk-algo.md](../references/base/sdk/sdk-algo.md)
- **片段**: [BatchQuerySample](../assets/snippets/query/BatchQuerySample.java) · [DataSetQueryStatSample](../assets/snippets/query/DataSetQueryStatSample.java) · [DynamicObjectCrudSample](../assets/snippets/data/DynamicObjectCrudSample.java)

### 基础资料 {#basedata}
- **关键词**: 基础资料 / 管控策略 / 分配 / 个性化 / 客户 / 物料 / 供应商
- **入口类**: `BaseDataServiceHelper` · `kd.bd.master.*`
- **先读**: 先用 `cosmic-form-metadata.py` / `cosmic-basedata-query.py` 确认 `refType` / `entityId`
- **兜底**: [sdk-dynamic-model-svc.md](../references/base/sdk/sdk-dynamic-model-svc.md)
- **片段**: [BaseDataQuerySample](../assets/snippets/query/BaseDataQuerySample.java)

### 数据包操作 {#dynamic-object}
- **关键词**: DynamicObject / 安全取值 / 序列化
- **入口类**: `DynamicObjectUtils` · `DynamicObject` · `DynamicObjectCollection`
- **先读**: [dynamic-object.md](../references/adv/dynamic-object.md)
- **兜底**: [sdk-dynamic-object.md](../references/base/sdk/sdk-dynamic-object.md)
- **片段**: [DynamicObjectOpsSample](../assets/snippets/data/DynamicObjectOpsSample.java) · [DynamicObjectCrudSample](../assets/snippets/data/DynamicObjectCrudSample.java)

### 元数据与结构 {#entity-metadata}
- **关键词**: 实体元数据 / 字段路径 / DBRoute
- **入口类**: `EntityUtils` · `EntityType` · `MainEntityType` · `IDataEntityProperty` · `DBRoute`
- **先读**: [entity-metadata.md](../references/adv/entity-metadata.md)
- **兜底**: [sdk-entity-model.md](../references/base/sdk/sdk-entity-model.md) · [sdk-orm-access.md](../references/base/sdk/sdk-orm-access.md)

### 弹性域 {#flex-prop}
- **关键词**: 弹性域字段 / 值解析
- **入口类**: `FlexPropUtils` · `FlexType`
- **先读**: [flex-prop.md](../references/adv/flex-prop.md)

### 消息通知 {#message}
- **关键词**: 消息 / 邮件 / 短信 / 企业微信
- **入口类**: `MessageServiceHelper` · `EmailInfo` · `kd.bos.message.api.*`
- **片段**: [MessageNotifySample](../assets/snippets/message/MessageNotifySample.java)

### 附件与文件 {#attachment}
- **关键词**: 附件上传 / 下载 / 复制 / 面板绑定
- **入口类**: `AttachmentUtils` · `AttachmentServiceHelper`
- **先读**: [attachment-api.md](../references/adv/attachment-api.md)
- **兜底**: [sdk-file.md](../references/base/sdk/sdk-file.md)
- **片段**: [AttachmentUploadBindSample](../assets/snippets/attachment/AttachmentUploadBindSample.java)

### 上下文判定 {#request-context}
- **关键词**: 组织 / 权限 / 上下文
- **入口类**: `RequestContext`
- **先读**: [request-context.md](../references/adv/request-context.md)
- **兜底**: [sdk-request-context.md](../references/base/sdk/sdk-request-context.md)

### 并发上下文恢复 {#thread-context}
- **关键词**: 跨线程身份 / 上下文恢复 / 线程池批量处理
- **入口类**: `RequestContextUtils` · `ThreadPools` · `ExecutorServiceUtils`
- **先读**: [request-context.md](../references/adv/request-context.md)
- **兜底**: [sdk-threadpool.md](../references/base/sdk/sdk-threadpool.md) · [sdk-request-context.md](../references/base/sdk/sdk-request-context.md)
- **片段**: [SampleThreadPoolBatch](../assets/snippets/concurrent/SampleThreadPoolBatch.java)

### 消息队列 {#mq-consumer}
- **关键词**: MQ / 消息队列 / 异步消费 / MessageConsumer
- **入口类**: `MessageConsumer` · `MessageAcker` · `MQFactory` · `DLock`
- **片段**: [SampleMQConsumer](../assets/snippets/mq/SampleMQConsumer.java)

### 缓存 {#cache}
- **关键词**: 缓存 / AppCache / 分布式缓存 / loadFromCache
- **入口类**: `AppCache` · `DistributeSessionlessCache` · `CacheFactory` · `BusinessDataServiceHelper.loadFromCache`
- **片段**: [SampleCacheUsage](../assets/snippets/cache/SampleCacheUsage.java)

### 业务拓展点查询 {#business-extpoints}
- **关键词**: 业务拓展点 / 业务扩展点 / SDK 扩展接口 / 扩展场景 / 扩展插件 / 二开接口 / Java 示例 / route extpoint
- **入口脚本**: `scripts/cosmic-extpoints-query.py`
- **先查**: `python3 <SKILL_ROOT>/scripts/cosmic-extpoints-query.py --config ok-cosmic.json get --keyword <业务关键词>`
- **示例查询**: 默认不用 `--full`；只有当需要生成实现代码或查看返回中的 Java/代码示例时，才追加 `--full`
- **后续验证**: 若 `--full` 返回中已有清晰 Java 示例代码（能识别 `implements` 接口、`@Override` 方法、参数与返回值），可先按示例生成，不强制立即调用 `cosmic-api-knowledge.py detail`；若示例缺失/不完整/签名仍不确定，再用 `detail <full.class.Name>` 确认
- **约束**: 仅用于定位标准产品预留的业务拓展点候选；不要误判为 OpenAPI 控制器、表单插件或操作插件；示例代码可作为优先参考，但示例不清晰时不得猜方法签名

---

## 未命中兜底 {#fallback}

如果需求不属于上述任一场景：

1. **需求可拆分**：将需求拆解为多个子任务，分别匹配卡片。常见组合：
   - "保存前校验 + 审核后下推 + 下推后在 BOTP 回写阶段更新来源" → 操作插件 + 转换插件 + 反写插件
   - "列表批量选中 + 弹窗编辑 + 保存" → 列表插件 + 表单插件 + 操作插件
2. **纯后端逻辑（无 UI、无操作链）**：优先命中"查询与存取""基础资料""消息通知""并发上下文恢复"等能力卡片；仍不匹配时，再回到 `references/base/sdk/` 原生 SDK 文档。
3. **用户想找标准产品预留扩展点**：优先命中 [`#business-extpoints`](#business-extpoints)，用业务关键词查候选接口，再用 `cosmic-api-knowledge.py detail` 校验签名。
4. **仍无法匹配**：先用 `cosmic-api-knowledge.py search <关键词>` 搜索相关类，再向用户确认具体实现方向后动手。
5. **扩展代码库探索**（需 `ok-cosmic.json` 配置 `extensionRepos`）：
   用当前需求关键词在扩展代码库中搜索相关 Java 实现，
   作为实现参考但仍需遵守 ok-cosmic 规范。
