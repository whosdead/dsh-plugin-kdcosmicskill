/**
 * 确认框弹窗 + 回调处理示例。
 * <p>
 * 适用插件：表单插件、单据插件
 * 优先封装：AbstractFormPluginExt、PageCache
 * 原生兜底：ConfirmCallBackListener、MessageBoxOptions、Toolbar
 * 相关 lint 规则：SCENE-005、STYLE-013、RESOURCE-002
 * <p>
 * 使用场景：对分录批量标记、批量关闭、批量清理等动作做二次确认。
 * 关键点：
 * 1. 普通自定义按钮可在 itemClick 中直接弹确认框；
 * 2. 标准工具栏按钮更适合在 beforeItemClick 中先拦截，再在确认后重放原动作；
 * 3. pageCache 可用于避免确认回调后二次拦截。
 */
package kd.cd.common.snippets.form;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.form.ConfirmCallBackListener;
import kd.bos.form.MessageBoxOptions;
import kd.bos.form.MessageBoxResult;
import kd.bos.form.control.Toolbar;
import kd.bos.form.control.events.BeforeItemClickEvent;
import kd.bos.form.control.events.ItemClickEvent;
import kd.bos.form.events.MessageBoxClosedEvent;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.core.util.CollectionUtils;

import java.util.EventObject;
import java.util.List;

public class ConfirmDialogSample extends AbstractFormPluginExt {
    private static final String ENTRY_TOOLBAR = "tbmainentry";
    private static final String MAIN_TOOLBAR = "toolbarap";
    private static final String ITEM_MARK_SELECTED = "xxxx_markselected";
    private static final String CALLBACK_MARK_SELECTED = "xxxx_markselected_confirm";
    private static final String ITEM_COPY_HISTORY = "btn_copyhis";
    private static final String PAGE_CACHE_CONFIRM_COPY_HISTORY = "pagecache_confirm_copyhis";
    private static final String ENTRY_KEY = "entryentity";
    private static final String FIELD_MARKED = "xxxx_selected";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    // --- 在 registerListener 中注册工具栏按钮 ---
    @Override
    public void registerListener(EventObject e) {
        super.registerListener(e);
        addItemClickListeners(ENTRY_TOOLBAR, MAIN_TOOLBAR);
    }

    // --- 标准工具栏按钮：先拦截，再确认后重放原动作 ---
    @Override
    public void beforeItemClick(BeforeItemClickEvent evt) {
        super.beforeItemClick(evt);
        if (!ITEM_COPY_HISTORY.equals(evt.getItemKey())) {
            return;
        }

        if ("true".equals(getView().getPageCache().get(PAGE_CACHE_CONFIRM_COPY_HISTORY))) {
            getView().getPageCache().put(PAGE_CACHE_CONFIRM_COPY_HISTORY, "false");
            return;
        }

        evt.setCancel(true);
        getView().showConfirm(
                ResManager.loadKDString("获取上年同期会覆盖当前取数结果，确认继续吗？", "ConfirmDialogSample_0", RES_APP_ID),
                MessageBoxOptions.YesNo,
                new ConfirmCallBackListener(ITEM_COPY_HISTORY, this)
        );
    }

    // --- 按钮点击后先弹确认框 ---
    @Override
    public void itemClick(ItemClickEvent evt) {
        super.itemClick(evt);
        if (!ITEM_MARK_SELECTED.equals(evt.getItemKey())) {
            return;
        }

        List<DynamicObject> selectedRows = getEntrySelectRowsEntity(ENTRY_KEY);
        if (CollectionUtils.isEmpty(selectedRows)) {
            getView().showTipNotification(ResManager.loadKDString("请先选择要处理的分录行", "ConfirmDialogSample_1", RES_APP_ID));
            return;
        }

        ConfirmCallBackListener listener = new ConfirmCallBackListener(CALLBACK_MARK_SELECTED, this);
        getView().showConfirm(ResManager.loadKDString("确定要标记所选分录吗？", "ConfirmDialogSample_2", RES_APP_ID), MessageBoxOptions.YesNo, listener);
    }

    // --- 用户确认后执行实际逻辑 ---
    @Override
    public void confirmCallBack(MessageBoxClosedEvent evt) {
        super.confirmCallBack(evt);
        String callBackId = evt.getCallBackId();
        if (CALLBACK_MARK_SELECTED.equals(callBackId)) {
            if (MessageBoxResult.Yes.equals(evt.getResult())) {
                markSelectedRows();
            }
            return;
        }
        if (ITEM_COPY_HISTORY.equals(callBackId)) {
            tryReplayToolbarAction(evt);
        }
    }

    private void tryReplayToolbarAction(MessageBoxClosedEvent evt) {
        if (!MessageBoxResult.Yes.equals(evt.getResult())) {
            return;
        }
        getView().getPageCache().put(PAGE_CACHE_CONFIRM_COPY_HISTORY, "true");
        Toolbar toolbar = getControl(MAIN_TOOLBAR);
        toolbar.addItemClickListener(this);
        toolbar.itemClick(ITEM_COPY_HISTORY, "");
    }

    private void markSelectedRows() {
        List<DynamicObject> selectedRows = getEntrySelectRowsEntity(ENTRY_KEY);
        if (CollectionUtils.isEmpty(selectedRows)) {
            return;
        }

        for (DynamicObject row : selectedRows) {
            row.set(FIELD_MARKED, true);
        }

        getView().updateView(ENTRY_KEY);
        getView().showSuccessNotification(String.format(
                ResManager.loadKDString("已标记%s行分录", "ConfirmDialogSample_3", RES_APP_ID),
                selectedRows.size()
        ));
    }
}
