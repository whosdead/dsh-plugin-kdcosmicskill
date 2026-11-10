package kd.cd.common.snippets;

import kd.bos.bill.OperationStatus;
import kd.bos.context.RequestContext;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.entity.report.FilterInfo;
import kd.bos.entity.report.ReportQueryParam;
import kd.bos.form.FormShowParameter;
import kd.bos.form.ShowType;
import kd.bos.report.ReportList;
import kd.bos.form.events.HyperLinkClickEvent;
import kd.bos.form.events.HyperLinkClickListener;
import kd.bos.report.events.SearchEvent;
import kd.bos.report.events.SortAndFilterEvent;
import kd.bos.report.filter.ReportFilter;
import kd.bos.report.filter.SearchListener;
import kd.bos.report.plugin.AbstractReportFormPlugin;
import kd.cd.common.form.FormUtils;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.core.util.BigDecimalUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EventObject;
import java.util.List;

/**
 * 报表界面插件示例 —— 配套 SampleReportListDataPlugin 使用。
 * <p>
 * 适用插件：报表插件
 * 优先封装：FormUtils、ShowParameterUtils
 * 原生兜底：AbstractReportFormPlugin、ReportFilter、HyperLinkClickEvent
 * 相关 lint 规则：STYLE-013
 * <p>
 * 使用场景：
 * 1. afterCreateNewData 设置查询条件默认值（如当前组织）；
 * 2. verifyQuery 查询前校验（showErrorMessage 替代 KDBizException）；
 * 3. registerListener 控制过滤面板不折叠；
 * 4. processRowData 对取数结果做二次加工（百分比计算、格式化）；
 * 5. setSortAndFilter 设置列排序/过滤能力；
 * 6. hyperLinkClick 超链接跳转关联单据。
 */
public class SampleReportFormPlugin extends AbstractReportFormPlugin
        implements HyperLinkClickListener {

    // ==================== 一、初始化默认值 ====================
    /**
     * 设置查询条件默认值。
     * 典型用法：将当前登录用户的组织设置为默认组织。
     */
    @Override
    public void afterCreateNewData(EventObject e) {
        super.afterCreateNewData(e);
        // 设置默认组织为当前登录组织
        long currentOrgId = RequestContext.get().getOrgId();
        this.getModel().setValue("kdcd_org", currentOrgId);
    }

    // ==================== 二、注册事件监听 ====================
    /**
     * 注册查询面板的事件监听。
     * 典型用法：设置过滤条件面板不折叠。
     */
    @Override
    public void registerListener(EventObject e) {
        super.registerListener(e);
        // 过滤条件面板不折叠
        ReportFilter reportFilter = getControl("reportfilterap");
        if (reportFilter != null) {
            reportFilter.addSearchListener(searchEvent -> {
                ReportFilter filter = (ReportFilter) searchEvent.getSource();
                filter.setCollapse(false);
            });
        }
    }

    // ==================== 三、查询前校验 ====================
    /**
     * 点击「查询」按钮后、取数之前的校验入口。
     * 返回 false 时框架不会调用取数插件的 query()，并且可以在这里用 showErrorNotification 提示用户。
     * <p>注意：不要用 throw KDBizException，否则用户看到的是异常弹窗而非友好提示。</p>
     */
    @Override
    public boolean verifyQuery(ReportQueryParam queryParam) {
        FilterInfo filterInfo = queryParam.getFilter();
        DynamicObject org = filterInfo.getDynamicObject("kdcd_org");
        if (org == null) {
            this.getView().showErrorNotification("请选择组织");
            return false;
        }

        if (filterInfo.getDate("kdcd_date") == null) {
            this.getView().showErrorNotification("请选择截止日期");
            return false;
        }
        return true;
    }

    // ==================== 四、列排序和过滤 ====================
    /**
     * 设置所有列均支持排序和过滤。
     */
    @Override
    public void setSortAndFilter(List<SortAndFilterEvent> allColumns) {
        super.setSortAndFilter(allColumns);
        for (SortAndFilterEvent column : allColumns) {
            column.setFilter(true);
            column.setSort(true);
        }
    }

    // ==================== 五、行数据二次加工 ====================
    /**
     * 对取数插件返回的行数据进行二次处理。
     * 典型用法：
     * - 计算衍生字段（如百分比、差异等）
     * - 格式化显示（如金额格式化、缩进处理）
     * - 字段拼接（如名称 + 编码）
     *
     */
    @Override
    public void processRowData(String gridPk, DynamicObjectCollection rowData, ReportQueryParam queryParam) {
        super.processRowData(gridPk, rowData, queryParam);

        for (DynamicObject row : rowData) {
            BigDecimal budgetAmt = BigDecimalUtils.nullToZero(row.getBigDecimal("kdcd_budget_amt"));
            BigDecimal actualAmt = BigDecimalUtils.nullToZero(row.getBigDecimal("kdcd_actual_amt"));

            // 示例1：计算执行率 = (实际金额 / 预算金额) * 100%
            if (BigDecimalUtils.largeThanZero(budgetAmt)) {
                BigDecimal rate = BigDecimalUtils.divide(
                        BigDecimalUtils.multiply(actualAmt, BigDecimalUtils.valueOf(100)),
                        budgetAmt, 2, RoundingMode.HALF_UP);
                row.set("kdcd_exec_rate", BigDecimalUtils.toPlainString(rate) + "%");
            } else {
                row.set("kdcd_exec_rate", "0%");
            }

            // 示例2：差异金额已由取数插件计算，此处可做格式化
            // BigDecimal diffAmt = row.getBigDecimal("kdcd_diff_amt");
        }
    }

    // ==================== 六、超链接点击处理 ====================
    /**
     * 处理报表中超链接列的点击事件。
     * 典型用法：点击单据编号跳转到对应单据的详情页面。
     *
     */
    @Override
    public void hyperLinkClick(HyperLinkClickEvent evt) {
        String fieldKey = evt.getFieldName();
        DynamicObject rowData = evt.getRowData();

        if ("kdcd_item_name".equals(fieldKey)) {
            // 获取行数据中的单据ID
            long billId = rowData.getLong("kdcd_bill_id");
            String billFormId = "kdcd_sample_bill_a"; // 目标单据标识

            // 打开单据详情（查看模式，新标签页）
            FormShowParameter showParam = ShowParameterUtils.getBill(
                    billFormId, billId, OperationStatus.VIEW, ShowType.MainNewTabPage);
            this.getView().showForm(showParam);
        }
    }

    // ==================== 七、获取报表列表控件 ====================
    /**
     * 通过 FormUtils.getReportList() 获取报表列表控件。
     * 可用于自定义列表行为（如设置选中行、刷新等）。
     */
    private void reportListExample() {
        ReportList reportList = FormUtils.getReportList(this.getView());
        reportList.refresh();
        // reportList.setCurrentSelectedRowIndex(0);  // 设置选中行
        // reportList.refreshData();                   // 刷新数据
    }
}
