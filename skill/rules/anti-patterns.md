# 禁忌清单 (Anti-Patterns)

> 本文件为禁忌清单；其中 `scene_check.py`（SCENE-\*）对应的场景错配条目会被自动检测。
> 幻觉方法名/类名黑名单用于事前人工/AI 自检，`hal_check.py` 与 `HAL-*` 事后 lint 规则已废弃。
> 场景错配条目默认属于 **A 层硬约束**，除非单独标注 `[B层]`。
> 编码风格（STYLE-\*）和资源管理（RESOURCE-\*）规则见 [coding-preferences.md](coding-preferences.md)。

## 幻觉方法名黑名单 `[事前自检]`

| 错误写法 | 正确替代 | 说明 |
|---|---|---|
| `setReadOnly(...)` | `getView().setEnable(false, "key")` | 苍穹不存在 setReadOnly 方法 |
| `afterCreateControl(...)` | `afterBindData` / `registerListener` | 不存在该事件方法 |
| `IDataModel.setReadOnly(...)` | `getView().setEnable(false, "key")` | 模型层不负责 UI 状态 |
| `this.getView().refresh()` | `this.getView().updateView(key)` | 不存在 refresh()，使用 updateView |
| `model.getEntryCount(...)` | `model.getEntryRowCount(entryKey)` | 方法名和参数都不同 |
| `model.deleteRow(...)` | `model.deleteEntryRow(entryKey, rowIndex)` | 方法名不同 |
| `model.addRow(...)` | `model.createNewEntryRow(entryKey, rowIndex)` | 方法名不同 |
| `destroy(...)` | `destory(...)` | 苍穹方法名就是 destory（少 r），不是 destroy |
| `model.getRowCount(...)` | `model.getEntryRowCount(entryKey)` | 与 JTable 的 getRowCount 混淆 |
| `model.getEntry(...)` | `dataEntity.getDynamicObjectCollection(entryKey)` 或 `model.getEntryRowEntity(entryKey, rowIndex)` | IDataModel 不存在 getEntry() |
| `QueryServiceHelper.queryAll(...)` | `QueryServiceHelper.query(entityId, selectFields, filters)` | 不存在 queryAll 方法 |

## 幻觉类名黑名单 `[事前自检]`

- ❌ 不存在以 `Cosmic` 开头的工具类（Utils/Helper/Service），除非脚本明确查到。
- ❌ 不存在以 `Cloud` 开头的 Utils/Helper 工具类，除非脚本明确查到。项目自定义的 `Cloud*Service` 不在该规则范围内。
- ❌ 不存在 `BillHelper`（应为 `BusinessDataServiceHelper`）。
- ❌ 不存在 `FormHelper`（应为 `FormUtils`，位于 `kd.cd.common.form`）。
- ❌ 不存在 `ListHelper`（应为 `BaseDataServiceHelper` 或 `QueryServiceHelper`）。
- ❌ 不存在 `PluginHelper`（应按具体场景使用对应 ServiceHelper）。

## 场景错配黑名单 `[A层]`

| 错误做法 | 原因 | 正确做法 | 层级 |
|---|---|---|---|
| 在操作插件中调用 `this.getView()` | 操作插件无 UI 上下文 | 使用 `log` 或 `addErrorMessage` | `[A层]` `→ SCENE-001` |
| 在操作插件中 `this.getModel().setValue(...)` | 操作插件不通过 model 操作 | 直接操作 `DynamicObject` 数据包 | `[A层]` `→ SCENE-002` |
| 在 UI 插件中做重查询/复杂事务 | UI 插件应保持轻量 | 移至操作插件或服务层 | `[B层]` |
| 在 `initialize()` 中注册监听或写 UI 状态逻辑 | 生命周期不对，可能失效或错时执行 | 在 `registerListener` 注册，在 `afterBindData` 处理界面状态 | `[A层]` `→ SCENE-006` |
| 在 `registerListener` 中调用 `model.getValue(...)` | 此时数据尚未绑定 | 推迟到 `afterBindData` | `[B层]` |
| 在 `beforeBindData` / `afterBindData` 中 `setValue` 或改数据包 | 绑定阶段禁止改数据 | 改到 `createNewData`、`propertyChanged`、保存前等正确事件 | `[A层]` `→ SCENE-008` |
| 在 `afterCreateNewData` 中期望触发 `propertyChanged` | 此时赋值不触发 | 在 `afterBindData` 中处理级联 | `[B层]` |
| 仅 `implements Listener` 不注册监听 | 监听不会生效 | 在 `registerListener` 中调用 `add*Listener` | `[B层]` |
| 对继承型插件 `@Override` 不调 `super.xxx()` | 基类初始化逻辑不执行 | 继承型必须先调 `super`；接口型无需 | `[B层]` |
| 直接修改 `EntityMetadataCache` 返回的元数据对象 | 缓存对象是单例，污染全局 | `clone` 后再修改 | `[A层]` |
| 用其他实体 `createInstance()` 的对象给引用属性赋值 `→ SCENE-010` | 引用对象类型可能不一致 | 使用属性复杂类型或当前实体元数据创建对象 | `[A层]` |
| 在 `propertyChanged` 中无条件 `setValue` `→ SCENE-012` | 触发新的 `propertyChanged` 导致死循环（栈溢出） | 先判断 `e.getProperty().getName()` 是否是目标字段，赋值前比对新旧值是否相同 | `[A层]` |
