/**
 * 消息通知示例。
 * <p>
 * 适用插件：操作插件、服务层、后台任务
 * 优先封装：当前仓库无专门消息封装，保持最小原生写法
 * 原生兜底：MessageCenterServiceHelper、MessageInfo
 * 相关 lint 规则：当前无专门规则，可参考 STYLE-013、STYLE-015
 * <p>
 * 使用场景：
 * 1. 系统内消息通知；
 * 2. 短信通知；
 * 3. 云之家机器人通知；
 * 4. 邮件通知。
 */
package kd.cd.common.snippets.message;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.LocaleString;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.servicehelper.workflow.MessageCenterServiceHelper;
import kd.bos.workflow.engine.msg.info.MessageInfo;
import kd.cd.core.util.CharSequenceUtils;
import kd.cd.core.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class MessageNotifySample {
    private static final Log log = LogFactory.getLog(MessageNotifySample.class);

    // --- 系统内消息通知 ---
    public static boolean sendSystemMessage(List<Long> userIds, String title, String content) {
        if (CollectionUtils.isEmpty(userIds)) {
            return false;
        }

        try {
            MessageInfo messageInfo = new MessageInfo();
            LocaleString titleLocale = new LocaleString();
            titleLocale.setLocaleValue_zh_CN(title);
            messageInfo.setMessageTitle(titleLocale);

            LocaleString contentLocale = new LocaleString();
            contentLocale.setLocaleValue_zh_CN(content);
            messageInfo.setMessageContent(contentLocale);

            messageInfo.setUserIds(userIds);
            messageInfo.setType(MessageInfo.TYPE_ALARM);
            messageInfo.setNotifyType("mcenter,yunzhijia");

            MessageCenterServiceHelper.batchSendMessages(Collections.singletonList(messageInfo));
            return true;
        } catch (Exception e) {
            log.warn("系统内消息发送失败", e);
            return false;
        }
    }

    // --- 构建预警消息内容 ---
    public static String buildAlarmMessage(String apiNum, String apiName, int successCount, int failCount, String failRate) {
        return String.format(
                ResManager.loadKDString(
                        "接口异常预警%n接口编号：%s%n接口名称：%s%n成功次数：%d%n失败次数：%d%n失败率：%s%%",
                        "MessageNotifySample_0",
                        "kd-cd-common-snippets"
                ),
                apiNum, apiName, successCount, failCount, failRate
        );
    }

    // --- 从分录获取通知用户ID列表 ---
    public static List<Long> getUserIdsFromEntry(DynamicObject bill, String entryKey, String userFieldKey) {
        return bill.getDynamicObjectCollection(entryKey).stream()
                .map(row -> (DynamicObject) row.get(userFieldKey))
                .filter(Objects::nonNull)
                .map(obj -> obj.getLong("id"))
                .distinct()
                .collect(Collectors.toList());
    }

    // --- 从分录获取手机号列表 ---
    public static List<String> getPhonesFromEntry(DynamicObject bill, String entryKey, String userFieldKey) {
        return bill.getDynamicObjectCollection(entryKey).stream()
                .map(row -> (DynamicObject) row.get(userFieldKey))
                .filter(Objects::nonNull)
                .map(obj -> obj.getString("phone"))
                .filter(CharSequenceUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }
}
