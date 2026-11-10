/**
 * 列表插件基础交互示例。
 * <p>
 * 适用插件：列表插件
 * 优先封装：ShowParameterUtils、FilterUtils
 * 原生兜底：SetFilterEvent、PackageDataEvent、HyperLinkClickArgs
 * 相关 lint 规则：STYLE-002、STYLE-013
 * <p>
 * 使用场景：
 * 1. 列表界面加载时动态追加过滤条件；
 * 2. 操作列按钮按行状态动态显隐；
 * 3. 列数据格式化显示；
 * 4. 列表操作后打开关联页面。
 */
package kd.cd.common.snippets.list;

import kd.bos.bill.BillShowParameter;
import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.entity.datamodel.ListSelectedRow;
import kd.bos.entity.datamodel.ListSelectedRowCollection;
import kd.bos.entity.datamodel.events.PackageDataEvent;
import kd.bos.entity.list.column.ColumnDesc;
import kd.bos.form.IPageCache;
import kd.bos.form.ShowType;
import kd.bos.form.events.AfterDoOperationEventArgs;
import kd.bos.form.events.HyperLinkClickArgs;
import kd.bos.form.events.SetFilterEvent;
import kd.bos.form.operatecol.OperationColItem;
import kd.bos.list.BillList;
import kd.bos.list.ListShowParameter;
import kd.bos.list.column.ListOperationColumnDesc;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.cd.common.form.ShowParameterUtils;
import kd.cd.common.plugin.AbstractListPluginExt;
import kd.cd.common.util.DynamicObjectUtils;
import kd.cd.common.util.FilterUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

public class ListPluginBasicSample extends AbstractListPluginExt {
    private static final String LOG_FORM_ID = "outapilog";
    private static final String SHOW_LOG_OP = "showlog";
    private static final String STATUS = "status";
    private static final String ENABLE = "enable";
    private static final String FUZZY_CACHE_KEY = "fuzzyfilterkey";

    // --- setFilter: 列表加载时追加过滤条件 ---
    @Override
    public void setFilter(SetFilterEvent e) {
        super.setFilter(e);
        IPageCache cache = getView().getPageCache();
        String fuzzyFilterStr = cache.get(FUZZY_CACHE_KEY);
        if (fuzzyFilterStr != null) {
            e.setCustomQFilters(FilterUtils.deSerializeQFilter(fuzzyFilterStr));
        }

        // 特殊权限过滤：非管理员只能看未标记的数据
        QFilter permFilter = new QFilter("require_super_auth", QCP.equals, false);
        QFilter specialDataPermFilter = e.getSpecialDataPermQFilter();
        if (specialDataPermFilter != null) {
            specialDataPermFilter.and(permFilter);
        } else {
            e.setSpecialDataPermQFilter(permFilter);
        }
    }

    // --- packageData: 操作列按钮按行状态动态显隐、列数据格式化 ---
    @SuppressWarnings("unchecked")
    @Override
    public void packageData(PackageDataEvent e) {
        Object source = e.getSource();

        // 操作列按钮显隐控制
        if (source instanceof ListOperationColumnDesc) {
            DynamicObject rowData = e.getRowData();
            boolean isAudited = "C".equals(DynamicObjectUtils.nullSafeGet(rowData, STATUS));
            boolean isEnabled = "1".equals(DynamicObjectUtils.nullSafeGet(rowData, ENABLE));

            List<OperationColItem> items = (List<OperationColItem>) e.getFormatValue();
            for (OperationColItem item : items) {
                if (SHOW_LOG_OP.equals(item.getOperationKey())) {
                    item.setVisible(isAudited && isEnabled);
                }
            }
            return;
        }

        // 列数据格式化
        if (source instanceof ColumnDesc) {
            String fieldKey = ((ColumnDesc) source).getFieldKey();
            if ("port".equals(fieldKey)) {
                Integer port = (Integer) e.getRowData().get("port");
                e.setFormatValue(port == null || port == 0 ? "-" : String.valueOf(port));
            } else if ("connectstate".equals(fieldKey)) {
                DynamicObject rowData = e.getRowData();
                if (!"C".equals(DynamicObjectUtils.nullSafeGet(rowData, STATUS))) {
                    e.setFormatValue("");
                }
            }
        }
    }

    // --- afterDoOperation: 操作完成后打开关联页面 ---
    @Override
    public void afterDoOperation(AfterDoOperationEventArgs args) {
        super.afterDoOperation(args);
        String opKey = getOpKey(args);
        if (!SHOW_LOG_OP.equals(opKey)) {
            return;
        }

        BillList billList = getBillList();
        ListSelectedRow rowInfo = billList.getCurrentSelectedRowInfo();
        if (rowInfo == null) {
            return;
        }

        String number = rowInfo.getNumber();
        String name = rowInfo.getName();

        ListShowParameter lsp = ShowParameterUtils.getList(LOG_FORM_ID, ShowType.MainNewTabPage, true);
        lsp.getListFilterParameter().getQFilters().add(new QFilter("opname", QCP.equals, number));
        if (name != null) {
            lsp.setCaption(String.format(
                    ResManager.loadKDString("%s-日志", "ListPluginBasicSample_0", "kd-cd-common-snippets"),
                    name
            ));
        }
        billList.clearSelection();
        getView().showForm(lsp);
    }

    // --- billListHyperLinkClick: 拦截默认打开逻辑，改成自定义弹窗 ---
    @Override
    public void billListHyperLinkClick(HyperLinkClickArgs args) {
        if (!Objects.equals("billno", args.getHyperLinkClickEvent().getFieldName())) {
            return;
        }
        args.setCancel(true);

        ListSelectedRowCollection rows = getCurrentListAllRowCollection();
        int rowIndex = args.getRowIndex();
        if (rows == null || rowIndex < 0 || rowIndex >= rows.size()) {
            return;
        }

        ListSelectedRow rowInfo = rows.get(rowIndex);
        BillShowParameter showParameter = ShowParameterUtils.getBill(
                rowInfo.getFormID(),
                rowInfo.getPrimaryKeyValue(),
                OperationStatus.EDIT,
                ShowType.Modal
        );
        getView().showForm(showParameter);
    }

    public ListSelectedRowCollection currentSelections() {
        ListSelectedRowCollection selectedRows = getSelectedRows();
        if (CollectionUtils.isNotEmpty(selectedRows)) {
            return selectedRows;
        }
        BillList billList = getBillList();
        return billList == null ? selectedRows : billList.getSelectedRows();
    }
}
