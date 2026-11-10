/**
 * 列表 preOpenForm 按父页面字段动态过滤示例。
 * <p>
 * 适用插件：列表插件
 * 优先封装：FormUtils、AbstractListPluginExt
 * 原生兜底：PreOpenFormEventArgs、ListShowParameter、QFilter
 * 相关 lint 规则：当前无专门规则，可参考 SCENE-003、STYLE-015
 * <p>
 * 使用场景：从父单据打开 F7/列表时，
 * 根据父页面表头或当前分录字段给列表追加过滤条件。
 */
package kd.cd.common.snippets.list;

import kd.bos.entity.datamodel.IDataModel;
import kd.bos.form.IFormView;
import kd.bos.form.events.PreOpenFormEventArgs;
import kd.bos.list.ListShowParameter;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.cd.common.form.FormUtils;
import kd.cd.common.plugin.AbstractListPluginExt;
import kd.cd.core.util.CharSequenceUtils;

import java.util.List;

public class ListPreOpenFilterSample extends AbstractListPluginExt {
    private static final String PARENT_FORM_ID = "cas_claimbill";
    private static final String PARENT_ENTRY_KEY = "entryentity";
    private static final String FIELD_CORE_BILL_TYPE = "e_corebilltype";
    private static final String FIELD_PAYMENT_TYPE = "paymenttype";
    private static final String FIELD_PAYER_NO = "recbasepayer.number";

    // --- 从父页面打开列表前，动态追加过滤 ---
    @Override
    public void preOpenForm(PreOpenFormEventArgs e) {
        super.preOpenForm(e);
        ListShowParameter showParameter = (ListShowParameter) e.getFormShowParameter();
        if (!PARENT_FORM_ID.equals(showParameter.getParentFormId())) {
            return;
        }
        // 获取noPlugin的parentView，防止跨节点报错
        IFormView parentView = FormUtils.getViewByPageId(showParameter.getParentPageId(), true);
        if (parentView == null) {
            return;
        }

        IDataModel parentModel = parentView.getModel();
        int rowIndex = parentModel.getEntryCurrentRowIndex(PARENT_ENTRY_KEY);
        if (rowIndex < 0) {
            return;
        }

        String coreBillType = (String) parentModel.getValue(FIELD_CORE_BILL_TYPE, rowIndex);
        String paymentType = String.valueOf(parentModel.getValue(FIELD_PAYMENT_TYPE));
        if (!"ar_finarbill".equals(coreBillType) || !"bd_customer".equals(paymentType)) {
            return;
        }

        String payerNumber = parentModel.getDataEntity().getString(FIELD_PAYER_NO);
        if (CharSequenceUtils.isBlank(payerNumber)) {
            return;
        }

        List<QFilter> qFilters = showParameter.getListFilterParameter().getQFilters();
        qFilters.add(new QFilter("asstact.number", QCP.equals, payerNumber));
        qFilters.add(new QFilter("billstatus", QCP.equals, "C"));
    }
}
