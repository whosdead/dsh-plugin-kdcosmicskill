/**
 * View 生命周期与页面控制示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：AbstractFormPluginExt、PageCache
 * 原生兜底：PreOpenFormEventArgs、BeforeClosedEvent、Toolbar
 * 相关 lint 规则：SCENE-006、SCENE-007、SCENE-008
 * <p>
 * 使用场景：
 * 1. preOpenForm 阶段决定是否允许打开页面；
 * 2. afterBindData 阶段按新增/编辑/审核状态控制可见性和锁定性；
 * 3. 局部刷新、工具栏重放、页面关闭、pageCache 暂存；
 * 4. 运行时修改控件标题。
 */
package kd.cd.common.snippets.form;

import kd.bos.bill.OperationStatus;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.form.FormShowParameter;
import kd.bos.form.control.Toolbar;
import kd.bos.form.events.BeforeClosedEvent;
import kd.bos.form.events.PreOpenFormEventArgs;
import kd.cd.common.plugin.AbstractFormPluginExt;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;

public class ViewControlOpsSample extends AbstractFormPluginExt {
    private static final String FIELD_TOTAL_AMOUNT = "totalamount";
    private static final String FIELD_AUDITOR = "auditor";
    private static final String ENTRY_KEY = "entryentity";
    private static final String ENTRY_DESC = "dealdesc";
    private static final String MAIN_TOOLBAR = "tbmain";
    private static final String PAGE_CACHE_SKIP_DIRTY_CHECK = "skip_dirty_check";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    @Override
    public void preOpenForm(PreOpenFormEventArgs e) {
        super.preOpenForm(e);
        if (LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY) {
            e.setCancel(true);
            e.setCancelMessage(ResManager.loadKDString("对不起，周日不能制单。", "ViewControlOpsSample_0", RES_APP_ID));
        }
    }

    @Override
    public void afterBindData(EventObject e) {
        super.afterBindData(e);
        FormShowParameter showParameter = getView().getFormShowParameter();
        OperationStatus status = showParameter.getStatus();
        if (OperationStatus.ADDNEW.equals(status)) {
            getView().showTipNotification(ResManager.loadKDString("当前是新增页面。", "ViewControlOpsSample_1", RES_APP_ID));
        } else if (OperationStatus.EDIT.equals(status)) {
            getView().showTipNotification(ResManager.loadKDString("当前是编辑页面。", "ViewControlOpsSample_2", RES_APP_ID));
        }

        lockAlwaysReadonlyFields();
        toggleAuditFieldVisibility();
        focusFirstEntryRow();
    }

    public void lockAlwaysReadonlyFields() {
        getView().setEnable(false, FIELD_TOTAL_AMOUNT);

        int rowCount = getModel().getEntryRowCount(ENTRY_KEY);
        for (int row = 0; row < rowCount; row++) {
            getView().setEnable(false, row, ENTRY_DESC);
        }
    }

    public void toggleAuditFieldVisibility() {
        String billStatus = String.valueOf(getModel().getValue("billstatus"));
        getView().setVisible("C".equalsIgnoreCase(billStatus), FIELD_AUDITOR);
    }

    public void focusFirstEntryRow() {
        if (getModel().getEntryRowCount(ENTRY_KEY) > 0) {
            getModel().setEntryCurrentRowIndex(ENTRY_KEY, 0);
        }
    }

    public void replaySaveToolbar() {
        Toolbar toolbar = getControl(MAIN_TOOLBAR);
        if (toolbar != null) {
            toolbar.itemClick("bar_save", "save");
        }
    }

    public void refreshLocally(int rowIndex) {
        getView().updateView(FIELD_TOTAL_AMOUNT);
        if (rowIndex >= 0) {
            getView().updateView(ENTRY_DESC, rowIndex);
        }
        getView().updateView(ENTRY_KEY);
    }

    public void refreshFromDatabase() {
        getView().invokeOperation("refresh");
    }

    public void renameControlCaption(String controlKey, String caption) {
        Map<String, Object> text = new HashMap<>(1);
        text.put("zh_CN", caption);

        Map<String, Object> payload = new HashMap<>(1);
        payload.put("text", text);
        getView().updateControlMetadata(controlKey, payload);
    }

    public void rememberInPageCache(String key, String value) {
        getPageCache().put(key, value);
    }

    public String readFromPageCache(String key) {
        return getPageCache().get(key);
    }

    @Override
    public void beforeClosed(BeforeClosedEvent e) {
        super.beforeClosed(e);
        if ("true".equalsIgnoreCase(getPageCache().get(PAGE_CACHE_SKIP_DIRTY_CHECK))) {
            e.setCheckDataChange(false);
        }
    }

    public void closeCurrentPage() {
        getView().close();
    }
}
