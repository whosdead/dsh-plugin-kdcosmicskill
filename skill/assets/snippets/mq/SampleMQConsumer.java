package kd.cd.common.snippets;

import kd.bos.dataentity.OperateOption;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dlock.DLock;
import kd.bos.entity.operate.result.OperationResult;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.mq.MessageAcker;
import kd.bos.mq.MessageConsumer;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.bos.servicehelper.operation.OperationServiceHelper;
import kd.bos.servicehelper.operation.SaveServiceHelper;
import kd.cd.common.operate.OpUtils;

/**
 * MQ 消息消费者示例 —— 苍穹消息队列消费端全场景。
 * <p>
 * 适用插件：MQ 消费者（实现 MessageConsumer 接口）
 * 优先封装：（暂无 commons 封装）
 * 原生兜底：MessageConsumer、MessageAcker、DLock、OperationServiceHelper
 * 相关 lint 规则：（暂无）
 * <p>
 * 使用场景：
 * 1. 简单消费模式：收到消息 → 执行操作 → ack/discard；
 * 2. 分布式锁防重复消费：DLock + tryLock 保证同一消息不会并发处理；
 * 3. MessageAcker 三种应答：ack（确认）、deny（拒绝回队列）、discard（废弃不重试）。
 * <p>
 * <b>配套配置：需在 resources 下创建 MQ XML 配置文件，并在启动类中声明。</b>
 * XML 结构示例见本文件末尾注释。
 */
public class SampleMQConsumer implements MessageConsumer {
    private static final Log log = LogFactory.getLog(SampleMQConsumer.class);

    // ===================================================================
    //  一、标准消费模式（执行操作 + 结果应答）
    // ===================================================================

    /**
     * MQ 消费入口。
     *
     * @param message   消息体（类型取决于发送方，常见 String / String[] / JSON）
     * @param messageId 消息唯一标识
     * @param resend    是否为重发消息
     * @param acker     消息应答器
     */
    @Override
    public void onMessage(Object message, String messageId, boolean resend, MessageAcker acker) {
        log.info("MQ 消费开始：messageId={}, message={}", messageId, message);
        if (message == null) {
            return;
        }

        // ---------- 场景 A：消息体为 String[]（常见于操作类消息） ----------
        // 发送方约定：message[0] = 单据标识, message[1] = 主键
        // consumeOperationMessage((String[]) message, messageId, acker);

        // ---------- 场景 B：消息体为单据 PK + 分布式锁防重 ----------
        consumeWithDLock(message, messageId, acker);
    }

    // ===================================================================
    //  场景 A：操作类消息（如异步入账、复核、反复核）
    // ===================================================================

    /**
     * 收到消息后调用平台操作。
     * 典型用途：异步入账、异步审批、凭证复核等。
     */
    private void consumeOperationMessage(String[] message, String messageId, MessageAcker acker) {
        String entityId = message[0];    // 单据标识
        String pkValue = message[1];     // 单据主键
        log.info("操作类消息：entityId={}, pk={}", entityId, pkValue);

        try {
            // 调用平台操作（如 "audit"、"submit"、"kdcd_dorule" 等）
            OperationResult result = OperationServiceHelper.executeOperate(
                    "audit", entityId, new Long[]{Long.valueOf(pkValue)}, OperateOption.create());

            if (result.isSuccess()) {
                log.info("操作执行成功，确认消息");
                acker.ack(messageId);         // 确认：消费完成
            } else {
                String errMsg = OpUtils.getCompleteFailMsg(result);
                log.info("操作执行失败：{}", errMsg);
                // 更新单据的错误信息字段
                DynamicObject entity = BusinessDataServiceHelper.loadSingle(pkValue, "kdcd_errmsg", entityId);
                entity.set("kdcd_errmsg", errMsg);
                SaveServiceHelper.update(entity);
                acker.discard(messageId);     // 废弃：不再重试
            }

        } catch (Exception e) {
            log.error("消费异常：{}", e.getMessage(), e);
            acker.discard(messageId);         // 异常也废弃，避免无限重试
        }
    }

    // ===================================================================
    //  场景 B：分布式锁防重复消费（适用于外部接口调用等幂等场景）
    // ===================================================================

    /**
     * 使用 DLock 分布式锁防止同一消息被多个消费者实例并发处理。
     * 典型用途：调用外部接口、同步第三方数据。
     */
    private void consumeWithDLock(Object message, String messageId, MessageAcker acker) {
        // 以消息内容（通常是单据 PK）作为锁的 key
        try (DLock dLock = DLock.create(messageId)) {
            if (dLock.tryLock()) {
                // ---------- 加锁成功，处理消息 ----------
                processMessageBody(message, messageId, acker);
            } else {
                // ---------- 加锁失败，返回队列等待重新消费 ----------
                log.info("获取分布式锁失败，消息回队列：{}", messageId);
                acker.deny(messageId);
            }
        }
    }

    /**
     * 消息处理核心逻辑。
     */
    private void processMessageBody(Object message, String messageId, MessageAcker acker) {
        String formId = "kdcd_api_message_pool";
        DynamicObject bill = BusinessDataServiceHelper.loadSingle(message, "kdcd_status", formId);

        // 幂等判断：已处理成功的跳过
        String status = bill.getString("kdcd_status");
        if ("1".equals(status)) {
            log.info("消息已成功处理过，跳过：{}", messageId);
            acker.discard(messageId);
            return;
        }

        try {
            // ====== 执行业务逻辑（如调用外部 API） ======
            boolean success = callExternalApi(bill);

            if (success) {
                bill.set("kdcd_status", "1");       // 成功
                SaveServiceHelper.update(bill);
                acker.ack(messageId);               // 确认消费完成
            } else {
                bill.set("kdcd_status", "-1");      // 失败
                SaveServiceHelper.update(bill);
                acker.deny(messageId);              // 拒绝：返回队列稍后重试
            }

        } catch (Exception e) {
            log.error("消息处理异常：{}", messageId, e);
            bill.set("kdcd_status", "-1");
            SaveServiceHelper.update(bill);
            acker.discard(messageId);               // 异常废弃
        }
    }

    private boolean callExternalApi(DynamicObject bill) {
        // 模拟外部接口调用
        return true;
    }

    // ===================================================================
    //  MessageAcker 应答方式速查
    // ===================================================================
    //
    //  acker.ack(messageId)     → 确认：消息已成功处理，从队列移除
    //  acker.deny(messageId)    → 拒绝：消息返回队列，稍后重新投递消费
    //  acker.discard(messageId) → 废弃：消息从队列移除，不再重试
    //
    //  选择原则：
    //  - 处理成功 → ack
    //  - 临时故障（如外部接口超时）→ deny（会重试）
    //  - 业务逻辑失败或异常（不可能通过重试修复）→ discard

    // ===================================================================
    //  MQ XML 配置示例（放在 resources 根目录下）
    // ===================================================================
    //
    //  文件名如：kdcd_sample_mq.xml
    //
    //  <root>
    //      <!--所属云：如 fi=财务云、tmc=资金云、sys=系统-->
    //      <region name="fi">
    //          <!--name：队列名称（全局唯一） appid：所属应用-->
    //          <queue name="kdcd_sample_queue" appid="gl">
    //              <!--class：消费者实现类全路径 concurrency：消费者线程数-->
    //              <consumer class="kd.cd.xxx.SampleMQConsumer" concurrency="5"/>
    //          </queue>
    //      </region>
    //  </root>
    //
    //  启动类配置：
    //  System.setProperty("mqConfigFiles.config", "kdcd_sample_mq.xml");
    //
    //  发送消息：
    //  MQFactory.get().send("kdcd_sample_queue", messageObj);
}
