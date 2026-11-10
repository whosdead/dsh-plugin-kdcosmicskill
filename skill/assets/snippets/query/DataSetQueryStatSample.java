/**
 * QueryServiceHelper.queryDataSet(...) + DataSet 聚合示例。
 * <p>
 * 适用插件：表单插件、报表插件、服务层
 * 优先封装：QueryServiceHelper、AlgoUtils
 * 原生兜底：DataSet、Row、QFilter
 * 相关 lint 规则：RESOURCE-004、STYLE-015
 * <p>
 * 使用场景：在表单/报表中按条件查询一批单据，
 * 既要拿最新一条记录，也要顺手做金额求和、主键收集。
 * 进阶场景：多 DataSet 按维度 groupBy / leftJoin 后，再做差额统计和动态排序。
 */
package kd.cd.common.snippets.query;

import kd.bos.algo.DataSet;
import kd.bos.algo.Row;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.AlgoUtils;
import kd.cd.common.util.SelectFieldBuilder;
import kd.cd.core.util.BigDecimalUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DataSetQueryStatSample extends AbstractFormPluginExt {
    private static final String PUR_ORDER_FORM_ID = "pm_purorderbill";
    private static final String AP_INVOICE_FORM_ID = "ap_invoice";

    // --- 查询已审核采购订单金额汇总，并取最新一条单据 ---
    public BigDecimal queryAuditedOrderTotal(String supplierNumber, Object orgId) {
        QFilter filter = new QFilter("billstatus", QCP.equals, "C")
                .and(new QFilter("supplier.number", QCP.equals, supplierNumber))
                .and(new QFilter("org.id", QCP.equals, orgId));

        try (DataSet ds = QueryServiceHelper.queryDataSet(PUR_ORDER_FORM_ID, PUR_ORDER_FORM_ID,
                "id,billno,amountandtax", filter.toArray(), "billno desc", 200)) {
            BigDecimal totalAmount = BigDecimalUtils.nullToZero(AlgoUtils.sumOf(ds.copy(), "amountandtax"));
            if (ds.hasNext()) {
                Row latestRow = ds.next();
                getView().showTipNotification(String.format(
                        ResManager.loadKDString("最新单据：%s", "DataSetQueryStatSample_0", "kd-cd-common-snippets"),
                        latestRow.getString("billno")
                ));
            }
            return totalAmount;
        }
    }

    // --- 只收集满足条件的主键集合 ---
    public Set<Object> queryAuditedOrderIds(Object orgId) {
        QFilter filter = new QFilter("billstatus", QCP.equals, "C")
                .and(new QFilter("org.id", QCP.equals, orgId));
        try (DataSet ds = QueryServiceHelper.queryDataSet(
                PUR_ORDER_FORM_ID, PUR_ORDER_FORM_ID,
                "id",
                filter.toArray(), null, -1)
        ) {
            return AlgoUtils.setOf(ds, "id");
        }
    }

    // --- 按供应商汇总订单金额和发票金额，再计算未开票差额 ---
    public BigDecimal queryUninvoicedAmount(Object orgId) {
        QFilter filter = new QFilter("billstatus", QCP.equals, "C")
                .and(new QFilter("org.id", QCP.equals, orgId));
        String orderFields = new SelectFieldBuilder()
                .append("supplier.id", "supplierId")
                .appendAll("amountandtax")
                .toString();
        String invoiceFields = new SelectFieldBuilder()
                .append("receivablessupp.id", "supplierId")
                .append("pricetaxtotal", "invoicedamount")
                .toString();
        try (DataSet orderDs = QueryServiceHelper.queryDataSet(
                PUR_ORDER_FORM_ID, PUR_ORDER_FORM_ID,
                orderFields, filter.toArray(), null, -1
        );
             DataSet invoiceDs = QueryServiceHelper.queryDataSet(
                     AP_INVOICE_FORM_ID, AP_INVOICE_FORM_ID,
                     invoiceFields, filter.toArray(), null, -1
             );
             DataSet invoiceSum = invoiceDs.groupBy(new String[]{"supplierId"}).sum("invoicedamount").finish();
             DataSet joined = orderDs.leftJoin(invoiceSum)
                     .on("supplierId", "supplierId")
                     .select("supplierId", "amountandtax", "invoicedamount")
                     .finish()
        ) {
            BigDecimal gap = BigDecimal.ZERO;
            for (Row row : joined) {
                BigDecimal orderAmount = BigDecimalUtils.nullToZero(row.getBigDecimal("amountandtax"));
                BigDecimal invoicedAmount = BigDecimalUtils.nullToZero(row.getBigDecimal("invoicedamount"));
                gap = BigDecimalUtils.add(gap, BigDecimalUtils.subtract(orderAmount, invoicedAmount));
            }
            return gap;
        }
    }

    // --- 动态排序 + 按需移除中间字段：适合报表首条预览或调试 ---
    public void previewOrderSummary(Object orgId, boolean orderByAmount, boolean keepSupplierField) {
        QFilter filter = new QFilter("billstatus", QCP.equals, "C")
                .and(new QFilter("org.id", QCP.equals, orgId));
        List<String> orderBys = new ArrayList<>(32);
        orderBys.add(orderByAmount ? "amountandtax desc" : "billno desc");
        orderBys.add("billno asc");
        // orderBy 返回派生 DataSet，统一纳入 try-with-resources 确保关闭
        SelectFieldBuilder builder = new SelectFieldBuilder()
                .appendAll("id", "billno", "amountandtax");
        if (keepSupplierField) {
            builder.append("supplier.number", "supplierNumber");
        }
        String fields = builder.toString();
        try (DataSet ds = QueryServiceHelper.queryDataSet(
                PUR_ORDER_FORM_ID, PUR_ORDER_FORM_ID, fields, filter.toArray(), null, -1);
             DataSet result = ds.orderBy(orderBys.toArray(new String[0]))
        ) {
            if (result.hasNext()) {
                Row firstRow = result.next();
                getView().showTipNotification(String.format(
                        ResManager.loadKDString("首条结果：%s", "DataSetQueryStatSample_1", "kd-cd-common-snippets"),
                        firstRow.getString("billno")
                ));
            }
        }
    }
}
