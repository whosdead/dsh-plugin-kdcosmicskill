package kd.cd.common.snippets;

import kd.bos.cache.CacheFactory;
import kd.bos.cache.DistributeSessionlessCache;
import kd.bos.context.RequestContext;
import kd.bos.dataentity.entity.DynamicObject;
import kd.bos.dataentity.entity.DynamicObjectCollection;
import kd.bos.entity.cache.AppCache;
import kd.bos.entity.cache.IAppCache;
import kd.bos.logging.Log;
import kd.bos.logging.LogFactory;
import kd.bos.orm.query.QCP;
import kd.bos.orm.query.QFilter;
import kd.bos.servicehelper.BusinessDataServiceHelper;
import kd.bos.servicehelper.QueryServiceHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * 缓存使用示例 —— AppCache / DistributeSessionlessCache / loadFromCache。
 * <p>
 * 适用插件：操作插件、OpenAPI 控制器、表单插件、后台任务
 * 优先封装：（暂无 commons 封装）
 * 原生兜底：AppCache、DistributeSessionlessCache、CacheFactory、BusinessDataServiceHelper
 * 相关 lint 规则：（暂无）
 * <p>
 * 使用场景：
 * 1. AppCache：应用级别缓存，适合存储配置、票据、状态等轻量数据；
 * 2. DistributeSessionlessCache：分布式无会话缓存，支持 TTL 自动过期，适合跨节点共享；
 * 3. loadFromCache：基础资料查询走缓存通道，减少数据库访问。
 * <p>
 * <b>注意：缓存 key 应包含足够的业务区分度（如表单标识 + 业务编码），
 * 避免不同业务场景互相覆盖。</b>
 */
public class SampleCacheUsage {
    private static final Log log = LogFactory.getLog(SampleCacheUsage.class);

    // ===================================================================
    //  一、AppCache —— 应用级别缓存
    // ===================================================================

    /**
     * AppCache 是基于命名空间的应用级缓存。
     * 参数为命名空间名称，同一命名空间内共享 key 空间。
     */
    private static final IAppCache APP_CACHE = AppCache.get("DEV");

    /**
     * 场景：单点登录票据缓存。
     * 票据只能使用一次，首次解析后缓存结果，后续直接取缓存。
     */
    public String getOrCacheTicket(String ticket) {
        // 先查缓存
        String cachedValue = APP_CACHE.get(ticket, String.class);
        if (cachedValue != null) {
            log.info("缓存命中：{}", ticket);
            return cachedValue;
        }

        // 缓存未命中，执行实际解析
        String resolvedValue = resolveTicket(ticket);

        // 写入缓存（无 TTL，缓存随应用生命周期）
        APP_CACHE.put(ticket, resolvedValue);
        log.info("缓存写入：{}", ticket);

        return resolvedValue;
    }

    /**
     * 场景：存储临时状态（如异步操作的中间状态）。
     */
    public void cacheOperationStatus(String bizKey, String status) {
        APP_CACHE.put(bizKey, status);
    }

    public String getOperationStatus(String bizKey) {
        return APP_CACHE.get(bizKey, String.class);
    }

    // ===================================================================
    //  二、DistributeSessionlessCache —— 分布式无会话缓存（支持 TTL）
    // ===================================================================

    /**
     * 分布式缓存，跨节点共享，支持自动过期。
     * 参数为缓存区域名，同一区域内共享 key 空间。
     */
    private final DistributeSessionlessCache distCache =
            CacheFactory.getCommonCacheFactory().getDistributeSessionlessCache("kdcd_biz_cache");

    /**
     * 场景：缓存基础资料映射表（如维度成员数据）。
     * 查询时先查缓存，未命中则从 DB 查询后回填。
     *
     * @param formId       表单标识（如 "bcm_model"、"bos_org"）
     * @param selectFields 需要缓存的字段
     * @param cacheKey     缓存 key（建议 formId + 业务标识）
     * @param ttlSeconds   过期时间（秒），一般 180~600
     * @return key=id, value=字段 JSON 串
     */
    public Map<String, String> getCachedModelData(
            String formId, String selectFields, String cacheKey, int ttlSeconds) {

        // 1. 先查缓存
        Map<String, String> cached = distCache.getAll(cacheKey);
        if (!cached.isEmpty()) {
            return cached;
        }

        // 2. 缓存未命中，查询数据库
        QFilter filter = new QFilter("status", QCP.equals, "C")
                .and(new QFilter("enable", QCP.equals, "1"));

        DynamicObjectCollection dataCollection = QueryServiceHelper.query(
                formId, "id," + selectFields, new QFilter[]{filter});

        // 3. 构建缓存 Map
        Map<String, String> dataMap = new HashMap<>();
        for (DynamicObject item : dataCollection) {
            String id = item.getString("id");
            // 可以存 JSON 或简单值
            dataMap.put(id, item.getString(selectFields));
        }

        // 4. 写入缓存（带 TTL）
        distCache.put(cacheKey, dataMap, ttlSeconds);
        log.info("分布式缓存写入：key={}, size={}, ttl={}s", cacheKey, dataMap.size(), ttlSeconds);

        return distCache.getAll(cacheKey);
    }

    /**
     * 场景：缓存单个值（如用户 ID 反查）。
     *
     * @param userNumber 用户编码
     * @return 用户 ID
     */
    public Long getCachedUserId(String userNumber) {
        String cacheKey = "bos_user_" + userNumber;

        // 先查缓存
        String userId = (String) distCache.get(cacheKey);
        if (userId != null) {
            return Long.valueOf(userId);
        }

        // 查询数据库
        DynamicObject userObj = QueryServiceHelper.queryOne(
                "bos_user", "id", new QFilter[]{new QFilter("number", QCP.equals, userNumber)});

        if (userObj == null) {
            // 兜底：返回当前用户 ID
            userId = String.valueOf(RequestContext.get().getCurrUserId());
        } else {
            userId = userObj.getString("id");
        }

        // 写入缓存，180 秒过期
        distCache.put(cacheKey, userId, 180);

        return Long.valueOf(userId);
    }

    // ===================================================================
    //  三、loadFromCache —— 基础资料缓存查询
    // ===================================================================

    /**
     * 场景：查询基础资料时走缓存通道，减少数据库压力。
     * 适用于基础资料、辅助资料等不经常变更的实体。
     *
     * @param pk       主键
     * @param entityId 实体标识（如 "bos_org"、"bd_currency"）
     * @return 缓存中的 DynamicObject（如果缓存中没有会自动从 DB 加载并缓存）
     */
    public DynamicObject loadBaseDataFromCache(Object pk, String entityId) {
        // loadSingleFromCache：平台内置缓存通道（单条查询）
        // 首次调用从 DB 加载，后续直接从缓存返回
        return BusinessDataServiceHelper.loadSingleFromCache(pk, entityId);
    }

    /**
     * 场景：批量加载基础资料走缓存。
     * 返回 Map&lt;主键, DynamicObject&gt;。
     */
    public Map<Object, DynamicObject> loadBaseDataBatchFromCache(Object[] pks, String entityId) {
        return BusinessDataServiceHelper.loadFromCache(pks, entityId);
    }

    // ===================================================================
    //  缓存选型速查
    // ===================================================================
    //
    //  AppCache:
    //  - 应用级缓存，同一 JVM 实例内共享
    //  - 无 TTL（生命周期随应用）
    //  - 适合：配置项、票据、临时状态
    //  - 用法：AppCache.get("命名空间") → put/get
    //
    //  DistributeSessionlessCache:
    //  - 分布式缓存，跨节点共享（底层 Redis）
    //  - 支持 TTL 自动过期
    //  - 适合：频繁查询的映射表、用户信息、维度成员
    //  - 用法：CacheFactory...getDistributeSessionlessCache("区域") → put/get/getAll
    //
    //  loadFromCache:
    //  - 平台内置基础资料缓存通道
    //  - 自动管理缓存生命周期
    //  - 适合：基础资料、辅助资料等标准实体
    //  - 用法：BusinessDataServiceHelper.loadFromCache(pk, entityId)

    private String resolveTicket(String ticket) {
        // 模拟票据解析
        return "resolved_" + ticket;
    }
}
