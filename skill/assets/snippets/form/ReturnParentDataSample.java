/**
 * 子页面选中数据后 returnDataToParent(...) 示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：AbstractFormPluginExt
 * 原生兜底：returnDataToParent(...)、sendFormAction(...)、IFormView
 * 相关 lint 规则：当前无专门规则，可参考 SCENE-005、STYLE-013、RESOURCE-002
 * <p>
 * 使用场景：
 * 1. 单行 Map 回传，父页面按目标行覆盖；
 * 2. List<Map> 回传，父页面批量追加并去重；
 * 3. 子页面直接修改父页面模型后 sendFormAction。
 */
package kd.cd.common.snippets.form;

import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.entity.datamodel.IDataModel;
import kd.bos.form.CloseCallBack;
import kd.bos.form.FormShowParameter;
import kd.bos.form.IFormView;
import kd.bos.form.ShowType;
import kd.bos.form.control.Control;
import kd.bos.form.control.events.RowClickEvent;
import kd.bos.form.control.events.RowClickEventListener;
import kd.bos.form.events.ClosedCallBackEvent;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReturnParentDataSample extends AbstractFormPluginExt implements RowClickEventListener {
    private static final String ENTRY_KEY = "entryentity";
    private static final String BTN_OK = "btnok";
    private static final String FIELD_NUMBER = "number";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_OWNER = "owner";
    private static final String PARENT_REMARK = "remark";
    private static final String CALLBACK_CHILD_SELECT = "select_from_child";
    private static final String CHILD_FORM_ID = "xxxx_child_selector";
    private static final String CUSTOM_PARAM_TARGET_ROW_INDEX = "targetRowIndex";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    @Override
    public void registerListener(EventObject e) {
        super.registerListener(e);
        addClickListeners(BTN_OK);
        addEntryRowClickListeners(ENTRY_KEY);
    }

    @Override
    public void click(EventObject evt) {
        super.click(evt);
        Control control = (Control) evt.getSource();
        if (BTN_OK.equalsIgnoreCase(control.getKey())) {
            returnSelectedData();
        }
    }

    @Override
    public void entryRowDoubleClick(RowClickEvent evt) {
        DynamicObject row = resolveRowByIndex(evt.getRow());
        if (row != null) {
            returnSingleRow(row);
        }
    }

    private void returnSelectedData() {
        List<DynamicObject> selectedRows = getEntrySelectRowsEntity(ENTRY_KEY);
        if (CollectionUtils.isNotEmpty(selectedRows)) {
            if (selectedRows.size() == 1) {
                returnSingleRow(selectedRows.get(0));
            } else {
                returnMultiRows(selectedRows);
            }
            return;
        }

        DynamicObject currentRow = resolveRowByIndex(getModel().getEntryCurrentRowIndex(ENTRY_KEY));
        if (currentRow == null) {
            getView().showTipNotification(ResManager.loadKDString("请先选择一行数据", "ReturnParentDataSample_0", RES_APP_ID));
            return;
        }
        returnSingleRow(currentRow);
    }

    private void returnSingleRow(DynamicObject row) {
        Map<String, String> result = buildRowResult(row);
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        getView().returnDataToParent(result);
        getView().close();
    }

    private void returnMultiRows(List<DynamicObject> selectedRows) {
        List<Map<String, String>> resultList = new ArrayList<>(selectedRows.size());
        for (DynamicObject row : selectedRows) {
            Map<String, String> result = buildRowResult(row);
            if (!result.isEmpty()) {
                resultList.add(result);
            }
        }
        if (CollectionUtils.isEmpty(resultList)) {
            getView().showTipNotification(ResManager.loadKDString("所选数据无可回传内容", "ReturnParentDataSample_1", RES_APP_ID));
            return;
        }
        getView().returnDataToParent(resultList);
        getView().close();
    }

    private Map<String, String> buildRowResult(DynamicObject row) {
        Object ownerId = row.get(FIELD_OWNER + "_id");
        if (EntityUtils.isEmptyPk(ownerId)) {
            getView().showErrorNotification(ResManager.loadKDString("所选行未设置负责人，不能回传", "ReturnParentDataSample_2", RES_APP_ID));
            return Collections.emptyMap();
        }

        String number = row.getString(FIELD_NUMBER);
        if (CharSequenceUtils.isBlank(number)) {
            getView().showErrorNotification(ResManager.loadKDString("所选行编码为空，不能回传", "ReturnParentDataSample_3", RES_APP_ID));
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>(4);
        result.put(FIELD_NUMBER, number);
        result.put(FIELD_NAME, row.getString(FIELD_NAME));
        result.put("ownerId", String.valueOf(ownerId));

        int targetRowIndex = resolveRowIndex(getView().getFormShowParameter().getCustomParam(CUSTOM_PARAM_TARGET_ROW_INDEX));
        if (targetRowIndex >= 0) {
            result.put(CUSTOM_PARAM_TARGET_ROW_INDEX, String.valueOf(targetRowIndex));
        }
        return result;
    }

    public void writeBackToParentDirectly() {
        DynamicObject currentRow = resolveRowByIndex(getModel().getEntryCurrentRowIndex(ENTRY_KEY));
        IFormView parentView = getView().getParentView();
        if (currentRow == null || parentView == null) {
            return;
        }

        IDataModel parentModel = parentView.getModel();
        int targetRowIndex = resolveRowIndex(
                getView().getFormShowParameter().getCustomParam(CUSTOM_PARAM_TARGET_ROW_INDEX)
        );
        if (isValidParentRow(parentModel, targetRowIndex)) {
            parentModel.setValue(FIELD_NUMBER, currentRow.get(FIELD_NUMBER), targetRowIndex);
            parentModel.setValue(FIELD_NAME, currentRow.get(FIELD_NAME), targetRowIndex);
            parentModel.setValue(FIELD_OWNER, currentRow.get(FIELD_OWNER), targetRowIndex);
            parentView.updateView(ENTRY_KEY);
        } else {
            parentModel.setValue(PARENT_REMARK, currentRow.get(FIELD_NAME));
            parentView.updateView(PARENT_REMARK);
        }
        getView().sendFormAction(parentView);
        getView().close();
    }

    private DynamicObject resolveRowByIndex(int rowIndex) {
        List<DynamicObject> rows = getModel().getEntryEntity(ENTRY_KEY);
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return null;
        }
        return rows.get(rowIndex);
    }

    private boolean isValidParentRow(IDataModel parentModel, int rowIndex) {
        int rowCount = parentModel.getDataEntity(true).getDynamicObjectCollection(ENTRY_KEY).size();
        return rowIndex >= 0 && rowIndex < rowCount;
    }

    /**
     * 父页面侧：打开子页面，并在 closedCallBack 中消费回传数据。
     */
    public static class ParentReceiveSide extends AbstractFormPluginExt {
        public void openChildSelector() {
            FormShowParameter fsp = ShowParameterUtils.getForm(
                    CHILD_FORM_ID,
                    OperationStatus.ADDNEW,
                    ShowType.Modal,
                    "800px",
                    "600px"
            );
            int rowIndex = getModel().getEntryCurrentRowIndex(ENTRY_KEY);
            if (rowIndex >= 0) {
                fsp.setCustomParam(CUSTOM_PARAM_TARGET_ROW_INDEX, rowIndex);
            }
            fsp.setCloseCallBack(new CloseCallBack(this, CALLBACK_CHILD_SELECT));
            getView().showForm(fsp);
        }

        @Override
        public void closedCallBack(ClosedCallBackEvent e) {
            super.closedCallBack(e);
            // 回调数据类型和子页面 returnDataToParent(...) 的回传类型保持一致即可。
            if (!CALLBACK_CHILD_SELECT.equals(e.getActionId())) {
                return;
            }
            Object returnData = e.getReturnData();
            if (returnData == null) {
                return;
            }
            if (returnData instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Map<?, ?>> returnDataList = (List<Map<?, ?>>) returnData;
                applyReturnedList(returnDataList);
                return;
            }
            applyReturnedMap((Map<?, ?>) returnData);
        }

        private void applyReturnedMap(Map<?, ?> returnData) {
            int rowIndex = resolveRowIndex(returnData.get(CUSTOM_PARAM_TARGET_ROW_INDEX));
            if (!isValidEntryRow(rowIndex)) {
                rowIndex = getModel().getEntryCurrentRowIndex(ENTRY_KEY);
            }
            if (!isValidEntryRow(rowIndex)) {
                rowIndex = getModel().createNewEntryRow(ENTRY_KEY);
            }
            fillParentRow(rowIndex, returnData);
            getModel().setEntryCurrentRowIndex(ENTRY_KEY, rowIndex);
            getView().updateView(ENTRY_KEY);
            getView().showSuccessNotification(ResManager.loadKDString("已回填当前行数据", "ReturnParentDataSample_4", RES_APP_ID));
        }

        private void applyReturnedList(List<Map<?, ?>> returnDataList) {
            Set<String> existingNumbers = collectExistingNumbers();
            int addedCount = 0;
            int lastRowIndex = -1;
            for (Map<?, ?> rowData : returnDataList) {
                String number = String.valueOf(rowData.get(FIELD_NUMBER));
                if (CharSequenceUtils.isBlank(number) || !existingNumbers.add(number)) {
                    continue;
                }
                lastRowIndex = getModel().createNewEntryRow(ENTRY_KEY);
                fillParentRow(lastRowIndex, rowData);
                addedCount++;
            }
            if (addedCount == 0) {
                getView().showTipNotification(ResManager.loadKDString("回传数据已全部存在，无需重复追加", "ReturnParentDataSample_5", RES_APP_ID));
                return;
            }
            if (lastRowIndex >= 0) {
                getModel().setEntryCurrentRowIndex(ENTRY_KEY, lastRowIndex);
            }
            getView().updateView(ENTRY_KEY);
            getView().showSuccessNotification(String.format(
                    ResManager.loadKDString("已追加%s行数据", "ReturnParentDataSample_6", RES_APP_ID),
                    addedCount
            ));
        }

        private void fillParentRow(int rowIndex, Map<?, ?> returnData) {
            getModel().setValue(FIELD_NUMBER, returnData.get(FIELD_NUMBER), rowIndex);
            getModel().setValue(FIELD_NAME, returnData.get(FIELD_NAME), rowIndex);
            getModel().setItemValueByID(FIELD_OWNER, returnData.get("ownerId"), rowIndex);
        }

        private Set<String> collectExistingNumbers() {
            List<DynamicObject> rows = getModel().getEntryEntity(ENTRY_KEY);
            Set<String> numbers = new HashSet<>(rows.size());
            for (DynamicObject row : rows) {
                String number = row.getString(FIELD_NUMBER);
                if (CharSequenceUtils.isNotBlank(number)) {
                    numbers.add(number);
                }
            }
            return numbers;
        }

        private boolean isValidEntryRow(int rowIndex) {
            return rowIndex >= 0 && rowIndex < getModel().getEntryEntity(ENTRY_KEY).size();
        }
    }

    private static int resolveRowIndex(Object rowValue) {
        if (rowValue == null) {
            return -1;
        }
        return Integer.parseInt(String.valueOf(rowValue).trim());
    }
}
