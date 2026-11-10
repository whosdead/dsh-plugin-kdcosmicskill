# 高性能查询与计算 (QueryServiceHelper & AlgoUtils)

## TL;DR
- 适用：ORM/DataSet 查询、批量聚合、内存计算和少量确需的 SQL 场景。
- 先抓：`QueryServiceHelper` 负责取数，`AlgoUtils` 负责 `DataSet` 过滤、聚合和转换。
- 跳转：只是基础资料加载优先 `BusinessDataServiceHelper.loadFromCache`；字段/元数据确认别读本页。
- 继续读全文：当你要写批量查询、`DataSet` 聚合或原生 SQL 兜底逻辑时。

## 概述
数据查询与处理是苍穹性能优化的第一大战场。`QueryServiceHelper` (位于 `kd.bos.servicehelper`) 提供 ORM 与 SQL 查询能力，极大简化了单条或批量数据的检索逻辑；`AlgoUtils` 针对苍穹特有的 `DataSet` 提供了类 Stream 的内存计算 API，是处理大数据量业务逻辑的首选。

> **适用边界**
> ✅ 适用：单条/批量数据查询、DataSet 内存计算与统计。
> ❌ 不适用：基础资料查询优先用 `BusinessDataServiceHelper.loadFromCache`；实体查询无法表达的场景才考虑裸 SQL。

## 核心类
- **`kd.bos.servicehelper.QueryServiceHelper`**: **查询核心**。负责从数据库（ORM 或原生 SQL）获取数据。
- **`kd.cd.common.util.AlgoUtils`**: **计算核心**。负责对 `DataSet` 进行过滤、聚合与转换。
- **`kd.bos.algo.DataSet`**: 苍穹高性能数据集对象。

## QueryServiceHelper API 方法

### 1. 单条查询
- `queryOne(String entityName, String selectFields, QFilter[] filters)`: 查询一条记录。
- `exists(String entityName, QFilter[] filters)`: 判断是否存在满足条件的记录。

### 2. 集合查询
- `query(String entityName, String selectFields, QFilter[] filters)`: 查询多条记录，返回 DynamicObjectCollection。
- `query(String entityName, String selectFields, QFilter[] filters, String orderBy)`: 带排序的查询。

### 3. 数据集查询
- `queryDataSet(String formId, String algoKey, String selectFields, QFilter[] filters, String orderBy)`: **最常用**。执行实体查询并返回 DataSet。
- `queryDataSet(String formId, String algoKey, String selectFields, QFilter[] filters, String orderBy, int top)`: 带条数限制的查询。
- `queryDataSet(DBRoute dbRoute, String sql)`: 原生 SQL 查询。
- `queryDataSet(DBRoute dbRoute, String sql, Object[] params)`: 带参数的原生 SQL 查询。

## AlgoUtils API 方法

### 1. 流处理
- `stream(DataSet dataSet)`: 提供对 DataSet 的 Stream 流支持。

### 2. 过滤与转换
- `filter(DataSet dataSet, Predicate<Row> filter)`: 对数据集执行高效过滤。
- `nullToZero(DataSet dataSet, String... fields)`: 将为 null 的字段值更新为 0。

### 3. 聚合计算
- `sumOf(DataSet dataSet, String field)`: 对数据集进行内存求和（返回 BigDecimal）。
- `listOf(DataSet dataSet, String field)`: 将数据集的一列提取为 List。
- `listOf(DataSet dataSet, Function<Row, T> function)`: 自定义函数提取为 List。
- `setOf(DataSet dataSet, String field)`: 将数据集的一列提取为 Set。
- `setOf(DataSet dataSet, Function<Row, T> function)`: 自定义函数提取为 Set。

### 4. 信息获取
- `fieldsOf(DataSet dataSet)`: 获取 DataSet 中字段数组。
- `sizeOf(DataSet dataSet)`: 获取 DataSet 大小。
- `dump(DataSet dataSet, String... fields)`: 转储 DataSet，按字段分组为 List。

### 5. 数据集创建
- `newDataSet(DynamicObjectCollection coll)`: 从 DynamicObjectCollection 创建 DataSet。
- `newDataSet(Map<String, DataType> metaMap, List<Object[]> seqRows)`: 根据类型映射创建。
- `newDataSet(String[] fields, DataType[] dataTypes, List<Object[]> seqRows)`: 根据字段定义创建。
- `newDataSet(RowMeta rowMeta, List<Object[]> seqRows)`: 根据 RowMeta 创建。
- `emptyDataSet(Map<String, DataType> metaMap, int initialSize)`: 生成空行 DataSet。
- `emptyDataSet(RowMeta rowMeta, int initialSize)`: 根据 RowMeta 生成空行 DataSet。
- `appendNullRow(DataSet dataSet, int size)`: 为 DataSet 追加空行。

### 6. 行元数据操作
- `rowAddField(Row row, String field, DataType dataType, Object value)`: Row 对象添加新字段并赋值。
- `rowMetaAddField(RowMeta rowMeta, String field, DataType dataType)`: RowMeta 添加新字段。
- `dumpRowMeta(RowMeta rowMeta)`: RowMeta 转换为类型映射。

### 7. 调试输出
- `print(DataSet dataSet)`: 等距对齐打印 DataSet。
- `print(DataSet dataSet, int top)`: 打印前 N 条。
- `print(DataSet dataSet, int top, boolean withJavaType)`: 带类型打印。

## 示例代码

### DataSet 组合查询与计算
```java
package kd.cd.common.demo;

import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.common.util.AlgoUtils;
import kd.bos.algo.DataSet;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QueryDemo {
    public void execute() {
        // 1. 查询已审核单据及其金额
        QFilter filter = new QFilter("status", QCP.equals, "C");
        try (DataSet ds = QueryServiceHelper.queryDataSet("my_bill", "my_bill", "id,totalamount", filter.toArray(), null);
             DataSet dsForSum = ds.copy();
             DataSet filtered = AlgoUtils.filter(ds, row ->
                 row.getBigDecimal("totalamount").compareTo(BigDecimal.valueOf(1000)) > 0)) {
            // 2. 计算已查询数据的金额合计
            BigDecimal sum = AlgoUtils.sumOf(dsForSum, "totalamount");

            // 3. 提取高金额单据ID
            List<Object> ids = AlgoUtils.listOf(filtered, "id");
        }
    }
}
```

### 快速查询示例
```java
public void quickQuery() {
    // 查一条记录
    DynamicObject row = QueryServiceHelper.queryOne("my_bill", "id,name",
            new QFilter[]{new QFilter("number", QCP.equals, "BILL001")});

    // 判断是否存在
    boolean exists = QueryServiceHelper.exists("my_bill",
            new QFilter[]{new QFilter("number", QCP.equals, "BILL001")});
    QFilter filter = new QFilter("status", QCP.equals, "C");

    // 查询多条 → DynamicObjectCollection
    DynamicObjectCollection rows = QueryServiceHelper.query("my_bill", "id,name", filter.toArray());
}
```

### 原生 SQL 查询
```java
public void sqlQuery(DBRoute dbRoute) {
    String sql = "SELECT id, name FROM my_table WHERE status = ?";
    try (DataSet ds = QueryServiceHelper.queryDataSet(dbRoute, sql, new Object[]{"C"})) {
        // 处理结果
        List<Object> ids = AlgoUtils.listOf(ds, "id");
    }
}
```

## 实践建议
1. **优先使用 DataSet**: 对于处理多单据关联、分录数据计算等场景，`DataSet` 性能远超 `List<DynamicObject>`。
2. **延迟加载**: 在 `queryDataSet` 时，仅传入必要的 `selectFields`，严禁使用 `*`。
3. **QFilter 优化**: 尽量在查询阶段完成数据过滤，减少传输到内存中的数据量。
4. **流关闭**: 使用 `try-with-resources` 确保 DataSet 被正确关闭。

## 常见坑位
1. **泄露隐患**: `DataSet` 必须在 `finally` 中或使用 `try-with-resources` 调用 `close()`，否则会造成内存溢出。
2. **空指针问题**: `AlgoUtils` 在执行计算时，如果字段值为空，应先通过 `nullToZero` 转换。
3. **查询频率**: 严禁在插件的生命周期内高频执行查询，应优先使用已加载到界面模型（Model）中的数据。
4. **SQL 注入**: 使用原生 SQL 时，务必使用参数化查询而非字符串拼接。
