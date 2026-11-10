/**
 * 后台定时任务示例。
 * <p>
 * 适用插件：后台任务、服务层
 * 优先封装：当前仓库无专门任务封装，简单查询场景可优先考虑 QueryServiceHelper
 * 原生兜底：AbstractTask、QueryServiceHelper、Log
 * 相关 lint 规则：STYLE-009、STYLE-015
 * <p>
 * 使用场景：
 * 1. 定时扫描并处理业务数据；
 * 2. 异常监控预警；
 * 3. 数据汇总统计；
 * 4. 任务参数解析、进度回传、停止检查、dry-run。
 */
package kd.cd.common.snippets.task;

import kd.bos.context.RequestContext;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.dataentity.resource.ResManager;
import kd.bos.dataentity.utils.StringUtils;
import kd.bos.exception.KDException;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.schedule.executor.AbstractTask;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.cd.core.util.BigDecimalUtils;
import kd.cd.core.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

public class ScheduleTaskSample extends AbstractTask {
    private static final Log log = LogFactory.getLog(ScheduleTaskSample.class);
    private static final String STAT_FORM_ID = "outapilogdailystats";
    private static final String PARAM_FAIL_RATE_LIMIT = "failRateLimit";
    private static final String PARAM_DRY_RUN = "dryRun";
    private static final BigDecimal DEFAULT_FAIL_RATE_LIMIT = BigDecimalUtils.valueOf(50);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final String RES_APP_ID = "kd-cd-common-snippets";

    @Override
    public void execute(RequestContext requestContext, Map<String, Object> params) throws KDException {
        BigDecimal failRateLimit = resolveFailRateLimit(params);
        boolean dryRun = resolveDryRun(params);
        this.feedbackProgress(0, ResManager.loadKDString("开始扫描接口统计数据", "ScheduleTaskSample_0", RES_APP_ID), null);

        DynamicObjectCollection stats = queryStats();
        if (CollectionUtils.isEmpty(stats)) {
            this.feedbackProgress(100, ResManager.loadKDString("本次无需处理数据", "ScheduleTaskSample_1", RES_APP_ID), null);
            return;
        }

        int total = stats.size();
        int processed = 0;
        int alarmCount = 0;
        for (DynamicObject stat : stats) {
            this.checkIsStop();

            BigDecimal failRate = calculateFailRate(stat);
            if (BigDecimalUtils.largeThan(failRate, failRateLimit)) {
                sendAlarm(stat, failRate, dryRun);
                alarmCount++;
            }
            processed++;

            if (processed % DEFAULT_BATCH_SIZE == 0 || processed == total) {
                int percent = processed * 100 / total;
                this.feedbackProgress(
                        percent,
                        String.format(
                                ResManager.loadKDString("已处理 %1$d/%2$d 条统计记录，触发 %3$d 条预警", "ScheduleTaskSample_2", RES_APP_ID),
                                processed,
                                total,
                                alarmCount
                        ),
                        null
                );
            }
        }
    }

    @Override
    public void stop() throws KDException {
        this.feedbackProgress(95, ResManager.loadKDString("收到停止指令，准备安全退出", "ScheduleTaskSample_3", RES_APP_ID), null);
    }

    private DynamicObjectCollection queryStats() {
        LocalDateTime nextHour = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
        return QueryServiceHelper.query(
                STAT_FORM_ID,
                "apinum,apiname,successcount,failcount",
                new QFilter("gentime", QCP.equals, nextHour).toArray()
        );
    }

    private BigDecimal calculateFailRate(DynamicObject stat) {
        int successCount = stat.getInt("successcount");
        int failCount = stat.getInt("failcount");
        int totalCount = successCount + failCount;
        if (totalCount <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimalUtils.divide(
                BigDecimalUtils.valueOf((long) failCount * 100),
                BigDecimalUtils.valueOf(totalCount),
                2,
                RoundingMode.DOWN
        );
    }

    private void sendAlarm(DynamicObject stat, BigDecimal failRate, boolean dryRun) {
        String message = String.format(
                ResManager.loadKDString("API异常预警：接口编号=%1$s，接口名称=%2$s，成功=%3$d，失败=%4$d，失败率=%5$s%%", "ScheduleTaskSample_4", RES_APP_ID),
                stat.getString("apinum"),
                stat.getString("apiname"),
                stat.getInt("successcount"),
                stat.getInt("failcount"),
                failRate
        );
        if (dryRun) {
            log.info("[dry-run] {}", message);
        } else {
            log.warn(message);
        }
    }

    private BigDecimal resolveFailRateLimit(Map<String, Object> params) {
        if (CollectionUtils.isEmpty(params)) {
            return DEFAULT_FAIL_RATE_LIMIT;
        }
        Object value = params.get(PARAM_FAIL_RATE_LIMIT);
        if (value instanceof Number) {
            return BigDecimalUtils.valueOf((Number) value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (StringUtils.isNumeric(text)) {
                return new BigDecimal(text);
            }
            log.warn("failRateLimit 参数格式非法：{}", value);
        }
        return DEFAULT_FAIL_RATE_LIMIT;
    }

    private boolean resolveDryRun(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        Object value = params.get(PARAM_DRY_RUN);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof String && Boolean.parseBoolean((String) value);
    }
}
