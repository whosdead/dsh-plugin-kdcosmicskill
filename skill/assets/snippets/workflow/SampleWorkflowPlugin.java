package kd.cd.common.snippets;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.LocaleString;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.bos.servicehelper.botp.BFTrackerServiceHelper;
import kd.bos.servicehelper.operation.SaveServiceHelper;
import kd.bos.servicehelper.user.UserServiceHelper;
import kd.bos.servicehelper.workflow.MessageCenterServiceHelper;
import kd.bos.servicehelper.workflow.WorkflowServiceHelper;
import kd.bos.workflow.api.AgentExecution;
import kd.bos.workflow.api.SuspendInfo;
import kd.bos.workflow.api.constants.WFTaskResultEnum;
import kd.bos.workflow.component.approvalrecord.IApprovalRecordGroup;
import kd.bos.workflow.component.approvalrecord.IApprovalRecordItem;
import kd.bos.workflow.engine.extitf.IWorkflowPlugin;
import kd.bos.workflow.engine.msg.info.MessageInfo;
import kd.cd.common.entity.EntityUtils;
import kd.cd.common.util.DynamicObjectUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作流插件示例 —— IWorkflowPlugin 全场景 Cookbook。
 * <p>
 * 适用插件：工作流插件
 * 优先封装：（暂无 commons 封装）
 * 原生兜底：IWorkflowPlugin、AgentExecution、WorkflowServiceHelper
 * 相关 lint 规则：（暂无）
 * <p>
 * 使用场景：
 * 1. filterParticipant 过滤审批参与人（排除创建人、取上游源单创建人）；
 * 2. notify 审批节点回调（审批意见反写、拒绝通知第三方、写流程变量）；
 * 3. afterSuspendProcess 流程挂起通知单据创建人；
 * 4. calcUserIds 动态计算审批参与人；
 * 5. hasTrueCondition 条件分支判断；
 * 6. notifyByWithdraw 流程撤回补偿处理；
 * 7. WorkflowServiceHelper 辅助能力速查（inProcess / getAllApprovalRecord）。
 */
public class SampleWorkflowPlugin implements IWorkflowPlugin {
    private static final Log log = LogFactory.getLog(SampleWorkflowPlugin.class);

    // ===================================================================
    //  一、filterParticipant —— 过滤/替换审批参与人
    // ===================================================================

    /**
     * 场景 1：排除单据创建人，避免自己审批自己。
     * <p>
     * AgentExecution 上下文要点：
     * - getBusinessKey()  → 单据主键（字符串）
     * - getEntityNumber() → 单据实体编码
     */
    @Override
    public List<Long> filterParticipant(AgentExecution execution, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return userIds;
        }
        String billId = execution.getBusinessKey();
        String entityNum = execution.getEntityNumber();
        DynamicObject bill = BusinessDataServiceHelper.loadSingle(billId, entityNum);
        DynamicObject creator = bill.getDynamicObject("creator");
        Long creatorId = DynamicObjectUtils.nullSafeGet(creator, "id");
        if (EntityUtils.isNotEmptyPk(creatorId)) {
            userIds.remove(creatorId);
        }
        return userIds;
    }

    // ===================================================================
    //  二、notify —— 审批节点通知回调
    // ===================================================================

    /**
     * 场景 1：审批意见反写到单据字段。
     * <p>
     * 工作流传参 [nodeName] → 指定反写字段标识。
     * AgentExecution 上下文要点：
     * - getCurrentTaskResult(WFTaskResultEnum.auditMessage) → 审批意见（JSON，含多语言）
     * - getCurrentTaskResult(WFTaskResultEnum.auditName)    → 审批动作名称（同意/驳回）
     * - getCurrentTask().getAssigneeId()                    → 实际审批人 ID
     * - getCurrentWFPluginParams()                          → 工作流节点上配置的自定义参数
     */
    @Override
    public void notify(AgentExecution execution) {
        // 获取审批意见
        Object approvalObj = execution.getCurrentTaskResult(WFTaskResultEnum.auditMessage);
        if (approvalObj == null) {
            return;
        }
        // 从工作流节点参数中获取需要反写的字段标识
        String nodeName = execution.getCurrentWFPluginParams().get("nodeName").toString();
        String entityName = execution.getEntityNumber();
        String businessKey = execution.getBusinessKey();

        DynamicObject bill = BusinessDataServiceHelper.loadSingle(businessKey, entityName);
        // 审批人信息
        List<Map<String, Object>> userInfo = UserServiceHelper.get(
                Collections.singletonList(execution.getCurrentTask().getAssigneeId()));
        String approverName = userInfo.get(0).get("name") + "(" + userInfo.get(0).get("number") + ")";
        String approvalTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 反写到单据
        bill.set(nodeName, "【审批意见】" + approvalObj + "," + approverName + " " + approvalTime + "\n");
        SaveServiceHelper.update(bill);
    }

    /**
     * 场景 2（替换 notify）：审批拒绝后同步通知第三方系统。
     * <p>
     * 通过 WorkflowServiceHelper.getAllApprovalRecord() 获取完整审批记录，
     * 取最后一条记录组的拒绝原因，推送给外部系统。
     */
    private void notifyRejectToThirdParty(AgentExecution execution) {
        String entityNumber = execution.getEntityNumber();
        String businessKey = execution.getBusinessKey();
        if (businessKey == null) {
            return;
        }
        // 获取审批拒绝原因（从完整审批记录中提取）
        StringBuilder rejectReason = getLastApprovalMessage(businessKey);
        log.info("审批拒绝，单据={}, 原因={}", businessKey, rejectReason);
    }

    /**
     * 从审批记录中获取最后一个节点的审批意见。
     * <p>
     * WorkflowServiceHelper.getAllApprovalRecord(businessKey) → List&lt;IApprovalRecordGroup&gt;
     * IApprovalRecordGroup.getChildren() → List&lt;IApprovalRecordItem&gt;
     * IApprovalRecordItem：getActivityName() / getMessage() / getResult()
     */
    private StringBuilder getLastApprovalMessage(String businessKey) {
        StringBuilder message = new StringBuilder();
        List<IApprovalRecordGroup> allRecords = WorkflowServiceHelper.getAllApprovalRecord(businessKey);
        if (allRecords == null || allRecords.isEmpty()) {
            return message;
        }
        IApprovalRecordGroup lastGroup = allRecords.get(allRecords.size() - 1);
        for (IApprovalRecordItem item : lastGroup.getChildren()) {
            message.append(item.getActivityName())
                    .append(":")
                    .append(item.getMessage())
                    .append("；");
        }
        return message;
    }

    // ===================================================================
    //  三、afterSuspendProcess —— 流程挂起通知
    // ===================================================================

    /**
     * 流程异常挂起时，通知单据创建人。
     * <p>
     * SuspendInfo 上下文：
     * - getBusinessKey()  → 单据主键
     * - getEntityNumber() → 单据实体编码
     * - getErrMsg()       → 挂起错误原因
     * <p>
     * 通知渠道：MessageCenterServiceHelper.sendMessage() → 消息中心 + 云之家
     */
    @Override
    public void afterSuspendProcess(SuspendInfo suspendInfo) {
        try {
            String pk = suspendInfo.getBusinessKey();
            String entityId = suspendInfo.getEntityNumber();
            DynamicObject bill = BusinessDataServiceHelper.loadSingle(pk, entityId);

            // 获取单据创建人
            DynamicObject creator = bill.getDynamicObject("creator");
            Long userId = DynamicObjectUtils.nullSafeGet(creator, "id");
            if (EntityUtils.isEmptyPk(userId)) {
                log.info("afterSuspendProcess: creator is empty, pk={}", pk);
                return;
            }
            String billNo = bill.getString("billno");

            // 构建消息
            MessageInfo messageInfo = new MessageInfo();
            LocaleString title = new LocaleString();
            title.setLocaleValue_zh_CN("流程异常挂起");
            LocaleString content = new LocaleString();
            content.setLocaleValue_zh_CN("您的单据编号为" + billNo + "的流程已挂起，请联系系统管理员。\n"
                    + "挂起原因：" + suspendInfo.getErrMsg());

            messageInfo.setMessageTitle(title);
            messageInfo.setMessageContent(content);
            messageInfo.setUserIds(Collections.singletonList(userId));
            messageInfo.setType(MessageInfo.TYPE_WARNING);
            // notifyType：mcenter=消息中心, yunzhijia=云之家, email=邮件
            messageInfo.setNotifyType("mcenter,yunzhijia");
            MessageCenterServiceHelper.batchSendMessages(Collections.singletonList(messageInfo));
        } catch (Exception e) {
            log.warn("流程挂起通知失败", e);
        }
    }

    // ===================================================================
    //  四、calcUserIds —— 动态计算审批参与人
    // ===================================================================

    /**
     * 根据单据字段/流程变量动态返回审批人列表。
     * <p>
     * 典型场景：根据单据金额、部门等信息动态决定审批人。
     */
    @Override
    public List<Long> calcUserIds(AgentExecution execution) {
        List<Long> userIds = new ArrayList<>();
        // 方式 1：从流程变量中获取
        Object startUser = execution.getVariable("startUserId");
        if (startUser instanceof Number) {
            userIds.add(((Number) startUser).longValue());
        }
        // 方式 2：从单据数据中获取
        String billId = execution.getBusinessKey();
        String entityNum = execution.getEntityNumber();
        DynamicObject bill = BusinessDataServiceHelper.loadSingle(billId, entityNum);
        DynamicObject approver = bill.getDynamicObject("kdcd_approver");
        if (approver != null) {
            userIds.add(approver.getLong("id"));
        }
        return userIds;
    }

    // ===================================================================
    //  五、hasTrueCondition —— 条件分支判断
    // ===================================================================

    /**
     * 根据流程变量或单据字段决定是否命中当前分支。
     * <p>
     * 返回 true → 流程走当前分支；false → 跳过。
     */
    @Override
    public boolean hasTrueCondition(AgentExecution execution) {
        Object amount = execution.getVariable("amount");
        return amount instanceof Number && ((Number) amount).doubleValue() > 100000D;
    }

    // ===================================================================
    //  六、notifyByWithdraw —— 流程撤回补偿
    // ===================================================================

    /**
     * 流程撤回时的补偿处理。
     * <p>
     * 注意：notify 和 notifyByWithdraw 应成对设计，保证状态可恢复。
     */
    @Override
    public void notifyByWithdraw(AgentExecution execution) {
        execution.setVariable("lastNodeName", "withdraw");
        // TODO 恢复单据状态或清理之前 notify 写入的数据
    }

    // ===================================================================
    //  七、formatFlowRecord —— 审批记录格式化
    // ===================================================================

    /**
     * 定制审批记录的显示文本。
     * <p>
     * IApprovalRecordItem：getActivityName() / getMessage() / getResult() / setMessage(...)
     */
    @Override
    public IApprovalRecordItem formatFlowRecord(IApprovalRecordItem item) {
        // 示例：在审批记录中追加自定义说明
        // item.setMessage(item.getMessage() + " [已同步]");
        return item;
    }

    // ===================================================================
    //  八、WorkflowServiceHelper 辅助能力速查
    // ===================================================================

    /**
     * WorkflowServiceHelper 常用静态方法速查。
     * <p>
     * 这些方法不属于 IWorkflowPlugin 接口事件，但在操作插件、表单插件中经常配合使用。
     */
    private void workflowServiceHelperCheatSheet() {
        String billId = "123456";

        // 1. 判断单据是否在审批流程中（常用于下推前校验）
        boolean inProcess = WorkflowServiceHelper.inProcess(billId);

        // 2. 获取完整审批记录（常用于拒绝通知、审批意见展示）
        List<IApprovalRecordGroup> records = WorkflowServiceHelper.getAllApprovalRecord(billId);

        // 3. 激活挂起的流程实例（常用于异常流程巡检任务）
        // WorkflowServiceHelper.revokeSuspendProcessInstancesByProcessInstanceId(processInstanceId);
    }
}
