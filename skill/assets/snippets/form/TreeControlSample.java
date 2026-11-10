/**
 * 树形控件（TreeView）交互示例。
 * <p>
 * 适用插件：表单插件
 * 优先封装：AbstractFormPluginExt、PageCache
 * 原生兜底：TreeView、TreeNode、SerializationUtils
 * 相关 lint 规则：当前无专门规则，可参考 SCENE-005、RESOURCE-002
 * <p>
 * 使用场景：
 * 1. 动态构建实体树形结构；
 * 2. 树节点点击/双击事件处理；
 * 3. 树节点搜索定位；
 * 4. 多选/单选节点回传。
 */
package kd.cd.common.snippets.form;

import kd.bos.dataentity.resource.ResManager;
import kd.bos.dataentity.serialization.SerializationUtils;
import kd.bos.entity.tree.TreeNode;
import kd.bos.form.control.Search;
import kd.bos.form.control.TreeView;
import kd.bos.form.control.events.ItemClickEvent;
import kd.bos.form.control.events.SearchEnterEvent;
import kd.bos.form.control.events.SearchEnterListener;
import kd.bos.form.control.events.TreeNodeClickListener;
import kd.bos.form.control.events.TreeNodeEvent;
import kd.cd.common.plugin.AbstractFormPluginExt;
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;

public class TreeControlSample extends AbstractFormPluginExt implements TreeNodeClickListener, SearchEnterListener {
    private static final String TREE_CONTROL_KEY = "kdcd_treeviewap";
    private static final String ROOT_NODE_CACHE = "rootjson";
    private static final String BTN_OK = "kdcd_ok";
    private static final String MULTI_SELECT_PARAM = "mutiSelect";
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    // --- 注册树控件和搜索框监听 ---
    @Override
    public void registerListener(EventObject e) {
        super.registerListener(e);
        TreeView tree = getTreeView();
        tree.addTreeNodeClickListener(this);
        Search search = getControl("kdcd_searchap");
        search.addEnterListener(this);
        addItemClickListeners("kdcd_toolbarap");
    }

    // --- 初始化树结构 ---
    private void buildTree(String entityId, String entryKey, boolean multiSelect) {
        TreeView tree = getTreeView();
        String rootId = CharSequenceUtils.isBlank(entryKey) ? entityId : entryKey;
        TreeNode root = recoverRootNode();
        tree.addNode(root);
        tree.setRootVisible(true);
        tree.expand(rootId);
        tree.setMulti(multiSelect);
    }

    // --- 构建根节点（从自定义参数或缓存恢复） ---
    private TreeNode recoverRootNode() {
        String rootJsonString = getPageCache().get(ROOT_NODE_CACHE);
        return SerializationUtils.fromJsonString(rootJsonString, TreeNode.class);
    }

    // --- 树节点双击事件：选中并返回 ---
    @Override
    public void treeNodeDoubleClick(TreeNodeEvent evt) {
        closeAndReturnData();
    }

    // --- 搜索框回车事件：定位匹配节点 ---
    @Override
    public void search(SearchEnterEvent evt) {
        String searchText = evt.getText();
        if (CharSequenceUtils.isBlank(searchText)) {
            return;
        }

        TreeNode root = recoverRootNode();
        List<String> ids = loadSearchIds(root, searchText);

        if (CollectionUtils.isEmpty(ids)) {
            getPageCache().put(searchText, null);
            getView().showSuccessNotification(ResManager.loadKDString("已是最后一个", "TreeControlSample_0", RES_APP_ID));
            return;
        }

        // 定位到第一个匹配节点
        String currId = ids.get(0);
        ids.remove(0);
        getPageCache().put(searchText, SerializationUtils.toJsonString(ids));

        TreeNode currNode = root.getTreeNode(currId);
        getTreeView().focusNode(currNode);
    }

    // --- 确定按钮点击：返回选中数据 ---
    @Override
    public void itemClick(ItemClickEvent evt) {
        super.itemClick(evt);
        if (BTN_OK.equals(evt.getItemKey())) {
            closeAndReturnData();
        }
    }

    // --- 关闭页面并返回选中数据 ---
    private void closeAndReturnData() {
        TreeView tree = getTreeView();
        Object returnData = Boolean.TRUE.equals(getCustomParam(MULTI_SELECT_PARAM))
                ? tree.getTreeState().getSelectedNodes()
                : tree.getTreeState().getFocusNode();
        getView().returnDataToParent(returnData);
        getView().close();
    }

    @SuppressWarnings("unchecked")
    private List<String> loadSearchIds(TreeNode root, String searchText) {
        String cachedIds = getPageCache().get(searchText);
        if (cachedIds != null) {
            return SerializationUtils.fromJsonString(cachedIds, List.class);
        }
        List<TreeNode> matchedNodes = root.getTreeNodeListByText(new ArrayList<>(0), searchText, 10000);
        List<String> ids = new ArrayList<>(matchedNodes.size());
        for (TreeNode matchedNode : matchedNodes) {
            ids.add(matchedNode.getId());
        }
        getPageCache().put(searchText, SerializationUtils.toJsonString(ids));
        return ids;
    }

    // --- 获取树控件实例 ---
    private TreeView getTreeView() {
        return getControl(TREE_CONTROL_KEY);
    }
}
