package kd.cd.common.snippets;

import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.QueryServiceHelper;
import kd.bos.threads.ThreadPools;
import kd.cd.common.concurrent.ExecutorServiceUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 线程池批量处理示例 —— 大批量数据并发处理的标准模式。
 * <p>
 * 适用插件：操作插件、后台调度任务、数据初始化
 * 优先封装：ExecutorServiceUtils.shutdownAndAwaitTermination
 * 原生兜底：ThreadPools、ExecutorService
 * 相关 lint 规则：STYLE-019（禁 new Thread）、STYLE-020（禁 JDK Executors）
 * <p>
 * 使用场景：
 * 1. 批量刷数据：查询大量单据后多线程并发修改；
 * 2. 一次性任务：ThreadPools.executeOnce 执行一次后销毁；
 * 3. 优雅关闭：shutdownAndAwaitTermination 等待所有任务完成再继续。
 * <p>
 */
public class SampleThreadPoolBatch {
    private static final Log log = LogFactory.getLog(SampleThreadPoolBatch.class);

    // ===================================================================
    //  一、标准批量处理模式（最常用）
    // ===================================================================

    /**
     * 批量并发修改单据。
     * 步骤：查询 → 创建线程池 → 循环提交任务 → 优雅关闭等待完成。
     *
     * @param formId     单据标识
     * @param conditions 过滤条件
     */
    public void batchProcess(String formId, QFilter[] conditions) {
        // ---------- Step 1: 查询待处理数据 ----------
        DynamicObjectCollection coll = QueryServiceHelper.query(formId, "id", conditions);
        List<Object> pks = coll.stream()
                .map(o -> o.get("id"))
                .distinct()
                .collect(Collectors.toList());

        if (pks.isEmpty()) {
            log.info("无需处理的数据");
            return;
        }

        // ---------- Step 2: 创建线程池 ----------
        // 命名规则：业务标识 + 时间戳，方便运维排查
        // 线程数：一般 50~100，根据单条任务耗时和总量调整
        ExecutorService pool = ThreadPools.newExecutorService(
                "kdcd_batch_" + System.currentTimeMillis(), 50);

        // ---------- Step 3: 循环提交任务 ----------
        for (Object pk : pks) {
            pool.execute(() -> processSingleItem(formId, pk));
        }

        // ---------- Step 4: 优雅关闭并等待所有任务完成 ----------
        // 参数：线程池、超时时间（秒）；一般 1800（30分钟）到 2400（40分钟）
        ExecutorServiceUtils.shutdownAndAwaitTermination(pool, 1800);

        log.info("批量处理完成，共 {} 条", pks.size());
    }

    /**
     * 单条数据处理逻辑（每个线程执行一条）。
     * 要点：捕获异常，避免单条失败导致整个线程池崩溃。
     */
    private void processSingleItem(String formId, Object pk) {
        try {
            // 此处编写具体业务逻辑（如修改单据、调接口等）
            log.info("正在处理：{}", pk);
        } catch (Exception e) {
            log.error("处理失败：pk={}", pk, e);
            // 根据业务需要决定：记录失败 or 更新状态字段 or 重试
        }
    }

    // ===================================================================
    //  二、ThreadPools.executeOnce —— 一次性异步任务
    // ===================================================================

    /**
     * 场景：只执行一次的后台任务（如异步生成中间表、触发通知等）。
     * ThreadPools.executeOnce 会创建单线程池执行后自动销毁。
     *
     * @param taskName 任务标识名（用于日志追踪）
     */
    public void executeOnceExample(String taskName) {
        ThreadPools.executeOnce(taskName, () -> {
            try {
                // 执行一次性任务
                log.info("一次性任务开始：{}", taskName);
                doHeavyWork();
                log.info("一次性任务完成：{}", taskName);
            } catch (Exception e) {
                log.error("一次性任务异常：{}", taskName, e);
            }
        });
    }

    // ===================================================================
    //  三、带分片的批量处理（超大数据量场景）
    // ===================================================================

    /**
     * 超大批量场景：先分片再并发。
     * 适用于数十万条以上的数据处理，避免一次性查出所有 PK 占用过多内存。
     *
     * @param formId 单据标识
     * @param pageSize 每页大小
     */
    public void batchProcessByPage(String formId, int pageSize) {
        ExecutorService pool = ThreadPools.newExecutorService(
                "kdcd_paged_" + System.currentTimeMillis(), 50);

        while (true) {
            // 分页查询
            DynamicObjectCollection page = QueryServiceHelper.query(
                    formId, "id",
                    new QFilter[]{new QFilter("kdcd_processed", QCP.equals, false)},
                    "id asc", pageSize);

            if (page.isEmpty()) {
                break;
            }

            for (DynamicObject row : page) {
                Object pk = row.get("id");
                pool.execute(() -> processSingleItem(formId, pk));
            }

            if (page.size() < pageSize) {
                break; // 最后一页
            }
        }

        ExecutorServiceUtils.shutdownAndAwaitTermination(pool, 2400);
        log.info("分页批量处理完成");
    }

    private void doHeavyWork() {
        // 模拟耗时操作
    }
}
