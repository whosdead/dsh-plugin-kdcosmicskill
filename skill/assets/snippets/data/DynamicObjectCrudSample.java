/**
 * 后端数据对象批量新建 / 批量保存 / 批量查询 / 批量复制示例。
 * <p>
 * 适用插件：操作插件、服务层、后台任务
 * 优先封装：DynamicObjectUtils、OpUtils
 * 原生兜底：BusinessDataServiceHelper、SaveServiceHelper、QueryServiceHelper
 * 相关 lint 规则：STYLE-003、STYLE-008、STYLE-015
 * <p>
 * 使用场景：
 * 1. 在服务层或操作插件中一次性构建多张单据数据包；
 * 2. 批量保存、批量提交、批量复制备份；
 * 3. 按主键集合、表头条件、单据体条件批量查询数据。
 */
package kd.cd.common.snippets.data;

import kd.bos.dataentity.OperateOption;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.bos.servicehelper.operation.SaveServiceHelper;
import kd.cd.common.operate.OpUtils;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DynamicObjectCrudSample {
    private static final String FORM_ID = "kded_simplebill";
    private static final String ENTRY_KEY = "entryentity";
    private static final String SUBENTRY_KEY = "subentryentity";
    private static final String FIELD_BILL_NO = "billno";
    private static final String FIELD_STATUS = "billstatus";
    private static final String FIELD_REMARK = "remark";
    private static final String FIELD_ENTRY_TEXT = "kded_dealdesc";
    private static final String FIELD_SUB_TEXT = "kded_subdec";

    /**
     * 批量构建表头数据包，适合导入暂存、批量生成草稿单据。
     */
    public DynamicObject[] newBills(List<String> billNos) {
        List<DynamicObject> bills = new ArrayList<>(billNos.size());
        int index = 1;
        for (String billNo : billNos) {
            DynamicObject bill = DynamicObjectUtils.newDynamicObject(FORM_ID);
            bill.set(FIELD_BILL_NO, billNo);
            bill.set(FIELD_STATUS, "A");
            bill.set(FIELD_REMARK, String.format(
                    ResManager.loadKDString("批量代码创建-%s", "DynamicObjectCrudSample_0", "kd-cd-common-snippets"),
                    index
            ));
            bills.add(bill);
            index++;
        }
        return bills.toArray(new DynamicObject[0]);
    }

    /**
     * 批量构建含单据体 / 子单据体的数据包，适合批量造单后统一保存。
     */
    public DynamicObject[] newBillsWithEntryAndSubEntry(List<String> billNos) {
        DynamicObject[] bills = newBills(billNos);
        for (int index = 0; index < bills.length; index++) {
            DynamicObject bill = bills[index];
            DynamicObjectCollection entryRows = bill.getDynamicObjectCollection(ENTRY_KEY);
            DynamicObject entryRow = entryRows.addNew();
            entryRow.set(FIELD_ENTRY_TEXT, String.format(
                    ResManager.loadKDString("批量代码新增单据体-%s", "DynamicObjectCrudSample_1", "kd-cd-common-snippets"),
                    index + 1
            ));

            DynamicObjectCollection subRows = entryRow.getDynamicObjectCollection(SUBENTRY_KEY);
            DynamicObject subRow = subRows.addNew();
            subRow.set(FIELD_SUB_TEXT, String.format(
                    ResManager.loadKDString("批量代码新增子单据体-%s", "DynamicObjectCrudSample_2", "kd-cd-common-snippets"),
                    index + 1
            ));
        }
        return bills;
    }

    /**
     * 批量保存并触发保存校验 / 保存插件。
     */
    public void saveOpBatch(Collection<DynamicObject> bills) {
        if (CollectionUtils.isNotEmpty(bills)) {
            SaveServiceHelper.saveOperate(FORM_ID, bills.toArray(new DynamicObject[0]), OperateOption.create());
        }
    }

    /**
     * 批量直接保存，适合已完成预校验的后台修数 / 中间表同步。
     */
    public void saveBatchDirectly(Collection<DynamicObject> bills) {
        if (CollectionUtils.isNotEmpty(bills)) {
            SaveServiceHelper.save(bills.toArray(new DynamicObject[0]));
        }
    }

    /**
     * 批量提交，失败抛出异常，适合保存后统一提交流程。
     */
    public void submitBatch(Collection<DynamicObject> bills) {
        if (CollectionUtils.isNotEmpty(bills)) {
            OpUtils.executeOperateOrThrow("submit", FORM_ID, bills.toArray(new DynamicObject[0]));
        }
    }

    /**
     * 按主键集合批量加载实体包，适合后续统一修改后再保存。
     */
    public DynamicObject[] loadByPks(Collection<?> pkValues) {
        return BusinessDataServiceHelper.load(
                FORM_ID,
                "id,billno,billstatus,remark," + ENTRY_KEY + "." + FIELD_ENTRY_TEXT,
                new QFilter("id", QCP.in, pkValues).toArray()
        );
    }

    /**
     * 按创建人批量查询单据，适合批量重算、批量审核前的候选集准备。
     */
    public DynamicObject[] queryByCreatorNames(Collection<String> creatorNames) {
        return BusinessDataServiceHelper.load(
                FORM_ID,
                "id,billno,creator,creator.id,createtime",
                new QFilter("creator.name", QCP.in, creatorNames).toArray()
        );
    }

    /**
     * 按分录业务员批量查询扁平结果，适合做分组、校验、汇总，不适合直接回写保存。
     */
    public DynamicObjectCollection queryByEntryUserNames(Collection<String> userNames) {
        return QueryServiceHelper.query(
                FORM_ID,
                "id,billno,entryentity.kded_dealuser.name,entryentity.kded_dealdesc",
                new QFilter("entryentity.kded_dealuser.name", QCP.in, userNames).toArray()
        );
    }

    // ==================== 特殊情况：基础资料属性字段（BasedataPropField）查询取值 ====================
    // BasedataPropField 是基础资料的派生显示字段，它的标识（如 e_materialname）是表单控件标识，
    // 在查询的 select 字段和 QFilter 中必须使用元数据中的「引用关系」路径。
    //
    // 示例：元数据查询结果
    //   标识: e_materialname | 类型: BasedataPropField | 引用关系: e_material.name
    //
    // ✅ 正确：使用引用关系路径
    //   select: "entryentity.e_material.name"
    //   filter: new QFilter("entryentity.e_material.name", QCP.like, "%某物料%")
    //
    // ❌ 错误：使用控件标识（查询时无法识别）
    //   select: "entryentity.e_materialname"

    /**
     * 按分录物料名称查询，演示 BasedataPropField 在查询中的正确用法。
     */
    public DynamicObjectCollection queryByMaterialName(String materialName) {
        return QueryServiceHelper.query(
                FORM_ID,
                // ✅ select 中使用引用关系路径 e_material.name，而非控件标识 e_materialname
                "id,billno," + ENTRY_KEY + ".e_material.name," + ENTRY_KEY + ".e_material.number",
                new QFilter(ENTRY_KEY + ".e_material.name", QCP.like, "%" + materialName + "%").toArray()
        );
    }

    /**
     * 批量复制单据作为备份包，适合修改前先批量做快照。
     */
    public DynamicObject[] cloneBatchForBackup(Collection<DynamicObject> origins) {
        List<DynamicObject> backups = new ArrayList<>(origins.size());
        for (DynamicObject origin : origins) {
            if (origin == null) {
                continue;
            }
            DynamicObject backup = DynamicObjectUtils.clone(origin);
            backup.set(FIELD_REMARK, String.format(
                    ResManager.loadKDString("批量备份-%s", "DynamicObjectCrudSample_3", "kd-cd-common-snippets"),
                    origin.getString(FIELD_BILL_NO)
            ));
            backups.add(backup);
        }
        return backups.toArray(new DynamicObject[0]);
    }

    /**
     * 把批量加载结果转成主键映射，便于后续本地批量反写。
     */
    public Map<Object, DynamicObject> toPkMap(Collection<?> pkValues) {
        return Arrays.stream(loadByPks(pkValues)).collect(Collectors.toMap(
                DynamicObject::getPkValue,
                bill -> bill,
                (left, right) -> left
        ));
    }
}
