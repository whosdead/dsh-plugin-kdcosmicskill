/**
 * 打开单据/动态表单弹窗并处理关闭回调示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：ShowParameterUtils、DynamicObjectUtils
 * 原生兜底：CloseCallBack、ClosedCallBackEvent、FormShowParameter / ListShowParameter
 * 相关 lint 规则：当前无专门规则，可参考 STYLE-013、SCENE-005
 * <p>
 * 使用场景：
 * 1. 打开 F7 列表让用户多选基础资料；
 * 2. 打开自定义动态表单并按当前分录回填结果；
 * 3. 打开特殊成员 F7，并按动态 actionId 回填多选基础资料。
 */
package kd.cd.common.snippets.form;

import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.entity.MulBasedataDynamicObjectCollection;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.entity.datamodel.ListSelectedRow;
import kd.bos.entity.datamodel.ListSelectedRowCollection;
import kd.bos.form.CloseCallBack;
import kd.bos.form.FormShowParameter;
import kd.bos.form.ShowType;
import kd.bos.form.events.ClosedCallBackEvent;
import kd.bos.form.IPageCache;
import kd.bos.list.ListFilterParameter;
import kd.bos.list.ListShowParameter;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.LinkedHashSet;
import java.util.Set;

public class OpenBillModalSample extends AbstractFormPluginExt {
    private static final String CALLBACK_SELECT_SUPPLIER = "select_supplier";
    private static final String CALLBACK_SELECT_BUDGET = "select_budget";
    private static final String CALLBACK_SELECT_DIM_MEMBER_PREFIX = "select_dim_member:";
    private static final String SUPPLIER_ENTRY = "entrysupplier";
    private static final String SUPPLIER_FIELD = "supplier";
    private static final String BILL_ENTRY = "billentry";
    private static final String SPECIAL_MEMBER_F7_FORM_ID = "bcm_mulmemberf7base_tem";
    private static final String PAGE_CACHE_BUDGET_ROW = "budget_callback_row";
    private static final String FIELD_BUDGET_ORG_NUMBER = "xxxx_orgnumber";
    private static final String FIELD_BUDGET_YEAR = "xxxx_budgetyear";
    private static final String FIELD_COST_CENTER = "xxxx_costcenter";
    private static final String FIELD_WORKTASK = "xxxx_worktask";
    private static final String FIELD_WORKTASK_NO = "xxxx_worktaskno";
    private static final String FIELD_FEE_ACCOUNT = "xxxx_feeaccount";
    private static final String FIELD_FEE_ACCOUNT_NO = "xxxx_feeaccountno";
    private static final String FIELD_BUDGET_OFFICER = "xxxx_budgetofficer";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    public void openSupplierSelector(Object orgId, Object materialId) {
        ListShowParameter showParameter = ShowParameterUtils.getF7List("bd_supplier", true);
        showParameter.setCloseCallBack(new CloseCallBack(this, CALLBACK_SELECT_SUPPLIER));

        ListFilterParameter listFilter = showParameter.getListFilterParameter();
        listFilter.getQFilters().add(new QFilter("enable", QCP.equals, true));
        listFilter.getQFilters().add(new QFilter("createorg.id", QCP.equals, orgId));
        listFilter.getQFilters().add(new QFilter("suppliermaterial.material.masterid.id", QCP.equals, materialId));
        getView().showForm(showParameter);
    }

    public void openBudgetSelectorForCurrentRow() {
        int rowIndex = getModel().getEntryCurrentRowIndex(BILL_ENTRY);
        if (!isValidEntryRow(rowIndex)) {
            getView().showTipNotification(ResManager.loadKDString("请先定位到需要回填的分录行", "OpenBillModalSample_0", RES_APP_ID));
            return;
        }

        String orgNumber = toText(getModel().getValue(FIELD_BUDGET_ORG_NUMBER, rowIndex));
        String budgetYear = toText(getModel().getValue(FIELD_BUDGET_YEAR, rowIndex));
        String costCenter = toText(getModel().getValue(FIELD_COST_CENTER, rowIndex));
        if (CharSequenceUtils.isBlank(orgNumber)
                || CharSequenceUtils.isBlank(budgetYear)
                || CharSequenceUtils.isBlank(costCenter)) {
            getView().showTipNotification(ResManager.loadKDString("请先补全预算组织、预算年度和成本中心", "OpenBillModalSample_1", RES_APP_ID));
            return;
        }

        getPageCache().put(PAGE_CACHE_BUDGET_ROW, String.valueOf(rowIndex));
        openBudgetSelector(orgNumber, budgetYear, costCenter);
    }

    public void openBudgetSelector(String orgNumber, String budgetYear, String costCenter) {
        FormShowParameter fsp = ShowParameterUtils.getForm(
                "xxxx_budgetlist",
                OperationStatus.ADDNEW,
                ShowType.Modal,
                "900px",
                "600px"
        );
        fsp.setCustomParam("OrgNumber", orgNumber);
        fsp.setCustomParam("BudgetYear", budgetYear);
        fsp.setCustomParam("CostCenter", costCenter);
        fsp.setCloseCallBack(new CloseCallBack(this, CALLBACK_SELECT_BUDGET));
        getView().showForm(fsp);
    }

    public void openMemberSelector(Object modelId, String fieldKey, String memberModel, Object dimensionId) {
        ListShowParameter showParameter = ShowParameterUtils.getF7List(SPECIAL_MEMBER_F7_FORM_ID, true);
        showParameter.setCustomParam("KEY_MODEL_ID", String.valueOf(modelId));
        showParameter.setCustomParam("dimensionid", String.valueOf(dimensionId));
        showParameter.setCustomParam("mutilentity", "[]");
        showParameter.setCustomParam("sign", fieldKey);
        showParameter.setCloseCallBack(new CloseCallBack(this, buildMemberCallbackId(fieldKey, memberModel)));
        getView().showForm(showParameter);
    }

    @Override
    public void closedCallBack(ClosedCallBackEvent e) {
        super.closedCallBack(e);
        // 回调数据类型和对应选择页的回传类型保持一致即可。
        String actionId = e.getActionId();
        Object returnData = e.getReturnData();
        if (returnData == null) {
            return;
        }
        if (actionId != null && actionId.startsWith(CALLBACK_SELECT_DIM_MEMBER_PREFIX)) {
            handleDimMemberSelected(actionId, (DynamicObjectCollection) returnData);
        } else if (CALLBACK_SELECT_SUPPLIER.equals(actionId)) {
            handleSupplierSelected((ListSelectedRowCollection) returnData);
        } else if (CALLBACK_SELECT_BUDGET.equals(actionId)) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> selectedBudget = (java.util.Map<String, String>) returnData;
            handleBudgetSelected(selectedBudget);
        }
    }

    private void handleSupplierSelected(ListSelectedRowCollection selectedRows) {
        if (CollectionUtils.isEmpty(selectedRows)) {
            return;
        }

        Set<Object> existingSupplierIds = new LinkedHashSet<>();
        for (DynamicObject row : getModel().getEntryEntity(SUPPLIER_ENTRY)) {
            DynamicObject supplier = row.getDynamicObject(SUPPLIER_FIELD);
            Object supplierId = DynamicObjectUtils.nullSafeGet(supplier, "id");
            if (EntityUtils.isNotEmptyPk(supplierId)) {
                existingSupplierIds.add(supplierId);
            }
        }

        int addedCount = 0;
        for (ListSelectedRow selectedRow : selectedRows) {
            Object supplierId = selectedRow.getPrimaryKeyValue();
            if (EntityUtils.isEmptyPk(supplierId) || !existingSupplierIds.add(supplierId)) {
                continue;
            }
            int rowIndex = getModel().createNewEntryRow(SUPPLIER_ENTRY);
            getModel().setItemValueByID(SUPPLIER_FIELD, supplierId, rowIndex);
            addedCount++;
        }
        if (addedCount == 0) {
            getView().showTipNotification(ResManager.loadKDString("所选供应商已全部存在，无需重复回填", "OpenBillModalSample_2", RES_APP_ID));
            return;
        }
        getView().updateView(SUPPLIER_ENTRY);
        getView().showSuccessNotification(String.format(
                ResManager.loadKDString("已新增%s行供应商", "OpenBillModalSample_3", RES_APP_ID),
                addedCount
        ));
    }

    private void handleBudgetSelected(java.util.Map<String, String> selectedBudget) {
        int rowIndex = resolveBudgetRowIndex();
        if (!isValidEntryRow(rowIndex)) {
            getView().showTipNotification(ResManager.loadKDString("目标分录行已不存在，请重新选择预算", "OpenBillModalSample_4", RES_APP_ID));
            return;
        }

        getModel().setEntryCurrentRowIndex(BILL_ENTRY, rowIndex);
        getModel().setValue(FIELD_WORKTASK, selectedBudget.get("workTask"), rowIndex);
        getModel().setValue(FIELD_WORKTASK_NO, selectedBudget.get("workTaskNo"), rowIndex);
        getModel().setValue(FIELD_FEE_ACCOUNT, selectedBudget.get("feeAccount"), rowIndex);
        getModel().setValue(FIELD_FEE_ACCOUNT_NO, selectedBudget.get("feeAccountNo"), rowIndex);
        getModel().setValue(FIELD_BUDGET_OFFICER, selectedBudget.get("budgetOfficer"), rowIndex);
        getView().updateView(BILL_ENTRY);
        getView().showSuccessNotification(String.format(
                ResManager.loadKDString("已回填第%s行预算信息", "OpenBillModalSample_5", RES_APP_ID),
                rowIndex + 1
        ));
    }

    private String buildMemberCallbackId(String fieldKey, String memberModel) {
        return CALLBACK_SELECT_DIM_MEMBER_PREFIX + fieldKey + "|" + memberModel;
    }

    private void handleDimMemberSelected(String actionId, DynamicObjectCollection selectedRows) {
        String[] parts = actionId.substring(CALLBACK_SELECT_DIM_MEMBER_PREFIX.length()).split("\\|", 2);
        if (parts.length != 2) {
            return;
        }

        String fieldKey = parts[0];
        String memberModel = parts[1];
        MulBasedataDynamicObjectCollection coll = (MulBasedataDynamicObjectCollection) getModel().getValue(fieldKey);

        Set<Object> existingMemberIds = new LinkedHashSet<>();
        for (DynamicObject row : coll) {
            DynamicObject member = row.getDynamicObject("fbasedataid");
            Object memberId = DynamicObjectUtils.nullSafeGet(member, "id");
            if (EntityUtils.isNotEmptyPk(memberId)) {
                existingMemberIds.add(memberId);
            }
        }

        int addedCount = 0;
        for (DynamicObject selectedRow : selectedRows) {
            Object memberId = selectedRow.get("mid1");
            if (EntityUtils.isEmptyPk(memberId) || !existingMemberIds.add(memberId)) {
                continue;
            }
            DynamicObject row = coll.addNew();
            DynamicObject member = DynamicObjectUtils.newDynamicObject(memberModel);
            member.set("id", memberId);
            row.set("fbasedataid", member);
            addedCount++;
        }
        if (addedCount == 0) {
            getView().showTipNotification(ResManager.loadKDString("所选成员已全部存在，无需重复回填", "OpenBillModalSample_6", RES_APP_ID));
            return;
        }
        getModel().setValue(fieldKey, coll);
        getView().updateView(fieldKey);
    }

    private int resolveBudgetRowIndex() {
        IPageCache pageCache = getPageCache();
        String rowText = pageCache.get(PAGE_CACHE_BUDGET_ROW);
        pageCache.remove(PAGE_CACHE_BUDGET_ROW);
        if (rowText == null) {
            return getModel().getEntryCurrentRowIndex(BILL_ENTRY);
        }
        return Integer.parseInt(rowText);
    }

    private boolean isValidEntryRow(int rowIndex) {
        return rowIndex >= 0 && rowIndex < getModel().getEntryEntity(BILL_ENTRY).size();
    }

    private String toText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
