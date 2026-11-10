package kd.cd.common.snippets;

import kd.bos.algo.Algo;
import kd.bos.algo.CustomAggFunction;
import kd.bos.algo.DataSet;
import kd.bos.algo.DataSetBuilder;
import kd.bos.algo.DataType;
import kd.bos.algo.Field;
import kd.bos.algo.Row;
import kd.bos.algo.RowMeta;
import kd.bos.algo.RowMetaFactory;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.entity.LocaleString;
import kd.bos.db.DB;
import kd.bos.db.DBRoute;
import kd.bos.entity.report.AbstractReportColumn;
import kd.bos.entity.report.AbstractReportListDataPlugin;
import kd.bos.entity.report.FilterInfo;
import kd.bos.entity.report.ReportColumn;
import kd.bos.entity.report.ReportQueryParam;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.core.util.BigDecimalUtils;
import kd.cd.core.util.CollectionUtils;
import kd.cd.common.util.AlgoUtils;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.common.util.SelectFieldBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报表取数插件示例 + DataSet Cookbook。
 * <p>
 * 适用插件：报表取数插件
 * 优先封装：AlgoUtils、SelectFieldBuilder、DynamicObjectUtils
 * 原生兜底：AbstractReportListDataPlugin、DataSet、QueryServiceHelper、DB
 * 相关 lint 规则：RESOURCE-004、STYLE-015
 * <p>
 * 使用场景：
 * 1. query() 入口：FilterInfo 获取条件 → QFilter 构建 → 多数据源查询 → DataSet 处理；
 * 2. getColumns() 动态定义报表列；
 * 3. DataSet 全场景 Cookbook：select / filter / groupBy / join / union /
 *    addField / orderBy / map / copy / 互转 / SQL / RowMeta。
 * <p>
 * <b>注意：DataSet 被迭代器访问或 for 循环遍历后会自动关闭，关闭后无法再做任何数据集操作。</b>
 * 如果迭代后还需要继续操作该 DataSet，必须在迭代前先调用 {@code copy()}。
 */
public class SampleReportListDataPlugin extends AbstractReportListDataPlugin {

    // ==================== 常量定义 ====================
    /** 业务实体标识（替换为实际的单据标识） */
    private static final String ENTITY_BILL_A = "kdcd_sample_bill_a";
    private static final String ENTITY_BILL_B = "kdcd_sample_bill_b";

    // ==================== 一、动态列定义（可选） ====================
    /**
     * 动态定义报表列。
     * 适用场景：列不固定（如根据查询条件动态增减列）、需要设置超链接、特殊列类型等。
     * 如果报表列已在设计器中配置好，则无需覆写此方法。
     */
    @Override
    public List<AbstractReportColumn> getColumns(List<AbstractReportColumn> columns) throws Throwable {
        // 方式一：通过二维数组批量定义列（推荐，简洁）
        String[][] columnDefs = {
                // {字段标识, 列标题, 列宽度}
                {"kdcd_order",       "序号",     "50"},
                {"kdcd_item_name",   "项目名称", "150"},
                {"kdcd_budget_amt",  "预算金额", "120"},
                {"kdcd_actual_amt",  "实际金额", "120"},
                {"kdcd_diff_amt",    "差异金额", "120"},
        };

        for (String[] def : columnDefs) {
            ReportColumn column = new ReportColumn();
            column.setFieldKey(def[0]);
            column.setCaption(new LocaleString(def[1]));
            column.setWidth(new LocaleString(def[2]));
            // 字段类型：TYPE_TEXT / TYPE_AMOUNT / TYPE_DECIMAL / TYPE_INTEGER / TYPE_DATE
            column.setFieldType(ReportColumn.TYPE_TEXT);
            // 如需设置超链接
            // column.setHyperlink(true);
            columns.add(column);
        }

        return columns;
    }

    // ==================== 二、核心取数方法 ====================
    /**
     * 报表取数入口。
     * 整体流程：获取过滤条件 → 构建 QFilter → 查询多个数据源 → DataSet 处理 → 返回结果
     */
    @Override
    public DataSet query(ReportQueryParam reportQueryParam, Object o) throws Throwable {
        // ---------- Step 1: 获取查询条件 ----------
        FilterInfo filterInfo = reportQueryParam.getFilter();

        // 获取单个基础资料（组织）
        DynamicObject org = filterInfo.getDynamicObject("kdcd_org");
        // 前置校验已在 FormPlugin.verifyQuery() 中完成，此处做安全兵底
        if (org == null) {
            return null;
        }
        long orgId = org.getLong("id");

        // 获取多选基础资料（项目列表）
        DynamicObjectCollection projectCollection = filterInfo.getDynamicObjectCollection("kdcd_project");

        // 获取日期
        Date endDate = filterInfo.getDate("kdcd_date");
        // 前置校验已在 FormPlugin.verifyQuery() 中完成，此处做安全兵底
        if (endDate == null) {
            return null;
        }

        // ---------- Step 2: 构建过滤条件 ----------
        // 单据A的过滤条件（已审核）
        QFilter filterA = new QFilter("billstatus", QCP.equals, "C");
        filterA.and("org.id", QCP.equals, orgId);
        filterA.and("bizdate", QCP.less_equals, endDate);

        // 单据B的过滤条件（已提交 + 已审核）
        QFilter filterB = new QFilter("billstatus", QCP.in, new String[]{"B", "C"});
        filterB.and("org.id", QCP.equals, orgId);
        filterB.and("bizdate", QCP.less_equals, endDate);

        // 项目过滤（如果选择了项目）
        if (CollectionUtils.isNotEmpty(projectCollection)) {
            Set<Long> projectIds = projectCollection.stream()
                    .map(p -> p.getLong("id"))
                    .collect(Collectors.toSet());
            filterA.and("kdcd_project.id", QCP.in, projectIds);
            filterB.and("kdcd_project.id", QCP.in, projectIds);
        }

        // ---------- Step 3: 查询数据源 ----------
        // 方式一：通过 QueryServiceHelper.queryDataSet 查询实体（推荐）
        // 参数：(调用者标识, 实体标识, 查询字段, QFilter数组, 排序)
        // 使用 SelectFieldBuilder 构建查询字段（支持表达式别名，比字符串拼接更清晰）
        String selectFieldsA = new SelectFieldBuilder()
                .appendAll("id", "billno", "kdcd_project", "billstatus")
                .append("kdcd_project.name", "project_name")
                .append("kdcd_amount", "budget_amt")
                .toString();
        DataSet dataSetA = QueryServiceHelper.queryDataSet(
                getClass().getName(), ENTITY_BILL_A, selectFieldsA,
                new QFilter[]{filterA}, null);

        String selectFieldsB = new SelectFieldBuilder()
                .appendAll("id", "kdcd_project")
                .append("entryentity.amount", "actual_amt")
                .toString();
        DataSet dataSetB = QueryServiceHelper.queryDataSet(
                getClass().getName(), ENTITY_BILL_B, selectFieldsB,
                new QFilter[]{filterB}, null);

        // ---------- Step 4: DataSet 数据处理（详见下方 DataSet Cookbook） ----------

        // 4.1 分组聚合：按项目汇总实际金额
        DataSet actualSummary = dataSetB
                .groupBy(new String[]{"kdcd_project"})
                .sum("actual_amt")
                .finish();

        // 4.2 关联查询：预算数据 LEFT JOIN 实际数据
        DataSet joinResult = dataSetA
                .leftJoin(actualSummary)
                .on("kdcd_project", "kdcd_project")
                .select("kdcd_project", "project_name", "budget_amt", "actual_amt")
                .finish();

        // 4.3 添加计算字段：差异金额 = 预算 - 实际
        DataSet withDiff = joinResult.addField("budget_amt - actual_amt", "diff_amt");

        // 4.4 过滤：只保留有差异的数据
        // DataSet filtered = withDiff.filter("diff_amt != 0");

        // 4.5 排序
        DataSet sorted = withDiff.orderBy(new String[]{"project_name"});

        // 4.6 字段重命名为报表列标识
        DataSet result = sorted.select(
                "project_name as kdcd_item_name",
                "budget_amt as kdcd_budget_amt",
                "actual_amt as kdcd_actual_amt",
                "diff_amt as kdcd_diff_amt"
        );

        // ---------- Step 5: 可选 —— 添加固定汇总行 ----------
        DataSet totalRow = buildTotalRow(result.copy());
        DataSet finalResult = totalRow.union(result);

        return finalResult;
    }

    // ==================== 三、辅助方法 ====================

    /**
     * 构建汇总行（手动创建 DataSet）。
     * 通过 AlgoUtils.newDataSet 创建自定义 DataSet。
     */
    private DataSet buildTotalRow(DataSet sourceData) {
        // 先汇总（注意：for 循环结束后 sourceData 会自动关闭，所以调用方传入的是 result.copy()）
        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        for (Row row : sourceData) {
            totalBudget = BigDecimalUtils.add(totalBudget, BigDecimalUtils.nullToZero(row.getBigDecimal("kdcd_budget_amt")));
            totalActual = BigDecimalUtils.add(totalActual, BigDecimalUtils.nullToZero(row.getBigDecimal("kdcd_actual_amt")));
        }

        // 构建行数据
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{
                "合计",                           // kdcd_item_name
                totalBudget,                       // kdcd_budget_amt
                totalActual,                       // kdcd_actual_amt
                BigDecimalUtils.subtract(totalBudget, totalActual)  // kdcd_diff_amt
        });

        // 使用 AlgoUtils.newDataSet 创建本地 DataSet（替代 CollectionInput + Algo.create 模式）
        return AlgoUtils.newDataSet(
                new String[]{"kdcd_item_name", "kdcd_budget_amt", "kdcd_actual_amt", "kdcd_diff_amt"},
                new DataType[]{DataType.StringType, DataType.BigDecimalType, DataType.BigDecimalType, DataType.BigDecimalType},
                rows
        );
    }

    /**
     * 使用 DataSetBuilder 手动构建 DataSet（另一种方式）。
     * 适用场景：需要逐行追加数据时。
     */
    private DataSet buildDataSetWithBuilder() {
        Field[] fields = new Field[]{
                new Field("name", DataType.StringType),
                new Field("amount", DataType.BigDecimalType),
        };
        RowMeta rowMeta = new RowMeta(fields);
        DataSetBuilder builder = Algo.create(getClass().getName()).createDataSetBuilder(rowMeta);

        // 逐行追加
        builder.append(new Object[]{"行1", new BigDecimal("100.00")});
        builder.append(new Object[]{"行2", new BigDecimal("200.00")});

        return builder.build();
    }

    // ===================================================================
    //  DataSet 数据处理 Cookbook —— 各种场景用法示例
    //  以下方法仅作为参考示例
    // ===================================================================

    // -------------------- 1. select 字段选择与表达式 --------------------
    private void selectExamples(DataSet ds) {
        // 1.1 基本字段选择
        DataSet ds1 = ds.select("id", "billno", "kdcd_amount");

        // 1.2 字段重命名（as 别名）
        DataSet ds2 = ds.select("kdcd_project.name as project_name", "kdcd_amount as amt");

        // 1.3 表达式计算新字段
        DataSet ds3 = ds.select(
                "billno",
                "kdcd_amount * 1.13 as amount_with_tax",     // 乘以税率
                "'固定值' as kdcd_type"                       // 常量字段
        );

        // 1.4 字段拼接（字符串拼接用 +）
        DataSet ds4 = ds.select(
                "\"      \" + accountNumber + ' ' + accname as kdcd_budget_item",  // 缩进 + 编码 + 名称
                "kdcd_init_budget",
                "kdcd_actual_amt"
        );

        // 1.5 select 保留所有原字段 + 追加新字段（用 addField）
        DataSet ds5 = ds.addField("budget_amt - actual_amt", "diff_amt")
                        .addField("5", "sort_order");   // 添加常量排序字段
    }

    // -------------------- 2. filter 条件过滤 --------------------
    private void filterExamples(DataSet ds) {
        // 2.1 字符串表达式过滤（类SQL语法）
        DataSet ds1 = ds.filter("billstatus = 'C'");                          // 等于
        DataSet ds2 = ds.filter("kdcd_amount > 0 and kdcd_amount < 10000");  // 范围
        DataSet ds3 = ds.filter("kdcd_version_number = 1.0");                // 数值
        DataSet ds4 = ds.filter("kdcd_type = 'er_expenseitemedit'");         // 字符串
        DataSet ds5 = ds.filter("accountNumber != null");                     // 非空
        DataSet ds6 = ds.filter("kdcd_item_sort != 0");                      // 非零
        DataSet ds7 = ds.filter("billstatus = 'C' and kdcd_version_number = 1.0"); // 组合条件

        // 2.2 过滤后保留有差异的行 —— 预警报表场景
        DataSet ds8 = ds.filter("kdcd_actual_amt > kdcd_adj_budget"); // 实际发生数 > 调整后预算

        // 2.3 null 值过滤
        DataSet ds9 = ds.filter("predictdepre != null and predictdepre != 0");

        // 2.4 自定义过滤逻辑（使用 AlgoUtils.filter 简化 FilterFunction 匿名类）
        List<String> targetIds = new ArrayList<>(); // 假设已构建
        targetIds.add("id_001");
        targetIds.add("id_002");
        DataSet ds10 = AlgoUtils.filter(ds, row -> {
            String value = row.getString("assgrp_value");
            if (value != null) {
                for (String targetId : targetIds) {
                    if (value.contains(targetId)) {
                        return true; // 包含目标ID则保留
                    }
                }
            }
            return false; // 否则过滤掉
        });
    }

    // -------------------- 3. groupBy 分组聚合 --------------------
    private void groupByExamples(DataSet ds) {
        // 3.1 单字段分组 + 单个求和
        DataSet ds1 = ds.groupBy(new String[]{"kdcd_project"})
                .sum("kdcd_amount")
                .finish();

        // 3.2 多字段分组 + 多个求和
        DataSet ds2 = ds.groupBy(new String[]{"kdcd_expense", "kdcd_expense_name", "kdcd_cost", "kdcd_cost_name"})
                .sum("kdcd_init_budget")
                .sum("kdcd_adj_budget")
                .sum("kdcd_actual_amt")
                .finish();

        // 3.3 全局聚合（不分组，求总计）—— 空数组表示不分组
        DataSet ds3 = ds.groupBy()
                .sum("kdcd_init_budget")
                .sum("kdcd_adj_budget")
                .sum("kdcd_actual_amt")
                .finish();

        // 3.4 分组 + 求和 + 指定别名（给聚合结果重命名）
        // sum("原始值", "别名") —— 当需要把 0 作为初始值求和时
        DataSet ds4 = ds.groupBy(new String[]{"kdcd_project"})
                .sum("0", "kdcd_init_budget")    // 用0填充，别名为 kdcd_init_budget
                .sum("kdcd_adj_budget")           // 保留原字段名
                .sum("kdcd_actual_amt")
                .finish();

        // 3.5 分组 + 自定义聚合函数（agg）
        // agg(自定义聚合函数, 参数字段, 输出别名)
        DataSet ds5 = ds.groupBy(new String[]{"kdcd_project"})
                .agg(new GroupMaxFunction(), "kdcd_version_number", "max_version")
                .finish();
    }

    // -------------------- 4. join 数据集关联 --------------------
    private void joinExamples(DataSet dsA, DataSet dsB) {
        // 4.1 LEFT JOIN + 单条件关联
        DataSet ds1 = dsA.leftJoin(dsB)
                .on("kdcd_project", "kdcd_project")
                .select("kdcd_project", "project_name", "budget_amt", "actual_amt")
                .finish();

        // 4.2 LEFT JOIN + 多条件关联（链式 on）
        DataSet ds2 = dsA.leftJoin(dsB)
                .on("kdcd_project", "kdcd_project")
                .on("max_version", "kdcd_version_number")   // 第二个关联条件
                .select("kdcd_type", "kdcd_expense", "kdcd_init_budget", "kdcd_adj_budget")
                .finish();

        // 4.3 INNER JOIN（join 而非 leftJoin）
        DataSet ds3 = dsA.join(dsB)
                .on("kdcd_project", "kdcd_project")
                .on("max_version", "kdcd_version_number")
                .select("kdcd_budget_item", "kdcd_init_budget", "kdcd_adj_budget", "kdcd_actual_amt")
                .finish();

        // 4.4 JOIN 选择两边的字段：左表字段 + 右表字段
        DataSet ds4 = dsA.leftJoin(dsB)
                .on("realcard", "realcardid")
                .on("period", "period")
                .select(AlgoUtils.fieldsOf(dsA),                        // 左表所有字段
                        new String[]{"kdcd_zhejiu", "kdcd_kuanian"})    // 右表指定字段
                .finish();

        // 4.5 JOIN 后再接其他操作
        DataSet ds5 = dsA.leftJoin(dsB)
                .on("kdcd_measureperiod", "kdcd_measureperiod")
                .on("kdcd_contract.id", "kdcd_contract.id")
                .select("kdcd_contract.id", "kdcd_sum", "kdcd_measureperiodnum")
                .finish()
                .distinct();  // JOIN 后去重
    }

    // -------------------- 5. union 数据集合并 --------------------
    private void unionExamples(DataSet ds1, DataSet ds2, DataSet ds3, DataSet ds4, DataSet ds5) {
        // 5.1 两个 DataSet 合并
        DataSet union1 = ds1.union(ds2);

        // 5.2 多个 DataSet 一次性合并（可变参数）
        DataSet union2 = ds1.union(ds2, ds3, ds4, ds5);

        // 5.3 链式合并
        DataSet union3 = ds1.union(ds2).copy()   // 注意: union 后需 copy() 才能再次 union
                .union(ds3).copy()
                .union(ds4);

        // 5.4 合并后再聚合（先 union 再 groupBy）
        DataSet union4 = ds1.union(ds2)
                .groupBy(new String[]{"kdcd_budget_item"})
                .sum("kdcd_init_budget")
                .sum("kdcd_adj_budget")
                .sum("kdcd_actual_amt")
                .finish();
    }

    // -------------------- 6. addField / addNullField 添加字段 --------------------
    private void addFieldExamples(DataSet ds) {
        // 6.1 添加常量字段（排序用）
        DataSet ds1 = ds.addField("5", "kdcd_sort");

        // 6.2 添加表达式计算字段
        DataSet ds2 = ds.addField("budget_amt - actual_amt", "diff_amt");

        // 6.3 添加字符串常量作为来源标记
        DataSet ds3 = ds.addField("'计量明细'", "kdcd_source");

        // 6.4 添加空值字段（当某些分录缺少某字段时补 null）
        DataSet ds4 = ds.addNullField("kdcd_item_sort")
                        .addNullField("kdcd_item_content")
                        .addField("'预付款'", "kdcd_source");

        // 6.5 链式添加多个字段
        DataSet ds5 = ds.addField("1", "summarytype")
                        .addNullField("currency");
    }

    // -------------------- 7. orderBy / distinct / removeFields --------------------
    private void orderDistinctExamples(DataSet ds) {
        // 7.1 单字段排序
        DataSet ds1 = ds.orderBy(new String[]{"project_name"});

        // 7.2 多字段排序
        DataSet ds2 = ds.orderBy(new String[]{"kdcd_settleorg", "kdcd_billno", "kdcd_currentaccount", "kdcd_source"});

        // 7.3 去重
        DataSet ds3 = ds.distinct();

        // 7.4 排序 + 去重组合
        DataSet ds4 = ds.distinct().orderBy(new String[]{"kdcd_sort"});

        // 7.5 移除不需要的字段
        DataSet ds5 = ds.removeFields("kdcd_measureperiodnum");
    }

    // -------------------- 8. map 自定义行映射 --------------------
    private void mapExamples(DataSet ds) {
        // 自定义 MapFunction 实现行级转换（如为报表行添加序号）
        //
        // 使用方式：
        //   CustomRowMapper mapper = new CustomRowMapper(ds.getRowMeta());
        //   DataSet result = ds.map(mapper);
        //
        // MapFunction 实现模板：
        //   public class CustomRowMapper extends MapFunction {
        //       private RowMeta rowMeta;
        //       public CustomRowMapper(RowMeta sourceMeta) {
        //           this.rowMeta = new RowMeta(sourceMeta.getFields());
        //       }
        //       @Override
        //       public Object[] map(Row row) {
        //           Object[] newRow = new Object[rowMeta.getFieldCount()];
        //           for (int i = 0; i < rowMeta.getFieldCount(); i++) {
        //               newRow[i] = row.get(i);
        //           }
        //           newRow[0] = "自定义值";  // 修改指定字段
        //           return newRow;
        //       }
        //       @Override
        //       public RowMeta getResultRowMeta() { return rowMeta; }
        //   }
    }

    // -------------------- 9. copy 克隆与复用 --------------------
    private void copyExamples(DataSet ds) {
        // 重要：DataSet 被迭代器访问或循环遍历后会自动关闭，关闭后无法再做任何数据集操作！
        // 典型场景：
        //   for (Row row : ds) { ... }   ← 循环结束后 ds 已关闭
        //   ds.select(...) / ds.filter(...) / ds.groupBy(...)  ← 全部报错
        // 因此：如果迭代后还需要继续操作，必须在迭代前先 copy()。
        // 同理：一个 DataSet 做多次不同处理时，也必须先 copy() 再分别操作。

        // 9.1 同一数据集做不同过滤
        DataSet version1 = ds.copy().filter("billstatus = 'C' and kdcd_version_number = 1.0");  // 第一版
        DataSet latestVersion = ds.copy().groupBy(new String[]{"kdcd_project"})               // 最新版
                .agg(new GroupMaxFunction(), "kdcd_version_number", "maxversion")
                .finish();

        // 9.2 copy 后做不同聚合
        DataSet totalDirect = ds.copy().groupBy().sum("kdcd_init_budget").sum("kdcd_adj_budget").finish();
        DataSet detailRows = ds.copy().select("kdcd_budget_item", "kdcd_init_budget", "kdcd_adj_budget");

        // 9.3 union 后需要 copy 才能继续 union
        DataSet result = ds.copy().union(version1).copy().union(latestVersion);
    }

    // -------------------- 10. DataSet ↔ DynamicObject 互转 --------------------
    private void conversionExamples(DataSet ds) {
        // 10.1 DataSet → DynamicObjectCollection（使用 DynamicObjectUtils.fromDataSet 封装）
        DynamicObjectCollection dynColl = DynamicObjectUtils.fromDataSet(ds);
        for (DynamicObject row : dynColl) {
            String number = row.getString("accountNumber");
            BigDecimal amount = row.getBigDecimal("kdcd_actual_amt");
            // ... 做复杂的对象级操作
        }

        // 10.2 获取 DataSet 中某列的所有值 —— 使用 AlgoUtils 一行搞定
        Set<Long> idSet = AlgoUtils.setOf(ds.copy(), "id");       // 提取为 Set
        List<Long> idList = AlgoUtils.listOf(ds.copy(), "id");    // 提取为 List

        // 10.3 DataSet Stream 支持 —— 使用 AlgoUtils.stream()
        Set<String> numberSet = AlgoUtils.stream(ds.copy())
                .map(row -> row.getString("number"))
                .collect(Collectors.toSet());

        // 10.4 DataSet 单列求和 —— 使用 AlgoUtils.sumOf()
        BigDecimal totalAmt = AlgoUtils.sumOf(ds.copy(), "kdcd_amount");

        // 10.5 DynamicObjectCollection → DataSet
        DynamicObjectCollection sourceColl = new DynamicObjectCollection();
        DataSet fromColl = DynamicObjectUtils.toDataSet(sourceColl);              // 全字段
        DataSet fromCollPartial = DynamicObjectUtils.toDataSet(sourceColl, "id", "name"); // 选择字段
    }

    // -------------------- 11. 原生 SQL 查询 --------------------
    private void rawSqlExamples() {
        // 11.1 通过 DB.queryDataSet 执行原生SQL（复杂跨表场景）
        String sql = "/*dialect*/ SELECT fnumber, fname FROM t_meta_formdesign_l WHERE flocaleid = 'zh_CN'";
        DataSet ds1 = DB.queryDataSet(getClass().getName(), DBRoute.of("sys.meta"), sql);

        // 11.2 带参数的SQL（使用 SqlBuilder）
        // SqlBuilder sqlBuilder = new SqlBuilder();
        // sqlBuilder.append("select fentryid, frealcardid from t_fa_depredetailentry where forgid = ?", orgId);
        // DataSet ds2 = DB.queryDataSet(getClass().getName(), DBRoute.of("fa"), sqlBuilder);
    }

    // -------------------- 12. getRowMeta 元数据操作 --------------------
    private void rowMetaExamples(DataSet ds) {
        // 12.1 获取 DataSet 的所有字段名（使用 AlgoUtils.fieldsOf 封装）
        String[] fieldNames = AlgoUtils.fieldsOf(ds);

        // 12.2 获取字段详细信息
        RowMeta rowMeta = ds.getRowMeta();
        Field[] fields = rowMeta.getFields();
        for (Field field : fields) {
            String name = field.getName();       // 字段名
            String alias = field.getAlias();     // 别名
            DataType type = field.getDataType(); // 数据类型
        }

        // 12.3 基于现有 DataSet 的 RowMeta 动态添加空字段
        DataSet sumDs = ds.copy().groupBy().sum("monthdepre").finish();
        Set<String> allFields = new java.util.LinkedHashSet<>();
        for (Field field : ds.getRowMeta().getFields()) {
            allFields.add(field.getAlias().toLowerCase());
        }
        for (String field : allFields) {
            if (!"monthdepre".equalsIgnoreCase(field)) {
                sumDs = sumDs.addNullField(field);  // 汇总行补全其他字段为null
            }
        }
        sumDs = sumDs.select(allFields.toArray(new String[0]));  // 对齐字段顺序
    }

    // ===================================================================
    //  自定义聚合函数示例 —— GroupMaxFunction
    // ===================================================================
    /**
     * 自定义聚合函数：求分组内的最大值。
     * <pre>
     * 使用方式：
     *   DataSet maxResult = ds.groupBy(new String[]{"kdcd_project"})
     *       .agg(new GroupMaxFunction(), "kdcd_version_number", "max_version")
     *       .finish();
     * </pre>
     */
    static class GroupMaxFunction extends CustomAggFunction<BigDecimal> {
        public GroupMaxFunction() {
            super("group_max", DataType.BigDecimalType);
        }

        /** 初始聚合值 */
        @Override
        public BigDecimal newAggValue() {
            return BigDecimal.ZERO;
        }

        /** 遍历每一行，取当前值与已有最大值中较大的 */
        @Override
        public BigDecimal addValue(BigDecimal oldValue, Object newValue) {
            BigDecimal current = new BigDecimal(newValue.toString());
            return oldValue.max(current);
        }

        /** 分批处理时，合并不同批次的结果 */
        @Override
        public BigDecimal combineAggValue(BigDecimal v1, BigDecimal v2) {
            return v1.max(v2);
        }

        @Override
        public Object getResult(BigDecimal result) {
            return result;
        }
    }
}
