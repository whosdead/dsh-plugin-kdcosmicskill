/**
 * 超链接点击跳转示例（分录 + 列表）。
 * <p>
 * 适用插件：表单插件、报表插件
 * 优先封装：ShowParameterUtils
 * 原生兜底：EntryGrid.addHyperClickListener、ReportList.addHyperClickListener、HyperLinkClickListener
 * 相关 lint 规则：STYLE-015
 * <p>
 * 使用场景：
 * 1. 分录字段配置为超链接，点击跳转到关联单据；
 * 2. 报表/列表字段配置为超链接，点击查看详情单据。
 */
package kd.cd.common.snippets.form;

import kd.bos.bill.BillShowParameter;
import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.report.ReportList;
import kd.bos.form.ShowType;
import kd.bos.form.control.EntryGrid;
import kd.bos.form.events.HyperLinkClickEvent;
import kd.bos.form.events.HyperLinkClickListener;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.report.plugin.AbstractReportFormPlugin;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;

import java.util.EventObject;

public class HyperlinkJumpSample {

    /**
     * 分录超链接点击：在 EntryGrid 上注册超链接事件。
     * 控件/字段 key 请替换为你的实际配置。
     */
    public static class EntryHyperlinkFormPlugin extends AbstractFormPluginExt implements HyperLinkClickListener {
        private static final String ENTRY_KEY = "entryentity";
        private static final String ENTRY_LINK_FIELD = "billno";
        private static final String ENTRY_BASEDATA_FIELD_ID = "supplier_id";
        private static final String TARGET_FORM_ID = "your_bill_form_id";

        @Override
        public void registerListener(EventObject e) {
            super.registerListener(e);
            this.addHyperClickListeners(ENTRY_KEY);
        }

        @Override
        public void hyperLinkClick(HyperLinkClickEvent event) {
            if (!ENTRY_LINK_FIELD.equals(event.getFieldName())) {
                return;
            }
            DynamicObject row = this.getModel().getEntryRowEntity(ENTRY_KEY, event.getRowIndex());
            if (row == null) {
                return;
            }
            String billNo = row.getString(ENTRY_LINK_FIELD);
            DynamicObject target = QueryServiceHelper.queryOne(
                    TARGET_FORM_ID,
                    "id",
                    new QFilter[]{new QFilter("billno", QCP.equals, billNo)}
            );
            if (target == null) {
                return;
            }
            BillShowParameter showParameter = ShowParameterUtils.getBill(
                    TARGET_FORM_ID,
                    target.getLong("id"),
                    OperationStatus.VIEW,
                    ShowType.Modal
            );
            this.getView().showForm(showParameter);
        }
    }

    /**
     * 报表/列表超链接点击：在 ReportList 上注册超链接事件。
     * 控件/字段 key 请替换为你的实际配置。
     */
    public static class ReportHyperlinkFormPlugin extends AbstractReportFormPlugin implements HyperLinkClickListener {
        private static final String REPORT_LIST_KEY = "reportlistap";
        private static final String REPORT_LINK_FIELD = "billno";
        private static final String TARGET_FORM_ID = "your_bill_form_id";

        @Override
        public void registerListener(EventObject e) {
            super.registerListener(e);
            ReportList reportList = this.getView().getControl(REPORT_LIST_KEY);
            if (reportList != null) {
                reportList.addHyperClickListener(this);
            }
        }

        @Override
        public void hyperLinkClick(HyperLinkClickEvent event) {
            if (!REPORT_LINK_FIELD.equals(event.getFieldName())) {
                return;
            }
            int rowIndex = event.getRowIndex();
            ReportList reportList = this.getView().getControl(REPORT_LIST_KEY);
            Object billNo = reportList.getReportModel().getRowData(rowIndex).get(REPORT_LINK_FIELD);
            if (billNo == null) {
                return;
            }
            DynamicObject target = QueryServiceHelper.queryOne(
                    TARGET_FORM_ID,
                    "id",
                    new QFilter[]{new QFilter("billno", QCP.equals, billNo)}
            );
            if (target == null) {
                return;
            }
            BillShowParameter showParameter = ShowParameterUtils.getBill(
                    TARGET_FORM_ID,
                    target.getLong("id"),
                    OperationStatus.VIEW,
                    ShowType.MainNewTabPage
            );
            this.getView().showForm(showParameter);
        }
    }
}
