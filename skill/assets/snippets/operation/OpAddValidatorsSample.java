/**
 * onPreparePropertys + onAddValidators 示例。
 * <p>
 * 适用插件：操作插件
 * 优先封装：AbstractOperationServicePlugInExt、AbstractValidatorExt、DynamicObjectUtils
 * 原生兜底：PreparePropertysEventArgs、AddValidatorsEventArgs、ExtendedDataEntity
 * 相关 lint 规则：STYLE-003、STYLE-010
 * <p>
 * 使用场景：
 * 1. preparePropertys 显式准备头/体字段
 * 2. onAddValidators 按规则拆分校验器
 * 3. 单据头校验、分录聚合校验、分录行级校验分开处理
 * 4. 错误信息挂到操作结果里，而不是直接抛异常
 */
package kd.cd.common.snippets.operation;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.entity.ExtendedDataEntity;
import kd.bos.entity.plugin.AddValidatorsEventArgs;
import kd.bos.entity.plugin.PreparePropertysEventArgs;
import kd.cd.common.plugin.AbstractOperationServicePlugInExt;
import kd.cd.common.plugin.AbstractValidatorExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.BigDecimalUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OpAddValidatorsSample extends AbstractOperationServicePlugInExt {
    private static final String TARGET_BILL_TYPE = "kdcd_measuresettlebill_B_02";
    private static final String FIELD_BILL_NO = "billno";
    private static final String FIELD_CONTRACT = "kdcd_contract";
    private static final String FIELD_BILL_TYPE = "kdcd_billtype";
    private static final String FIELD_INITIALIZATION = "kdcd_initialization";
    private static final String ENTRY_MEASURE_DETAIL = "kdcd_measuredelail";
    private static final String FIELD_TOTAL_TAX = "kdcd_totaltax";
    private static final String ENTRY_INVOICE_DETAIL = "kdcd_invoicedetail";
    private static final String FIELD_PRICE_TAX_TOTAL = "kdcd_pricetaxtotal";
    private static final String FIELD_SEQ = "seq";

    @Override
    public void onPreparePropertys(PreparePropertysEventArgs e) {
        super.onPreparePropertys(e);
        List<String> fieldKeys = e.getFieldKeys();
        fieldKeys.add(FIELD_BILL_NO);
        fieldKeys.add(FIELD_CONTRACT);
        fieldKeys.add(FIELD_CONTRACT + ".number");
        fieldKeys.add(FIELD_BILL_TYPE);
        fieldKeys.add(FIELD_BILL_TYPE + ".number");
        fieldKeys.add(FIELD_INITIALIZATION);
        fieldKeys.addAll(entryFields(ENTRY_MEASURE_DETAIL, ENTRY_INVOICE_DETAIL));
    }

    @Override
    public void onAddValidators(AddValidatorsEventArgs e) {
        super.onAddValidators(e);
        e.addValidator(new ContractRequiredValidator());
        e.addValidator(new InvoiceAmountEqualsValidator());
        e.addValidator(new InvoiceAmountPositiveValidator());
    }

    private static class ContractRequiredValidator extends AbstractValidatorExt {
        @Override
        public void validate() {
            for (ExtendedDataEntity ext : getDataEntities()) {
                DynamicObject bill = ext.getDataEntity();
                if (shouldSkip(bill)) {
                    continue;
                }
                if (bill.getDynamicObject(FIELD_CONTRACT) == null) {
                    addMessageWithErrCode(ext, "kdcd_contract_required", "合同不能为空");
                }
            }
        }
    }

    private static class InvoiceAmountEqualsValidator extends AbstractValidatorExt {
        @Override
        public void validate() {
            for (ExtendedDataEntity ext : getDataEntities()) {
                DynamicObject bill = ext.getDataEntity();
                if (shouldSkip(bill)) {
                    continue;
                }

                DynamicObjectCollection measureRows = bill.getDynamicObjectCollection(ENTRY_MEASURE_DETAIL);
                DynamicObjectCollection invoiceRows = bill.getDynamicObjectCollection(ENTRY_INVOICE_DETAIL);
                BigDecimal measureTotal = DynamicObjectUtils.sumOf(measureRows, FIELD_TOTAL_TAX);
                BigDecimal invoiceTotal = DynamicObjectUtils.sumOf(invoiceRows, FIELD_PRICE_TAX_TOTAL);
                if (!BigDecimalUtils.equals(measureTotal, invoiceTotal)) {
                    addMessageWithErrCode(ext, "kdcd_invoice_amount_not_match", "发票明细价税合计必须等于计量明细本期价税合计");
                }
            }
        }
    }

    private static class InvoiceAmountPositiveValidator extends AbstractValidatorExt {
        @Override
        public void validate() {
            for (ExtendedDataEntity ext : getDataEntities()) {
                DynamicObject bill = ext.getDataEntity();
                if (shouldSkip(bill)) {
                    continue;
                }

                DynamicObjectCollection invoiceRows = bill.getDynamicObjectCollection(ENTRY_INVOICE_DETAIL);
                List<String> invalidSeqs = invoiceRows.stream()
                        .filter(row -> !BigDecimalUtils.largeThanZero(
                                BigDecimalUtils.nullToZero(row.getBigDecimal(FIELD_PRICE_TAX_TOTAL))))
                        .map(row -> String.valueOf(row.get(FIELD_SEQ)))
                        .collect(Collectors.toList());
                if (!invalidSeqs.isEmpty()) {
                    addMessageWithErrCode(
                            ext,
                            "kdcd_invoice_row_amount_not_positive",
                            String.format(
                                    ResManager.loadKDString("发票明细第%s行价税合计必须大于 0", "OpAddValidatorsSample_0", "kd-cd-common-snippets"),
                                    String.join("、", invalidSeqs)
                            )
                    );
                }
            }
        }
    }

    private static boolean isTargetBill(DynamicObject bill) {
        return TARGET_BILL_TYPE.equals(DynamicObjectUtils.nullSafeGet(bill, FIELD_BILL_TYPE + ".number"));
    }

    private static boolean shouldSkip(DynamicObject bill) {
        return !isTargetBill(bill) || Boolean.TRUE.equals(DynamicObjectUtils.nullSafeGet(bill, FIELD_INITIALIZATION));
    }
}
