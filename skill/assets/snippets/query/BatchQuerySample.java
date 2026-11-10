/**
 * 批量查询样本。
 * <p>
 * 适用插件：表单插件、操作插件、服务层
 * 优先封装：DynamicObjectUtils、AlgoUtils
 * 原生兜底：BusinessDataServiceHelper.load(...)、QueryServiceHelper.query(...)、QFilter、DataSet
 * 相关 lint 规则：STYLE-015、STYLE-017、STYLE-010
 * <p>
 * 使用场景：
 * 1. 先按关联键分组，再一次性批量查询；
 * 2. 查询结果先转本地 Map，再做校验、反写或恢复；
 * 3. 表单插件、操作插件、服务层都可以直接复用这里的查询分组思路。
 * 4. 注意：QueryServiceHelper.query(...) / QueryServiceHelper.queryDataSet(...) 查出来的数据默认用于读取、分组、聚合，不要直接当成可更新实体回写保存。
 */
package kd.cd.common.snippets.query;

import kd.bos.algo.DataSet;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.common.util.AlgoUtils;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.util.SelectFieldBuilder;
import kd.cd.core.util.BigDecimalUtils;
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BatchQuerySample {
    private static final String FIELD_ROW_SEQ = "seq";
    private static final String FIELD_REL_BILL_NO = "relbillno";
    private static final String FIELD_REL_ENTRY_ID = "relentryid";
    private static final String FIELD_AMT = "amount";
    private static final String REL_FORM_ID = "related_form";
    private static final String REL_ENTRY_KEY = "relentryentity";
    private static final String REL_PLAN_AMT = "planamount";
    private static final String REL_LOCKED_AMT = "lockedamount";

    private static final String FIELD_TARGET_BILL_NO = "targetbillno";
    private static final String SOURCE_SUB_ENTRY_KEY = "subentryentity";
    private static final String FIELD_ITEM = "item";
    private static final String FIELD_ACCOUNT = "account";
    private static final String TARGET_FORM_ID = "target_form";
    private static final String TARGET_ENTRY_KEY = "targetentryentity";

    private static final String SOURCE_FORM_ID = "source_form";
    private static final String FIELD_BILL_NO = "billno";
    private static final String FIELD_DIMENSION = "dimension";
    private static final String FIELD_VALUE = "value";
    private static final String MID_FORM_ID = "latest_value_mid";
    private static final String FIELD_MID_DIMENSION = "dimensionref";
    private static final String FIELD_MID_VALUE = "latestvalue";

    private BatchQuerySample() {
    }

    /**
     * 范式一：按关联单号分组，一次性加载关联单，本地映射后做校验。
     */
    public static List<String> validateRelationRows(List<DynamicObject> entryRows) {
        Map<String, List<DynamicObject>> rowsByBillNo = entryRows.stream()
                .filter(row -> CharSequenceUtils.isNotBlank(row.getString(FIELD_REL_BILL_NO)))
                .collect(Collectors.groupingBy(row -> row.getString(FIELD_REL_BILL_NO)));
        if (CollectionUtils.isEmpty(rowsByBillNo)) {
            return Collections.emptyList();
        }

        Map<String, DynamicObject> relatedBillMap = loadRelatedBillMap(rowsByBillNo.keySet());
        List<String> errors = new ArrayList<>(32);
        for (Map.Entry<String, List<DynamicObject>> entry : rowsByBillNo.entrySet()) {
            DynamicObject relatedBill = relatedBillMap.get(entry.getKey());
            if (relatedBill == null) {
                continue;
            }
            Map<String, DynamicObject> relatedEntryMap = relatedBill.getDynamicObjectCollection(REL_ENTRY_KEY).stream()
                    .collect(Collectors.toMap(
                            row -> row.getString("id"),
                            row -> row,
                            (left, right) -> left
                    ));
            for (DynamicObject row : entry.getValue()) {
                DynamicObject relatedEntry = relatedEntryMap.get(row.getString(FIELD_REL_ENTRY_ID));
                if (relatedEntry == null) {
                    continue;
                }
                BigDecimal availableAmt = BigDecimalUtils.subtract(
                        BigDecimalUtils.nullToZero(relatedEntry.getBigDecimal(REL_PLAN_AMT)),
                        BigDecimalUtils.nullToZero(relatedEntry.getBigDecimal(REL_LOCKED_AMT))
                );
                BigDecimal currentAmt = BigDecimalUtils.nullToZero(row.getBigDecimal(FIELD_AMT));
                if (BigDecimalUtils.largeThan(currentAmt, availableAmt)) {
                    errors.add(String.format(
                            "row %s amount %s > available %s",
                            row.get(FIELD_ROW_SEQ),
                            BigDecimalUtils.toPlainString(currentAmt),
                            BigDecimalUtils.toPlainString(availableAmt)
                    ));
                }
            }
        }
        return errors;
    }

    /**
     * 范式二：按目标单号聚合，一次性加载目标单，本地重建子分录后返回待保存单据。
     */
    public static DynamicObject[] buildTargetBillsForSave(List<DynamicObject> sourceRows) {
        if (CollectionUtils.isEmpty(sourceRows)) {
            return new DynamicObject[0];
        }

        List<DynamicObject> targetRows = sourceRows.stream()
                .filter(row -> CharSequenceUtils.isNotBlank(row.getString(FIELD_TARGET_BILL_NO)))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(targetRows)) {
            return new DynamicObject[0];
        }

        Set<String> targetBillNos = DynamicObjectUtils.setOf(targetRows, FIELD_TARGET_BILL_NO);
        Map<String, List<DynamicObject>> rowsByTargetBillNo = targetRows.stream()
                .collect(Collectors.groupingBy(row -> row.getString(FIELD_TARGET_BILL_NO)));
        Map<String, DynamicObject> targetBillMap = loadTargetBillMap(targetBillNos);

        List<DynamicObject> needSaveBills = new ArrayList<>(32);
        for (Map.Entry<String, List<DynamicObject>> entry : rowsByTargetBillNo.entrySet()) {
            DynamicObject targetBill = targetBillMap.get(entry.getKey());
            if (targetBill == null) {
                continue;
            }
            rewriteTargetEntries(targetBill.getDynamicObjectCollection(TARGET_ENTRY_KEY), entry.getValue());
            needSaveBills.add(targetBill);
        }
        return needSaveBills.toArray(new DynamicObject[0]);
    }

    /**
     * 范式三-A：按当前数据直接同步中间表，返回待 update/save 的数据。
     */
    public static DynamicObject[] buildMidBillsForSync(List<DynamicObject> currentRows) {
        if (CollectionUtils.isEmpty(currentRows)) {
            return new DynamicObject[0];
        }

        Map<String, DynamicObject> currentRowMap = new HashMap<>(currentRows.size());
        for (DynamicObject row : currentRows) {
            Object dimensionId = row.get(FIELD_DIMENSION + ".id");
            if (EntityUtils.isNotEmptyPk(dimensionId)) {
                currentRowMap.put(keyOf(dimensionId), row);
            }
        }
        if (CollectionUtils.isEmpty(currentRowMap)) {
            return new DynamicObject[0];
        }

        List<DynamicObject> currentRowList = new ArrayList<>(currentRowMap.size());
        currentRowList.addAll(currentRowMap.values());
        Set<Object> dimensionIds = DynamicObjectUtils.setOf(currentRowList, FIELD_DIMENSION + ".id");
        Map<String, DynamicObject> midBillMap = loadMidBillMap(dimensionIds);
        List<DynamicObject> result = new ArrayList<>(currentRowMap.size());

        for (Map.Entry<String, DynamicObject> entry : currentRowMap.entrySet()) {
            DynamicObject row = entry.getValue();
            DynamicObject midBill = midBillMap.get(entry.getKey());
            if (midBill == null) {
                midBill = DynamicObjectUtils.newDynamicObject(MID_FORM_ID);
                midBill.set("number", row.getString(FIELD_DIMENSION + ".masterid.number"));
                midBill.set(FIELD_MID_DIMENSION + "_id", row.get(FIELD_DIMENSION + ".id"));
                midBill.set("enable", "1");
                midBill.set("status", "C");
            }
            applyLatestValue(midBill, row.getString(FIELD_BILL_NO), row.getBigDecimal(FIELD_VALUE));
            result.add(midBill);
        }
        return result.toArray(new DynamicObject[0]);
    }

    /**
     * 范式三-B：按维度集合一次性查询候选记录，在内存里取每组最近一条，再回填中间表。
     */
    public static DynamicObject[] buildMidBillsForRecover(List<DynamicObject> affectedRows) {
        if (CollectionUtils.isEmpty(affectedRows)) {
            return new DynamicObject[0];
        }

        Set<Object> dimensionIds = DynamicObjectUtils.setOf(affectedRows, FIELD_DIMENSION + ".id");
        Map<String, DynamicObject> midBillMap = Arrays.stream(loadMidBillsForUpdateByQuery(dimensionIds))
                .collect(Collectors.toMap(
                        bill -> keyOf(bill.get(FIELD_MID_DIMENSION + ".id")),
                        bill -> bill,
                        (left, right) -> left
                ));
        Map<String, LatestValue> latestValueMap = queryLatestValueMap(dimensionIds);
        List<DynamicObject> updateList = new ArrayList<>(midBillMap.size());

        for (DynamicObject midBill : midBillMap.values()) {
            LatestValue latestValue = latestValueMap.get(keyOf(midBill.get(FIELD_MID_DIMENSION + ".id")));
            applyLatestValue(
                    midBill,
                    latestValue == null ? "" : latestValue.billNo,
                    latestValue == null ? BigDecimal.ZERO : latestValue.value
            );
            updateList.add(midBill);
        }
        return updateList.toArray(new DynamicObject[0]);
    }

    /**
     * 范式四：用 QueryServiceHelper.query(...) 一次性查出候选明细，再按维度分组或直接取每组第一条。
     * 注意：这里拿到的是扁平查询结果，只适合读取、分组、聚合，不能直接拿去做后续 update/save。
     * 需要更新时，应该回到 BusinessDataServiceHelper.load(...) 重新加载实体包，或 newDynamicObject(...) 后再保存。
     */
    public static Map<String, LatestValue> queryLatestValueMapByQuery(Set<Object> dimensionIds) {
        // QueryServiceHelper.query(...) 返回的是扁平结果集，适合做“批量查 + 分组映射”，不适合直接回写更新。
        DynamicObjectCollection rows = QueryServiceHelper.query(
                SOURCE_FORM_ID,
                new SelectFieldBuilder()
                        .append("entryentity." + FIELD_DIMENSION + ".id", "dimensionId")
                        .append("entryentity." + FIELD_VALUE, "value")
                        .appendAll(FIELD_BILL_NO)
                        .toString(),
                new QFilter("billstatus", QCP.equals, "C")
                        .and("entryentity." + FIELD_DIMENSION + ".id", QCP.in, dimensionIds)
                        .toArray(),
                "auditdate desc," + FIELD_BILL_NO + " desc"
        );
        return rows.stream().collect(Collectors.toMap(
                row -> keyOf(row.get("dimensionId")),
                row -> new LatestValue(row.getString(FIELD_BILL_NO), BigDecimalUtils.nullToZero(row.getBigDecimal("value"))),
                (left, right) -> left
        ));
    }

    /**
     * 范式四-B：如果前一步必须用 query(...) 查范围，后面又要更新，
     * 正确桥接方式是“先 query 出 id，再 load 实体包”，不要直接修改 query 的扁平结果。
     */
    public static DynamicObject[] loadMidBillsForUpdateByQuery(Set<Object> dimensionIds) {
        if (CollectionUtils.isEmpty(dimensionIds)) {
            return new DynamicObject[0];
        }

        DynamicObjectCollection flatRows = QueryServiceHelper.query(
                MID_FORM_ID,
                "id",
                new QFilter(FIELD_MID_DIMENSION + ".id", QCP.in, dimensionIds).toArray()
        );
        Set<Object> ids = DynamicObjectUtils.setOf(flatRows, "id");
        if (CollectionUtils.isEmpty(ids)) {
            return new DynamicObject[0];
        }

        return BusinessDataServiceHelper.load(
                MID_FORM_ID,
                "id,name," + FIELD_MID_DIMENSION + "," + FIELD_MID_VALUE,
                new QFilter("id", QCP.in, ids).toArray()
        );
    }

    private static Map<String, DynamicObject> loadRelatedBillMap(Set<String> relatedBillNos) {
        DynamicObject[] relatedBills = BusinessDataServiceHelper.load(
                REL_FORM_ID,
                "billno,relentryentity.id,relentryentity." + REL_PLAN_AMT + ",relentryentity." + REL_LOCKED_AMT,
                new QFilter("billno", QCP.in, relatedBillNos).toArray()
        );
        return Arrays.stream(relatedBills).collect(Collectors.toMap(
                bill -> bill.getString("billno"),
                bill -> bill,
                (left, right) -> left
        ));
    }

    private static Map<String, DynamicObject> loadTargetBillMap(Set<String> targetBillNos) {
        DynamicObject[] targetBills = BusinessDataServiceHelper.load(
                TARGET_FORM_ID,
                "billno,createtime," + TARGET_ENTRY_KEY + "." + FIELD_ITEM
                        + "," + TARGET_ENTRY_KEY + "." + FIELD_AMT
                        + "," + TARGET_ENTRY_KEY + "." + FIELD_ACCOUNT,
                new QFilter("billno", QCP.in, targetBillNos).toArray(),
                "createtime desc"
        );
        return Arrays.stream(targetBills).collect(Collectors.toMap(
                bill -> bill.getString("billno"),
                bill -> bill,
                (left, right) -> left
        ));
    }

    private static void rewriteTargetEntries(DynamicObjectCollection targetEntries, List<DynamicObject> sourceRows) {
        targetEntries.clear();
        for (DynamicObject sourceRow : sourceRows) {
            for (DynamicObject sourceSubRow : sourceRow.getDynamicObjectCollection(SOURCE_SUB_ENTRY_KEY)) {
                DynamicObject newRow = targetEntries.addNew();
                newRow.set(FIELD_ITEM, sourceSubRow.get(FIELD_ITEM));
                newRow.set(FIELD_AMT, sourceSubRow.get(FIELD_AMT));
                newRow.set(FIELD_ACCOUNT, sourceSubRow.get(FIELD_ACCOUNT));
            }
        }
    }

    /**
     * 关键点：不要在维度循环里 query(..., top 1)，先一次性查出候选记录，再利用排序结果取每组第一条。
     */
    private static Map<String, LatestValue> queryLatestValueMap(Set<Object> dimensionIds) {
        try (DataSet ds = QueryServiceHelper.queryDataSet(
                SOURCE_FORM_ID, SOURCE_FORM_ID,
                new SelectFieldBuilder()
                        .append("entryentity." + FIELD_DIMENSION + ".id", "dimensionId")
                        .appendAll(FIELD_BILL_NO)
                        .append("entryentity." + FIELD_VALUE, "value")
                        .toString(),
                new QFilter("billstatus", QCP.equals, "C")
                        .and("entryentity." + FIELD_DIMENSION + ".id", QCP.in, dimensionIds)
                        .toArray(),
                "auditdate desc," + FIELD_BILL_NO + " desc"
        )) {
            return AlgoUtils.stream(ds).collect(Collectors.toMap(
                    row -> keyOf(row.get("dimensionId")),
                    row -> new LatestValue(row.getString(FIELD_BILL_NO), BigDecimalUtils.nullToZero(row.getBigDecimal("value"))),
                    (left, right) -> left
            ));
        }
    }

    private static Map<String, DynamicObject> loadMidBillMap(Set<?> dimensionIds) {
        DynamicObject[] midBills = BusinessDataServiceHelper.load(
                MID_FORM_ID,
                "name," + FIELD_MID_DIMENSION + "," + FIELD_MID_VALUE,
                new QFilter(FIELD_MID_DIMENSION + ".id", QCP.in, dimensionIds).toArray()
        );
        return Arrays.stream(midBills).collect(Collectors.toMap(
                bill -> keyOf(bill.get(FIELD_MID_DIMENSION + ".id")),
                bill -> bill,
                (left, right) -> left
        ));
    }

    private static void applyLatestValue(DynamicObject midBill, String billNo, BigDecimal value) {
        midBill.set("name", billNo);
        midBill.set(FIELD_MID_VALUE, BigDecimalUtils.nullToZero(value));
    }

    private static String keyOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static class LatestValue {
        private final String billNo;
        private final BigDecimal value;

        private LatestValue(String billNo, BigDecimal value) {
            this.billNo = billNo;
            this.value = value;
        }
    }
}
