/**
 * 分录行遍历与表头汇总示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：DynamicObjectUtils、AbstractFormPluginExt
 * 原生兜底：PropertyChangedArgs、AfterAddRowEventArgs、AfterDeleteRowEventArgs
 * 相关 lint 规则：SCENE-008、STYLE-014、STYLE-015
 * <p>
 * 使用场景：
 * 1. 数量/单价/税率变化后，回写当前行金额、税额；
 * 2. 分录新增/删除后，刷新表头汇总字段；
 * 3. 避免在 propertyChanged 里做重查询，只处理当前行和表头汇总。
 * 参考写法：优先复用 getModel().getEntryEntity(...) + DynamicObjectUtils.sumOf(...)。
 */
package kd.cd.common.snippets.form;

import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.entity.datamodel.events.AfterAddRowEventArgs;
import kd.bos.entity.datamodel.events.AfterDeleteRowEventArgs;
import kd.bos.entity.datamodel.events.PropertyChangedArgs;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.BigDecimalUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class EntryRowCalculateSample extends AbstractFormPluginExt {
    private static final String ENTRY_KEY = "billentry";
    private static final String FIELD_QTY = "qty";
    private static final String FIELD_PRICE = "price";
    private static final String FIELD_TAX_RATE = "taxrate";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_TAX_AMOUNT = "taxamount";
    private static final String FIELD_TOTAL_QTY = "totalqty";
    private static final String FIELD_TOTAL_AMOUNT = "totalamount";
    private static final String FIELD_TOTAL_TAX_AMOUNT = "totaltaxamount";

    // --- 数量/单价/税率变化后，只重算当前行，再刷新表头汇总 ---
    @Override
    public void propertyChanged(PropertyChangedArgs e) {
        super.propertyChanged(e);
        String fieldKey = e.getProperty().getName();
        if (!isCalcField(fieldKey)) {
            return;
        }
        int rowIndex = getChangedRowIndex(e);
        recalcEntryRow(rowIndex);
        recalcEntrySummary();
    }

    // --- 新增分录后初始化默认值，并刷新表头汇总 ---
    @Override
    public void afterAddRow(AfterAddRowEventArgs e) {
        super.afterAddRow(e);
        if (ENTRY_KEY.equals(e.getEntryProp().getName())) {
            int rowIndex = getModel().getEntryCurrentRowIndex(ENTRY_KEY);
            getModel().beginInit();
            getModel().setValue(FIELD_QTY, BigDecimal.ZERO, rowIndex);
            getModel().setValue(FIELD_PRICE, BigDecimal.ZERO, rowIndex);
            getModel().setValue(FIELD_TAX_RATE, BigDecimal.ZERO, rowIndex);
            getModel().setValue(FIELD_AMOUNT, BigDecimal.ZERO, rowIndex);
            getModel().setValue(FIELD_TAX_AMOUNT, BigDecimal.ZERO, rowIndex);
            getModel().endInit();
            recalcEntrySummary();
        }
    }

    // --- 删除分录后触发重算 ---
    @Override
    public void afterDeleteRow(AfterDeleteRowEventArgs e) {
        super.afterDeleteRow(e);
        if (ENTRY_KEY.equals(e.getEntryProp().getName())) {
            recalcEntrySummary();
        }
    }

    private boolean isCalcField(String fieldKey) {
        return FIELD_QTY.equals(fieldKey)
                || FIELD_PRICE.equals(fieldKey)
                || FIELD_TAX_RATE.equals(fieldKey);
    }

    // --- 当前行金额、税额联动 ---
    private void recalcEntryRow(int rowIndex) {
        BigDecimal qty = BigDecimalUtils.nullToZero((BigDecimal) getModel().getValue(FIELD_QTY, rowIndex));
        BigDecimal price = BigDecimalUtils.nullToZero((BigDecimal) getModel().getValue(FIELD_PRICE, rowIndex));
        BigDecimal taxRate = BigDecimalUtils.nullToZero((BigDecimal) getModel().getValue(FIELD_TAX_RATE, rowIndex));

        BigDecimal amount = BigDecimalUtils.multiply(qty, price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = BigDecimalUtils.divide(
                BigDecimalUtils.multiply(amount, taxRate),
                BigDecimalUtils.valueOf(100),
                2,
                RoundingMode.HALF_UP
        );

        getModel().beginInit();
        getModel().setValue(FIELD_AMOUNT, amount, rowIndex);
        getModel().setValue(FIELD_TAX_AMOUNT, taxAmount, rowIndex);
        getModel().endInit();
    }

    // --- 表头汇总 ---
    private void recalcEntrySummary() {
        DynamicObjectCollection entryRows = getModel().getEntryEntity(ENTRY_KEY);

        BigDecimal totalQty = DynamicObjectUtils.sumOf(entryRows, FIELD_QTY);
        BigDecimal totalAmount = DynamicObjectUtils.sumOf(entryRows, FIELD_AMOUNT);
        BigDecimal totalTaxAmount = DynamicObjectUtils.sumOf(entryRows, FIELD_TAX_AMOUNT);

        getModel().setValue(FIELD_TOTAL_QTY, totalQty);
        getModel().setValue(FIELD_TOTAL_AMOUNT, totalAmount);
        getModel().setValue(FIELD_TOTAL_TAX_AMOUNT, totalTaxAmount);
    }
}
