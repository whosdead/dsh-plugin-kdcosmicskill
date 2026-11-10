/**
 * F7（基础资料选择控件）过滤条件示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：DynamicObjectUtils、EntityUtils
 * 原生兜底：BeforeF7SelectEvent、ListShowParameter、QFilter
 * 相关 lint 规则：SCENE-005、STYLE-013
 * <p>
 * 使用场景：
 * 1. 打开 F7 前，按当前组织、行物料、启用/审核状态动态过滤可选数据；
 * 2. 按当前体系切换特殊 F7 页面，并注入模型/维度 customParams；
 * 3. 回显当前已选数据、控制是否展示未审核数据、是否开启多选；
 * 4. 个别字段需要关闭系统默认 QFilter；
 * 5. 区分表头字段 F7 与分录字段 F7 的过滤来源。
 */
package kd.cd.common.snippets.form;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.form.FormShowParameter;
import kd.bos.form.field.events.BeforeF7SelectEvent;
import kd.bos.list.ListShowParameter;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.DynamicObjectUtils;

import java.util.EventObject;
import java.util.List;

public class F7FilterSample extends AbstractFormPluginExt {
    private static final String FIELD_ORG = "org";
    private static final String FIELD_MATERIAL = "material";
    private static final String FIELD_SUPPLIER = "supplier";
    private static final String FIELD_MODEL = "kdcd_model";
    private static final String FIELD_YEAR = "kdcd_year";
    private static final String FIELD_PERIOD = "kdcd_period";
    private static final String SPECIAL_MEMBER_F7_FORM_ID = "bcm_mulmemberf7base_tem";
    private static final String FIELD_ENABLE = "enable";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_CREATE_ORG_ID = "createorg.id";
    private static final String FIELD_MATERIAL_MASTER_ID = "masterid.id";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    // --- 在 registerListener 中注册 F7 监听 ---
    @Override
    public void registerListener(EventObject e) {
        super.registerListener(e);
        addBeforeF7SelectListeners(FIELD_MATERIAL, FIELD_SUPPLIER, FIELD_MODEL, FIELD_YEAR, FIELD_PERIOD);
    }

    // --- 打开 F7 前按业务条件加过滤 ---
    @Override
    public void beforeF7Select(BeforeF7SelectEvent e) {
        super.beforeF7Select(e);
        String fieldKey = e.getProperty().getName();
        if (FIELD_MATERIAL.equals(fieldKey)) {
            Object orgId = resolveOrgId();
            if (EntityUtils.isEmptyPk(orgId)) {
                e.setCancel(true);
                getView().showTipNotification(ResManager.loadKDString("请先选择组织", "F7FilterSample_0", RES_APP_ID));
                return;
            }

            List<QFilter> filters = e.getCustomQFilters();
            appendEnabledApprovedFilters(filters);
            filters.add(new QFilter(FIELD_CREATE_ORG_ID, QCP.equals, orgId));

            ListShowParameter showParameter = (ListShowParameter) e.getFormShowParameter();
            configureShowParameter(showParameter, false);
            return;
        }

        if (FIELD_SUPPLIER.equals(fieldKey)) {
            int rowIndex = e.getRow();
            DynamicObject material = (DynamicObject) getModel().getValue(FIELD_MATERIAL, rowIndex);
            Object materialMasterId = DynamicObjectUtils.nullSafeGet(material, FIELD_MATERIAL_MASTER_ID);
            if (EntityUtils.isEmptyPk(materialMasterId)) {
                e.setCancel(true);
                getView().showTipNotification(ResManager.loadKDString("请先选择当前行物料", "F7FilterSample_1", RES_APP_ID));
                return;
            }

            Object orgId = resolveOrgId();

            ListShowParameter showParameter = (ListShowParameter) e.getFormShowParameter();
            List<QFilter> filters = showParameter.getListFilterParameter().getQFilters();
            filters.add(new QFilter(FIELD_ENABLE, QCP.equals, true));
            filters.add(new QFilter("forbidstatus", QCP.not_equals, "B"));
            filters.add(new QFilter("suppliermaterial.material.masterid.id", QCP.equals, materialMasterId));
            if (!EntityUtils.isEmptyPk(orgId)) {
                filters.add(new QFilter(FIELD_CREATE_ORG_ID, QCP.equals, orgId));
            }
            configureShowParameter(showParameter, true);

            Object supplierId = DynamicObjectUtils.nullSafeGet((DynamicObject) getModel().getValue(FIELD_SUPPLIER, rowIndex), "id");
            if (EntityUtils.isNotEmptyPk(supplierId)) {
                showParameter.setSelectedRow(supplierId);
                showParameter.setSelectedRows(new Object[]{supplierId});
            }
            return;
        }

        if (FIELD_MODEL.equals(fieldKey)) {
            FormShowParameter formShowParameter = e.getFormShowParameter();
            formShowParameter.setFormId(SPECIAL_MEMBER_F7_FORM_ID);
            formShowParameter.setCustomParam("noNeedDefaultQFilter", true);
            formShowParameter.setCustomParam("mutilentity", "[]");
            formShowParameter.setCustomParam("sign", FIELD_MODEL);
            return;
        }

        if (FIELD_YEAR.equals(fieldKey) || FIELD_PERIOD.equals(fieldKey)) {
            ListShowParameter showParameter = (ListShowParameter) e.getFormShowParameter();
            appendEnabledApprovedFilters(showParameter.getListFilterParameter().getQFilters());
            configureShowParameter(showParameter, false);
        }
    }

    private void appendEnabledApprovedFilters(List<QFilter> filters) {
        filters.add(new QFilter(FIELD_ENABLE, QCP.equals, true));
        filters.add(new QFilter(FIELD_STATUS, QCP.equals, "C"));
    }

    private Object resolveOrgId() {
        return DynamicObjectUtils.nullSafeGet(getValue(FIELD_ORG), "id");
    }

    private void configureShowParameter(ListShowParameter showParameter, boolean multiSelect) {
        showParameter.setShowApproved(false);
        showParameter.setMultiSelect(multiSelect);
    }
}
