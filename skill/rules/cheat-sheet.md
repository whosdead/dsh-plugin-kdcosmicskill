# 高频 API 速查表

以下 API 签名已确认准确，AI 可直接使用无需脚本验证。

## QFilter 构造与组合 `[查询]`

```java
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;

// 基本构造
new QFilter("field", QCP.equals, value)
new QFilter("field", QCP.not_equals, value)
new QFilter("field", QCP.large_than, value)
new QFilter("field", QCP.less_than, value)
new QFilter("field", QCP.large_equals, value)
new QFilter("field", QCP.less_equals, value)
new QFilter("field", QCP.in, new Object[]{v1, v2, v3})
new QFilter("field", QCP.not_in, new Object[]{v1, v2, v3})
new QFilter("field", QCP.like, "%keyword%")
new QFilter("field", QCP.is_null, null)
new QFilter("field", QCP.is_not_null, null)

// 组合
filter1.and(filter2)
filter1.or(filter2)
```

## Model 读写 `[表单] [单据]`

```java
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;

// 读取字段值
Object val = getModel().getValue("fieldKey");                          // 表头字段
Object val = getModel().getValue("fieldKey", rowIndex);                // 分录字段
Object val = getModel().getValue("fieldKey", rowIndex, parentRowIndex);// 子分录字段

// 设置字段值（自动触发 propertyChanged）
getModel().setValue("fieldKey", value);                                // 表头
getModel().setValue("fieldKey", value, rowIndex);                      // 分录

// 按 ID 设置基础资料字段（自动加载引用对象，比 setValue + loadSingle 更简洁）
getModel().setItemValueByID("basedataFieldKey", pkId);                 // 表头基础资料
getModel().setItemValueByID("basedataFieldKey", pkId, rowIndex);       // 分录基础资料

// 按编码设置基础资料字段（已知编码不知 ID 时使用）
getModel().setItemValueByNumber("basedataFieldKey", number);           // 表头基础资料
getModel().setItemValueByNumber("basedataFieldKey", number, rowIndex); // 分录基础资料

// 分录操作
int rowCount = getModel().getEntryRowCount("entryKey");               // 分录行数
int newRow = getModel().createNewEntryRow("entryKey", rowIndex);      // 新增分录行
getModel().deleteEntryRow("entryKey", rowIndex);                      // 删除分录行
DynamicObject rowEntity = getModel().getEntryRowEntity("entryKey", rowIndex); // 获取分录行数据包
DynamicObject dataEntity = getModel().getDataEntity();                // 获取完整数据包
DynamicObjectCollection entry = dataEntity.getDynamicObjectCollection("entryKey"); // 分录集合
```

## View 控制 `[表单] [单据] [列表]`

```java
import kd.bos.form.FormShowParameter;
import kd.bos.form.control.Control;
import kd.bos.list.ListShowParameter;

// 字段锁定/解锁
getView().setEnable(boolean, "key1", "key2"...);       // 支持多 key

// 字段锁定/解锁(分录按行处理)
getView().setEnable(boolean, index , "key1", "key2"...);       // 支持多 key

// 字段显示/隐藏
getView().setVisible(boolean, "key1", "key2"...);      // 支持多 key

// 通知
getView().showSuccessNotification(String msg);
getView().showErrorNotification(String msg);
getView().showTipNotification(String msg);

// 操作
getView().invokeOperation(String opKey);               // 调用操作
getView().updateView(String key);                      // 刷新控件
getView().updateView();                                // 刷新整个视图
getView().showForm(FormShowParameter/ListShowParameter); // 打开页面

// PageCache（跨事件传值）
getView().getPageCache().put(String key, String value);
String val = getView().getPageCache().get(String key);

// 获取控件
Control ctrl = getView().getControl("controlKey");
```

## 封装工具类速查 `[通用]`

```java
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;
// fallback
// import org.apache.commons.lang3.StringUtils;
// import org.apache.commons.collections4.CollectionUtils;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.common.operate.OpUtils;
import kd.bos.orm.query.QFilter;

// 字符串判空
CharSequenceUtils.isBlank(str)
CharSequenceUtils.isNotBlank(str)
CharSequenceUtils.equals(a, b)

// 集合判空
CollectionUtils.isEmpty(collection)
CollectionUtils.isNotEmpty(collection)

// DynamicObject 取值
DynamicObjectUtils.nullSafeGet(dynamicObject, "field")       // 安全取值（空安全）
DynamicObjectUtils.setOf(dynamicObjects, "field")
DynamicObjectUtils.listOf(dynamicObjects, "field")

// 查询（标准 API）
QueryServiceHelper.queryOne(entityId, field, filters)                  // 查一条
QueryServiceHelper.query(entityId, selectFields, filters)              // 查多条 → DynamicObjectCollection
QueryServiceHelper.query(entityId, selectFields, filters, orderBy)     // 查多条 + 排序
QueryServiceHelper.queryDataSet(formId, algoKey, selectFields, filters, orderBy)  // 查 DataSet

// 操作（失败即抛异常，适合大多数场景）
OpUtils.executeOperateOrThrow(opKey, entityId, new Object[]{pk})
OpUtils.executeOperateOrThrow(opKey, entityId, dataEntities)
OpUtils.throwIfFail(operationResult)
OpUtils.addErrorMessage(plugin, dataEntity, message)

// 操作（需要自行解析结果，适合 MQ 消费、后台任务等"失败不抛异常"场景）
OperationResult result = OperationServiceHelper.executeOperate(opKey, entityId, pks, option)
if (!result.isSuccess()) { String errMsg = OpUtils.getCompleteFailMsg(result); /* 自行处理 */ }
```

## 弹窗与回调 `[表单] [单据]`

```java
import kd.bos.bill.OperationStatus;
import kd.bos.form.CloseCallBack;
import kd.bos.form.ConfirmCallBackListener;
import kd.bos.form.FormShowParameter;
import kd.bos.form.MessageBoxOptions;
import kd.bos.form.ShowType;
import kd.bos.list.ListShowParameter;
import kd.cd.common.form.ShowParameterUtils;

// 打开 F7 列表弹窗
ListShowParameter lsp = ShowParameterUtils.getF7List(formId, multiSelect);
getView().showForm(lsp);

// 打开普通表单弹窗
FormShowParameter fsp = ShowParameterUtils.getForm(formId, OperationStatus.ADDNEW, ShowType.Modal, "500px", "300px");
fsp.setCustomParam("key", value);
fsp.setCloseCallBack(new CloseCallBack(this, "actionId"));
getView().showForm(fsp);

// 确认框
ConfirmCallBackListener listener = new ConfirmCallBackListener("callbackId", this);
getView().showConfirm("确认信息？", MessageBoxOptions.YesNo, listener);
```

## 操作链 `[操作]`

```java
import kd.cd.common.operate.chain.OperateChain;

// 链式操作
OperateChain.of(dataEntity).save().submit().audit().failThenThrow();
OperateChain.of(entityId, pkValue).save().submit().failThenDeleteAndThrow();
```

## BOTP 下推与选单 `[转换]`

```java
import kd.cd.common.util.BotpUtils;
import kd.cd.common.util.BotpUtils.PushResult;

// 后台下推并保存（最常用，4 参数：源单标识, 目标单标识, 源单主键, 转换规则ID）
PushResult result = BotpUtils.pushAndSave(sourceFormId, targetFormId, sourcePk, null);
result.failThenThrow();
result.isSuccess();
result.getErrMsg();
Object[] targetPks = result.getPks();

// 下推不保存（只生成内存对象）
PushResult result = BotpUtils.pushNoSave(sourceFormId, targetFormId, sourcePk, null);

// 获取默认转换规则
String ruleId = BotpUtils.getDefaultRuleId(sourceFormId, targetFormId);

// 正向链路追踪：查所有下游目的单
Map<Long, Map<String, Set<Long>>> targets = BotpUtils.findAllTargetBills(targetEntityId, entityId, pkValues);

// 反向链路追踪：查直接上游源单
Map<Long, Set<Long>> sources = BotpUtils.findDirectSourceBills(sourceEntityId, entityId, pkValues);
```

## 元数据常用操作 `[通用]`

```java
import kd.bos.entity.MainEntityType;
import kd.bos.entity.property.EntryType;
import kd.bos.dataentity.metadata.IDataEntityProperty;
import kd.cd.common.entity.EntityUtils;

// 获取主实体类型
MainEntityType mainType = EntityUtils.getMainEntityType(formId);

// 获取字段属性（单据头 & 单据体均适用）
IDataEntityProperty prop = EntityUtils.getProperty(formId, "fieldKey");

// 获取分录实体类型
EntryType entryType = EntityUtils.getEntryType(formId, "entryKey");

```

## 异常与日志 `[通用]`

```java
import kd.bos.exception.ErrorCode;
import kd.bos.exception.KDBizException;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;

// 业务异常（统一使用 KDBizException）
throw new KDBizException(new ErrorCode("myModule", "errCode001"), "业务描述");

// 包装异常（保留原始 cause）
throw new KDBizException(new ErrorCode("myModule", "errCode002"), e);

// 日志记录（插件内直接使用内置 log）
log.info("操作完成: billNo={}", billNo);
log.error("操作失败", e);    // 异常用 error 级别

// 非插件类获取日志
private static final Log log = LogFactory.getLog(MyService.class);
```

## 多语言 `[通用]`

```java
import kd.bos.dataentity.resource.ResManager;

// 标准写法：完整句模板 + 占位符
String msg = String.format(
    ResManager.loadKDString("单据 %1$s 的金额 %2$s 超出限额", "MyPlugin_0", "kd-cd-myapp"),
    billNo, amount
);

// 错误写法（禁止拼接）
// String msg = "单据" + billNo + "的金额" + amount + "超出限额";
```