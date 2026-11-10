/**
 * 界面插件与操作插件之间通过 OperateOption 传值示例。
 * <p>
 * 适用插件：表单插件、操作插件
 * 优先封装：AbstractFormPluginExt、AbstractOperationServicePlugInExt
 * 原生兜底：OperateOption、BeforeDoOperationEventArgs、AfterOperationArgs
 * 相关 lint 规则：SCENE-001、STYLE-013
 * <p>
 * 使用场景：
 * 1. 提交前先打开复核页，用户确认后带 continue 标记重新执行提交；
 * 2. 界面插件把临时参数传给操作插件；
 * 3. 操作插件把处理结果回写到 option，供界面插件展示提示。
 */
package kd.cd.common.snippets.operation;

import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.OperateOption;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.entity.plugin.args.AfterOperationArgs;
import kd.bos.entity.plugin.args.BeginOperationTransactionArgs;
import kd.bos.form.CloseCallBack;
import kd.bos.form.FormShowParameter;
import kd.bos.form.ShowType;
import kd.bos.form.events.AfterDoOperationEventArgs;
import kd.bos.form.events.BeforeDoOperationEventArgs;
import kd.bos.form.events.ClosedCallBackEvent;
import kd.bos.form.operate.FormOperate;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.plugin.AbstractOperationServicePlugInExt;
import kd.cd.core.util.CharSequenceUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Map;

public class OperationOptionBridgeSample {
    public static final String OP_SUBMIT = "submit";
    public static final String REVIEW_FORM_ID = "xxxx_submitreview";
    public static final String REVIEW_CALLBACK_ID = "submit_review";
    public static final String OPTION_CUSTOM_ITEM = "customItem";
    public static final String OPTION_SKIP_SUBMIT_REVIEW = "skipSubmitReview";
    public static final String OPTION_REVIEW_COMMENT = "reviewComment";
    public static final String OPTION_ALL_BILL_NO = "allBillNo";
    public static final String OPTION_WARNING_SUMMARY = "warningSummary";
    public static final String FIELD_RISK_FLAG = "xxxx_riskflag";
    public static final String FIELD_REVIEW_COMMENT = "xxxx_reviewcomment";
    public static final String FIELD_CUSTOM_TAG = "xxxx_customtag";

    /**
     * 界面插件侧：提交前拦截、弹复核页、读取操作插件回传值。
     * 同文件中同时放了操作插件示例，lint 可能把本类里的 getView() 误判到操作插件上下文。
     */
    public static class BillViewSide extends AbstractFormPluginExt {
        @Override
        public void beforeDoOperation(BeforeDoOperationEventArgs e) {
            super.beforeDoOperation(e);
            FormOperate operate = (FormOperate) e.getSource();
            if (!OP_SUBMIT.equals(operate.getOperateKey())) {
                return;
            }

            bindSharedOption(operate.getOption());
            if (shouldSkipSubmitReview(operate.getOption()) || !needOpenSubmitReview()) {
                return;
            }

            e.setCancel(true);
            openSubmitReviewDialog();
        }

        @Override
        public void afterDoOperation(AfterDoOperationEventArgs e) {
            super.afterDoOperation(e);
            FormOperate operate = (FormOperate) e.getSource();
            if (!OP_SUBMIT.equals(operate.getOperateKey()) || !e.getOperationResult().isSuccess()) {
                return;
            }

            String allBillNo = operate.getOption().getVariableValue(OPTION_ALL_BILL_NO, "");
            String warningSummary = operate.getOption().getVariableValue(OPTION_WARNING_SUMMARY, "");
            if (CharSequenceUtils.isBlank(allBillNo) && CharSequenceUtils.isBlank(warningSummary)) {
                return;
            }

            StringBuilder msg = new StringBuilder("提交成功");
            if (CharSequenceUtils.isNotBlank(allBillNo)) {
                msg.append("：").append(allBillNo);
            }
            if (CharSequenceUtils.isNotBlank(warningSummary)) {
                msg.append("；").append(warningSummary);
            }
            e.getOperationResult().setMessage(msg.toString());
        }

        @Override
        public void closedCallBack(ClosedCallBackEvent e) {
            super.closedCallBack(e);
            // 回调数据类型和复核页的回传类型保持一致即可。
            if (!REVIEW_CALLBACK_ID.equals(e.getActionId())) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> reviewData = (Map<String, Object>) e.getReturnData();
            if (reviewData == null || !isConfirmed(reviewData)) {
                return;
            }

            OperateOption option = OperateOption.create();
            bindSharedOption(option);
            option.setVariableValue(OPTION_SKIP_SUBMIT_REVIEW, "true");

            String reviewComment = extractReviewComment(reviewData);
            if (CharSequenceUtils.isNotBlank(reviewComment)) {
                option.setVariableValue(OPTION_REVIEW_COMMENT, reviewComment);
            }
            getView().invokeOperation(OP_SUBMIT, option);
        }

        private void bindSharedOption(OperateOption option) {
            String customItem = resolveCustomItem();
            if (CharSequenceUtils.isNotBlank(customItem)) {
                option.setVariableValue(OPTION_CUSTOM_ITEM, customItem);
            }
        }

        private boolean needOpenSubmitReview() {
            return Boolean.TRUE.equals(getModel().getValue(FIELD_RISK_FLAG));
        }

        private boolean shouldSkipSubmitReview(OperateOption option) {
            return "true".equalsIgnoreCase(option.getVariableValue(OPTION_SKIP_SUBMIT_REVIEW, "false"));
        }

        private void openSubmitReviewDialog() {
            FormShowParameter fsp = ShowParameterUtils.getForm(
                    REVIEW_FORM_ID,
                    OperationStatus.ADDNEW,
                    ShowType.Modal,
                    "720px",
                    "420px"
            );
            fsp.setCustomParam("billNo", getModel().getValue("billno"));
            fsp.setCustomParam("customItem", resolveCustomItem());
            fsp.setCloseCallBack(new CloseCallBack(this, REVIEW_CALLBACK_ID));
            getView().showForm(fsp);
        }

        private String resolveCustomItem() {
            Object customTag = getModel().getValue(FIELD_CUSTOM_TAG);
            String text = customTag == null ? "" : String.valueOf(customTag).trim();
            return CharSequenceUtils.isBlank(text) ? "default-tag" : text;
        }

        private boolean isConfirmed(Map<String, Object> returnData) {
            return Boolean.TRUE.equals(returnData.get("confirmed"));
        }

        private String extractReviewComment(Map<String, Object> returnData) {
            Object comment = returnData.get("reviewComment");
            return comment == null ? "" : String.valueOf(comment).trim();
        }
    }

    /**
     * 操作插件侧：读取界面参数并回写结果摘要。
     */
    public static class SaveSubmitOpSide extends AbstractOperationServicePlugInExt {
        @Override
        public void beginOperationTransaction(BeginOperationTransactionArgs e) {
            super.beginOperationTransaction(e);
            if (!OP_SUBMIT.equals(e.getOperationKey())) {
                return;
            }

            String customItem = getOption().getVariableValue(OPTION_CUSTOM_ITEM, "");
            String reviewComment = getOption().getVariableValue(OPTION_REVIEW_COMMENT, "");
            for (DynamicObject bill : e.getDataEntities()) {
                if (CharSequenceUtils.isNotBlank(customItem)) {
                    bill.set("remark", appendRemark(bill.getString("remark"), customItem));
                }
                if (CharSequenceUtils.isNotBlank(reviewComment)) {
                    bill.set(FIELD_REVIEW_COMMENT, reviewComment);
                }
            }
        }

        @Override
        public void afterExecuteOperationTransaction(AfterOperationArgs e) {
            super.afterExecuteOperationTransaction(e);
            if (!OP_SUBMIT.equals(e.getOperationKey()) || !getOperationResult().isSuccess()) {
                return;
            }

            List<String> billNoList = new ArrayList<>();
            boolean hasRiskBill = false;
            for (DynamicObject bill : e.getDataEntities()) {
                String billNo = bill.getString("billno");
                if (CharSequenceUtils.isNotBlank(billNo)) {
                    billNoList.add(billNo);
                }
                hasRiskBill = hasRiskBill || isRiskBill(bill);
            }
            getOption().setVariableValue(OPTION_ALL_BILL_NO, String.join("、", billNoList));
            if (hasRiskBill) {
                getOption().setVariableValue(OPTION_WARNING_SUMMARY, "命中风险复核，已按复核意见继续提交");
            }
        }

        private String appendRemark(String remark, String customItem) {
            String line = String.format(
                    ResManager.loadKDString("界面参数:%s", "OperationOptionBridgeSample_0", "kd-cd-common-snippets"),
                    customItem
            );
            if (CharSequenceUtils.isBlank(remark)) {
                return line;
            }
            return remark + System.lineSeparator() + line;
        }

        private boolean isRiskBill(DynamicObject bill) {
            return Boolean.TRUE.equals(bill.get(FIELD_RISK_FLAG));
        }
    }
}
