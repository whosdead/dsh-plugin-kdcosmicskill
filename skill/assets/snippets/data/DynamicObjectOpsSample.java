/**
 * DynamicObjectUtils 常见能力示例。
 * <p>
 * 适用插件：表单插件、操作插件、服务层
 * 优先封装：DynamicObjectUtils
 * 原生兜底：DynamicObject、DynamicObjectCollection 原生 API
 * 相关 lint 规则：STYLE-006、STYLE-015
 * <p>
 * 使用场景：
 * 1. 脱离表单模型后，直接操作 DynamicObject / DynamicObjectCollection；
 * 2. 安全取值、批量收集字段值、对象克隆与序列化；
 * 3. 不再重复表单层的 getModel().getValue / setValue 写法。
 */
package kd.cd.common.snippets.data;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DynamicObjectOpsSample {

    // ==================== 场景1：原生取值 ====================

    public static Object getValue(DynamicObject bill, String fieldKey) {
        return DynamicObjectUtils.nullSafeGet(bill, fieldKey);
    }

    public static Object nullSafeGet(DynamicObject bill, String fieldPath) {
        return DynamicObjectUtils.nullSafeGet(bill, fieldPath);
    }

    public static String getBaseDataNumber(DynamicObject bill, String fieldKey) {
        Object number = DynamicObjectUtils.nullSafeGet(bill, fieldKey + ".number");
        return number == null ? "" : String.valueOf(number);
    }

    // ==================== 特殊情况：基础资料属性字段（BasedataPropField）取值 ====================
    // BasedataPropField 是基础资料的派生显示字段，它的标识（如 e_materialname）是表单控件标识，
    // 在 DynamicObject 上取值时必须使用元数据中的「引用关系」路径。
    //
    // 示例：元数据查询结果
    //   标识: e_materialname | 类型: BasedataPropField | 引用关系: e_material.name
    //
    // ✅ 正确：使用引用关系路径
    //   DynamicObjectUtils.nullSafeGet(entryRow, "e_material.name");
    //
    // ❌ 错误：使用控件标识（DynamicObject 上不存在该属性）
    //   DynamicObjectUtils.nullSafeGet(entryRow, "e_materialname");

    // ==================== 场景2：原生赋值 ====================

    public static void setValue(DynamicObject bill, String fieldKey, Object value) {
        if (bill != null) {
            bill.set(fieldKey, value);
        }
    }

    public static void batchSetValues(DynamicObject bill, Map<String, Object> fieldValues) {
        if (CollectionUtils.isEmpty(fieldValues)) {
            return;
        }
        fieldValues.forEach(bill::set);
    }

    // ==================== 场景3：分录集合操作 ====================

    public static DynamicObjectCollection getEntryRows(DynamicObject bill, String entryKey) {
        return bill.getDynamicObjectCollection(entryKey);
    }

    public static BigDecimal sumEntryAmount(DynamicObject bill, String entryKey, String amountField) {
        return DynamicObjectUtils.sumOf(getEntryRows(bill, entryKey), amountField);
    }

    public static Set<Object> collectEntryIds(Collection<DynamicObject> rows) {
        return CollectionUtils.isEmpty(rows) ? Collections.emptySet() : DynamicObjectUtils.setOfIds(rows);
    }

    public static List<String> collectEntryNumbers(Collection<DynamicObject> rows, String numberField) {
        return CollectionUtils.isEmpty(rows) ? Collections.emptyList() : DynamicObjectUtils.listOf(rows, numberField);
    }

    // ==================== 场景4：调试/排查 ====================

    public static boolean containsProperty(DynamicObject bill, String fieldKey) {
        return DynamicObjectUtils.containsKey(bill, fieldKey);
    }

    public static Map<String, Object> dumpBill(DynamicObject bill) {
        return DynamicObjectUtils.dump(bill);
    }
}
