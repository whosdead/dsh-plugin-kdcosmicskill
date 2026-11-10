/**
 * 表单模型取值/赋值示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：DynamicObjectUtils
 * 原生兜底：IDataModel、BusinessDataServiceHelper
 * 相关 lint 规则：SCENE-008、STYLE-006
 * <p>
 * 使用场景：
 * 1. 表头 / 单据体 / 子单据体按行读取
 * 2. 基础资料属性值、多选基础资料读取
 * 3. 基础资料按对象、按 ID、按编码回写
 * 4. 批量造行、子单据体造行、静默初始化
 */
package kd.cd.common.snippets.form;

import kd.bos.context.RequestContext;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.entity.MulBasedataDynamicObjectCollection;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.BigDecimalUtils;
import kd.cd.core.util.CollectionUtils;

import java.math.BigDecimal;

public class GetAndSetValueSample extends AbstractFormPluginExt {
    private static final String BOS_USER = "bos_user";
    private static final String FIELD_REMARK = "kdtest_remark";
    private static final String FIELD_REGISTRANT = "kdtest_registrant";
    private static final String FIELD_REGISTRANT_BIRTHDAY = "birthday";
    private static final String FIELD_APPROVERS = "kdtest_approvers";
    private static final String ENTRY_KEY = "kdtest_reqentryentity";
    private static final String FIELD_QTY = "kdtest_qtyfield";
    private static final String FIELD_MATERIAL = "kdtest_materielfield";
    private static final String SUBENTRY_KEY = "kdtest_subentryentity";
    private static final String FIELD_SUB_DESC = "kdtest_subdesc";

    // ==================== 场景1：表单读值 ====================

    public void readHeaderEntryAndSubEntryValues() {
        String remark = (String) getModel().getValue(FIELD_REMARK);

        DynamicObject registrant = (DynamicObject) getModel().getValue(FIELD_REGISTRANT);
        Object registrantId = DynamicObjectUtils.nullSafeGet(registrant, FIELD_REGISTRANT + ".id");
        String registrantName = DynamicObjectUtils.nullSafeGet(registrant, FIELD_REGISTRANT + ".name");

        int rowCount = getModel().getEntryRowCount(ENTRY_KEY);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            BigDecimal qty = (BigDecimal) getModel().getValue(FIELD_QTY, rowIndex);
            DynamicObject material = (DynamicObject) getModel().getValue(FIELD_MATERIAL, rowIndex);
        }

        DynamicObjectCollection entryRows = getModel().getEntryEntity(ENTRY_KEY);
        for (DynamicObject row : entryRows) {
            BigDecimal qty = row.getBigDecimal(FIELD_QTY);
            DynamicObjectCollection subRows = row.getDynamicObjectCollection(SUBENTRY_KEY);
            for (DynamicObject subRow : subRows) {
                String subDesc = subRow.getString(FIELD_SUB_DESC);
            }
        }
    }

    public String loadRegistrantBirthday() {
        DynamicObject registrant = (DynamicObject) getModel().getValue(FIELD_REGISTRANT);
        if (EntityUtils.isEmptyPk(registrant)) {
            return null;
        }

        DynamicObject user = BusinessDataServiceHelper.loadSingleFromCache(
                registrant.getPkValue(),
                BOS_USER,
                FIELD_REGISTRANT_BIRTHDAY
        );
        return DynamicObjectUtils.nullSafeGet(user, FIELD_REGISTRANT_BIRTHDAY);
    }

    public int countSelectedApprovers() {
        MulBasedataDynamicObjectCollection approvers =
                (MulBasedataDynamicObjectCollection) getModel().getValue(FIELD_APPROVERS);
        return CollectionUtils.isEmpty(approvers) ? 0 : approvers.size();
    }

    // ==================== 场景2：表头 / 基础资料赋值 ====================

    public void fillDefaultHeaderAndBaseData() {
        getModel().setValue(FIELD_REMARK, "备注字段默认值");

        DynamicObject currentUser = BusinessDataServiceHelper.loadSingleFromCache(
                RequestContext.get().getCurrUserId(),
                BOS_USER,
                "id,number,name"
        );
        if (currentUser != null) {
            getModel().setValue(FIELD_REGISTRANT, currentUser);
        }

        getModel().setItemValueByID(FIELD_REGISTRANT, RequestContext.get().getCurrUserId());
        getModel().setItemValueByNumber(FIELD_REGISTRANT, "ID-000002");
    }

    // ==================== 场景3：批量造行 ====================

    public void rebuildEntryRowsByTemplate() {
        getModel().deleteEntryData(ENTRY_KEY);

        DynamicObjectCollection entryRows = getModel().getEntryEntity(ENTRY_KEY);
        DynamicObject template = new DynamicObject(entryRows.getDynamicObjectType());
        template.set(FIELD_QTY, BigDecimal.ZERO);
        int[] rowIndexes = getModel().batchCreateNewEntryRow(ENTRY_KEY, template, 2);
        for (int i = 0; i < rowIndexes.length; i++) {
            int rowIndex = rowIndexes[i];
            getModel().setValue(
                    FIELD_QTY,
                    BigDecimalUtils.multiply(BigDecimalUtils.valueOf(10), BigDecimalUtils.valueOf(i + 1L)),
                    rowIndex
            );
            getModel().setItemValueByNumber(FIELD_MATERIAL, "M000" + (i + 1), rowIndex);
        }
    }

    public void appendEntryRowByObject() {
        DynamicObjectCollection entryRows = getModel().getEntryEntity(ENTRY_KEY);
        DynamicObject row = entryRows.addNew();
        row.set(FIELD_QTY, BigDecimalUtils.valueOf(1));
        // 基础资料赋值方式1：从缓存加载基础资料对象后再 set
        DynamicObject supplier = BusinessDataServiceHelper.loadSingleFromCache(10001L, "bd_supplier", "id");
        if (supplier != null) {
            row.set("kdtest_supplier", supplier);
        }
        // 基础资料赋值方式2：可直接 set "字段标识_id" 进行 ID 赋值
        row.set("kdtest_supplier_id", 10001L);
        int rowIndex = getModel().createNewEntryRow(ENTRY_KEY, row);
        getModel().setItemValueByNumber(FIELD_MATERIAL, "M0001", rowIndex);
    }

    public void appendSubEntryRow(int parentRowIndex) {
        getModel().setEntryCurrentRowIndex(ENTRY_KEY, parentRowIndex);
        int rowIndex = getModel().createNewEntryRow(SUBENTRY_KEY);
        getModel().setValue(FIELD_SUB_DESC, "子单据体默认值", rowIndex);
    }

    public void appendApprover(Object userId) {
        MulBasedataDynamicObjectCollection approvers = (MulBasedataDynamicObjectCollection) getModel().getValue(FIELD_APPROVERS);
        if (CollectionUtils.isEmpty(approvers) || EntityUtils.isEmptyPk(userId)) {
            return;
        }

        DynamicObject row = approvers.addNew();
        DynamicObject user = DynamicObjectUtils.newDynamicObject(BOS_USER);
        user.set("id", userId);
        row.set("fbasedataid", user);
        getModel().setValue(FIELD_APPROVERS, approvers);
    }

    // ==================== 场景4：静默初始化 / 直接改数据包 ====================

    public void silentSetRemark(String remark) {
        getModel().beginInit();
        getModel().setValue(FIELD_REMARK, remark);
        getModel().endInit();
    }

    /**
     * 直接改数据包不会触发 propertyChanged，适合导入回填、批量矫正、静默初始化。
     */
    public void appendEntryRowSilently() {
        DynamicObjectCollection entryRows = getModel().getDataEntity(true).getDynamicObjectCollection(ENTRY_KEY);
        DynamicObject newRow = entryRows.addNew();
        newRow.set(FIELD_QTY, BigDecimalUtils.valueOf(30));
        getView().updateView(ENTRY_KEY);
    }
}
