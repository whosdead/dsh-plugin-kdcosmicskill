# Snippets 分层说明

`assets/snippets` 里的样例按“如何选代码”分成两层。从决策矩阵命中插件类型后，可直接在下表中查找对应 snippet。

> 完整插件选型与能力矩阵见 [rules/decision-matrix.md](../../rules/decision-matrix.md)。

## 第一层：推荐写法

- 默认先读这一层。
- 这层代表当前 `ok-cosmic` 推荐的实现路径：优先走 commons 封装、`*Ext` 基类、团队认可的默认写法。
- 即使内部仍包含少量 BOS 原生 API，也表示“这是当前 skill 默认推荐的落地方式”，不是让模型先去拼底层样板。

| 场景关键词 | snippet 文件 |
|---|---|
| 字段取值/赋值/getValue/setValue | [GetAndSetValueSample.java](form/GetAndSetValueSample.java) |
| 页面状态/锁定/可见/刷新/关闭/pageCache | [ViewControlOpsSample.java](form/ViewControlOpsSample.java) |
| 分录行计算/汇总/金额联动 | [EntryRowCalculateSample.java](form/EntryRowCalculateSample.java) |
| 确认框/二次确认/ConfirmCallBack | [ConfirmDialogSample.java](form/ConfirmDialogSample.java) |
| 操作前拦截确认/beforeDoOperation | [BeforeOperationConfirmSample.java](form/BeforeOperationConfirmSample.java) |
| F7 过滤/基础资料弹窗过滤 | [F7FilterSample.java](form/F7FilterSample.java) |
| 打开单据弹窗/showForm/Modal | [OpenBillModalSample.java](form/OpenBillModalSample.java) |
| 子页面回传数据/closedCallBack | [ReturnParentDataSample.java](form/ReturnParentDataSample.java) |
| 超链接跳转/分录+列表 | [HyperlinkJumpSample.java](form/HyperlinkJumpSample.java) |
| 列表插件基础/选中行/批量操作 | [ListPluginBasicSample.java](list/ListPluginBasicSample.java) |
| 操作校验器/addValidators | [OpAddValidatorsSample.java](operation/OpAddValidatorsSample.java) |
| 操作参数传递/OperateOption/操作提示 | [OperationOptionBridgeSample.java](operation/OperationOptionBridgeSample.java) |
| 下推/选单/来源追踪/BotpUtils | [BotpTracePushSample.java](botp/BotpTracePushSample.java) |
| 批量查询样本/分组映射/批量反写/最近值恢复 | [BatchQuerySample.java](query/BatchQuerySample.java) |
| 查询/聚合/DataSet/统计 | [DataSetQueryStatSample.java](query/DataSetQueryStatSample.java) |
| 报表界面插件/校验/超链接/行加工 | [SampleReportFormPlugin.java](report/SampleReportFormPlugin.java) |
| 报表取数/DataSet Cookbook/动态列 | [SampleReportListDataPlugin.java](report/SampleReportListDataPlugin.java) |
| 基础资料查询/loadFromCache | [BaseDataQuerySample.java](query/BaseDataQuerySample.java) |
| DynamicObject 操作/安全取值 | [DynamicObjectOpsSample.java](data/DynamicObjectOpsSample.java) |
| 后端建单/保存/查询/复制 | [DynamicObjectCrudSample.java](data/DynamicObjectCrudSample.java) |
| 附件上传/绑定/AttachmentUtils | [AttachmentUploadBindSample.java](attachment/AttachmentUploadBindSample.java) |
| 线程池批量处理/并发任务/优雅关闭 | [SampleThreadPoolBatch.java](concurrent/SampleThreadPoolBatch.java) |
| 缓存/AppCache/分布式缓存/loadFromCache | [SampleCacheUsage.java](cache/SampleCacheUsage.java) |

## 第二层：原生兜底

- 只有在第一层没有覆盖，或者场景本身就是控件底层能力 / 原生 BOS 事件时，再读这一层。
- 这层通常意味着：
  - 当前仓库暂无更高层封装；
  - 该场景更偏原生事件、控件 API、平台服务；
  - 写代码前更应该配合 `references/base/*` 和脚本校验一起使用。

| 场景关键词 | snippet 文件 |
|---|---|
| 树形控件/TreeView/树节点 | [TreeControlSample.java](form/TreeControlSample.java) |
| 列表预打开过滤/setFilter/preOpenForm | [ListPreOpenFilterSample.java](list/ListPreOpenFilterSample.java) |
| 消息通知/邮件/MessageService | [MessageNotifySample.java](message/MessageNotifySample.java) |
| 后台调度任务/定时任务 | [ScheduleTaskSample.java](task/ScheduleTaskSample.java) |
| MQ消费者/分布式锁防重/MessageAcker | [SampleMQConsumer.java](mq/SampleMQConsumer.java) |
| 工作流插件/审批参与人/流程通知/条件分支 | [SampleWorkflowPlugin.java](workflow/SampleWorkflowPlugin.java) |

## 选择规则

1. 同一主题如果第一层已经有样例，不要直接跳到第二层。
2. 第二层代码可以用，但默认不应反向覆盖第一层的团队写法。
3. 单个 snippet 头部的 4 行元信息优先作为快速判断依据：
   - `适用插件`
   - `优先封装`
   - `原生兜底`
   - `相关 lint 规则`
