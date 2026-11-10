/**
 * beforeDoOperation 拦截 + 弹窗确认后继续执行示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：ShowParameterUtils、AbstractFormPluginExt
 * 原生兜底：FormOperate、OperateOption、ClosedCallBackEvent
 * 相关 lint 规则：SCENE-008、STYLE-013
 * <p>
 * 使用场景：
 * 1. 提交前先做内存校验，命中风险则打开说明页；
 * 2. 部分检查必须先保存拿到主键，再在 afterDoOperation 中弹结果页；
 * 3. 用户确认后，带 continue 标记重新执行原提交操作。
 */
package kd.cd.common.snippets.form;

import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.OperateOption;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.form.CloseCallBack;
import kd.bos.form.FormShowParameter;
import kd.bos.form.ShowType;
import kd.bos.form.events.AfterDoOperationEventArgs;
import kd.bos.form.events.BeforeDoOperationEventArgs;
import kd.bos.form.events.ClosedCallBackEvent;
import kd.bos.form.operate.FormOperate;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.BigDecimalUtils;
import kd.cd.core.util.CollectionUtils;
import kd.bos.servicehelper.QueryServiceHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BeforeOperationConfirmSample extends AbstractFormPluginExt {
    private static final String OP_SAVE = "save";
    private static final String OP_SUBMIT = "submit";
    private static final String CHECK_WARNING_FORM_ID = "xxxx_billcheckresult";
    private static final String CHECK_WARNING_CALLBACK_ID = "bill_check_warning";
    private static final String CHECK_RESULT_FORM_ID = "xxxx_billcheckdetail";
    private static final String CHECK_RESULT_CALLBACK_ID = "bill_check_detail";
    private static final String OPTION_CONTINUE = "ContinueToSubmit";
    private static final String OPTION_SAVE_THEN_CHECK = "SaveThenCheck";
    private static final String ENTRY_KEY = "billentry";
    private static final String FIELD_SUPPLIER = "supplier";
    private static final String FIELD_QTY = "qty";
    private static final String FIELD_TAX_AMOUNT = "taxamount";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    @Override
    public void beforeDoOperation(BeforeDoOperationEventArgs args) {
        super.beforeDoOperation(args);
        if (args.isCancel()) {
            return;
        }

        FormOperate operate = (FormOperate) args.getSource();
        if (!OP_SUBMIT.equals(operate.getOperateKey()) || isContinueSubmit(operate.getOption())) {
            return;
        }

        List<String> warnings = collectSubmitWarnings();
        if (CollectionUtils.isNotEmpty(warnings)) {
            openWarningConfirmForm(warnings);
            args.setCancel(true);
            return;
        }

        Object billPk = getModel().getDataEntity().getPkValue();
        if (EntityUtils.isEmptyPk(billPk)) {
            OperateOption saveOption = OperateOption.create();
            saveOption.setVariableValue(OPTION_SAVE_THEN_CHECK, "true");
            getView().invokeOperation(OP_SAVE, saveOption);
            args.setCancel(true);
            return;
        }

        List<Object> resultIds = queryCheckResultIds(billPk);
        if (CollectionUtils.isNotEmpty(resultIds)) {
            openPersistedCheckResultForm(resultIds);
            args.setCancel(true);
        }
    }

    @Override
    public void afterDoOperation(AfterDoOperationEventArgs args) {
        super.afterDoOperation(args);
        FormOperate operate = (FormOperate) args.getSource();
        if (OP_SAVE.equals(operate.getOperateKey())) {
            handleSaveAfterCheck(operate, args);
            return;
        }
        if (OP_SUBMIT.equals(operate.getOperateKey())
                && args.getOperationResult().isSuccess()
                && isContinueSubmit(operate.getOption())) {
            args.getOperationResult().setMessage(ResManager.loadKDString("提交成功：已按确认结果继续执行", "BeforeOperationConfirmSample_3", RES_APP_ID));
        }
    }

    @Override
    public void closedCallBack(ClosedCallBackEvent e) {
        super.closedCallBack(e);
        // 回调数据类型和确认页的回传类型保持一致即可。
        String actionId = e.getActionId();
        if (!CHECK_WARNING_CALLBACK_ID.equals(actionId) && !CHECK_RESULT_CALLBACK_ID.equals(actionId)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> returnData = (Map<String, Object>) e.getReturnData();
        if (returnData != null && shouldContinueSubmit(returnData)) {
            invokeContinueSubmit();
        }
    }

    private void handleSaveAfterCheck(FormOperate operate, AfterDoOperationEventArgs args) {
        if (!"true".equalsIgnoreCase(operate.getOption().getVariableValue(OPTION_SAVE_THEN_CHECK, "false"))) {
            return;
        }
        if (!args.getOperationResult().isSuccess()) {
            return;
        }

        Object billPk = getModel().getDataEntity().getPkValue();
        List<Object> resultIds = queryCheckResultIds(billPk);
        if (CollectionUtils.isEmpty(resultIds)) {
            invokeContinueSubmit();
            return;
        }
        openPersistedCheckResultForm(resultIds);
    }

    private void openWarningConfirmForm(List<String> warnings) {
        FormShowParameter fsp = ShowParameterUtils.getForm(
                CHECK_WARNING_FORM_ID,
                OperationStatus.VIEW,
                ShowType.Modal,
                "900px",
                "600px"
        );
        fsp.setCustomParam("warnings", warnings);
        fsp.setCloseCallBack(new CloseCallBack(this, CHECK_WARNING_CALLBACK_ID));
        getView().showForm(fsp);
    }

    private void openPersistedCheckResultForm(List<Object> resultIds) {
        FormShowParameter fsp = ShowParameterUtils.getForm(
                CHECK_RESULT_FORM_ID,
                OperationStatus.VIEW,
                ShowType.Modal,
                "960px",
                "640px"
        );
        fsp.setCustomParam("sourceBillId", String.valueOf(getModel().getDataEntity().getPkValue()));
        fsp.setCustomParam("resultIds", resultIds);
        fsp.setCloseCallBack(new CloseCallBack(this, CHECK_RESULT_CALLBACK_ID));
        getView().showForm(fsp);
    }

    private void invokeContinueSubmit() {
        OperateOption option = OperateOption.create();
        option.setVariableValue(OPTION_CONTINUE, "true");
        getView().invokeOperation(OP_SUBMIT, option);
    }

    private List<Object> queryCheckResultIds(Object billPk) {
        if (EntityUtils.isEmptyPk(billPk)) {
            return Collections.emptyList();
        }
        QFilter filter = new QFilter("sourcebill.id", QCP.equals, billPk);
        DynamicObjectCollection rows = QueryServiceHelper.query(CHECK_RESULT_FORM_ID, "id", filter.toArray());
        return DynamicObjectUtils.listOf(rows, "id");
    }

    private boolean shouldContinueSubmit(Map<String, Object> returnData) {
        return Boolean.TRUE.equals(returnData.get("confirmed"));
    }

    private List<String> collectSubmitWarnings() {
        DynamicObjectCollection entryRows = getModel().getDataEntity(true).getDynamicObjectCollection(ENTRY_KEY);
        List<String> warnings = new ArrayList<>(entryRows.size() * 3);
        for (int i = 0; i < entryRows.size(); i++) {
            DynamicObject row = entryRows.get(i);
            int seq = i + 1;
            if (row.getDynamicObject(FIELD_SUPPLIER) == null) {
                warnings.add(String.format(
                        ResManager.loadKDString("第%s行未选择供应商", "BeforeOperationConfirmSample_0", RES_APP_ID),
                        seq
                ));
            }
            if (BigDecimalUtils.lessEqualsZero(row.getBigDecimal(FIELD_QTY))) {
                warnings.add(String.format(
                        ResManager.loadKDString("第%s行数量必须大于0", "BeforeOperationConfirmSample_1", RES_APP_ID),
                        seq
                ));
            }
            if (row.getBigDecimal(FIELD_TAX_AMOUNT) == null) {
                warnings.add(String.format(
                        ResManager.loadKDString("第%s行税额未计算，请先刷新金额", "BeforeOperationConfirmSample_2", RES_APP_ID),
                        seq
                ));
            }
        }
        return warnings;
    }

    private boolean isContinueSubmit(OperateOption option) {
        return option != null && "true".equalsIgnoreCase(option.getVariableValue(OPTION_CONTINUE, "false"));
    }
}
