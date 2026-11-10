# 苍穹平台总规范摘要

> 本文件为**平台级基线参考**（guidance-only），不直接对应 lint 脚本。

本文件提炼自《苍穹定制化开发规范》，用于补齐 `ok-cosmic` 现有规则中较少覆盖的"平台级基线"。遇到命名、设计器/PDM、数据库、多语言、微服务、日志异常等问题时，优先回看本文件。

## 1. 命名基线

- 扩展项目推荐命名：`<isv>-<cloud>-<system>[-<type>]-ext`
- 扩展包强制命名：`<isv>.<cloud>[.<app>][.<feature>][.<suffix>]`
- 类名使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`
- 不要使用难以理解的随意缩写；抽象类以 `Abstract` 开头，异常类以 `Exception` 结尾，枚举类以 `Enum` 结尾
- Service/DAO 命名遵守语义前缀：单条 `get`，列表 `list`，统计 `count`，新增 `save/insert`，删除 `remove/delete`，修改 `update`
- 插件类推荐后缀：
    - 表单插件：`{Name}FormPlugin`
    - 单据插件：`{Name}BillPlugin`
    - 列表插件：`{Name}ListPlugin`
    - 操作插件：`{Name}OpPlugin`
    - 报表插件：`{Name}RptPlugin`
    - 统一使用 `Plugin` 后缀写法

## 2. 数据模型与数据库基线

- 业务对象表结构以 PDM 和设计器定义为准；表、字段、缺省值、可空性、实体关系必须一致
- 二开表名使用 `tk_<isv>_...` 前缀，二开字段使用 `fk_<isv>_...` 前缀
- 业务对象禁止使用数据库视图；设计器主键属性必须与表定义主键一致
- 表、字段、键、约束、缺省值名称长度不超过 24；字段 Key 推荐 4-24 位，由字母、数字、下划线组成
- 不要设计数据库外键；关系通过平台模型和应用逻辑维护
- 表结构总字节长度不得超过 8K（LOB / Image / nText 不计入）
- 新建表必须定义主键，新建数据库对象必须具备聚集索引
- 金额、数量字段必须使用精确数值类型 `Decimal`，明确精度，`not null` 且默认值为 `0`
- 字段长度按实际场景定义，禁止无依据地统一使用 `255`
- 同一业务含义的字段，在不同表中的类型定义要保持一致

## 3. 开发与插件基线

- 禁止在 `beforeBindData`、`afterBindData` 中修改数据对象
- 禁止在 `initialize()` 中注册控件事件、设置控件可见性或编写界面逻辑
- 事件注册放 `registerListener`；界面控制放 `afterBindData`
- 禁止继承标准产品插件；禁止禁用原厂插件
- 创建引用对象或给引用属性赋值时，必须保证对象类型与属性复杂类型一致
- 从缓存拿到的实体元数据是单例，修改前必须先 `clone`
- 判断"记录是否存在"优先使用 `QueryServiceHelper.exists(...)`

## 4. 性能与数据访问基线

- 所有脚本优先使用 KSQL，避免数据库方言
- SQL/KSQL 传参优先使用参数化形式，避免字符串拼接
- 不推荐在循环中访问数据库、Redis、`view.updateView()`
- 查询按需取字段；除加载完整单据/基础资料场景外，不要默认查询所有字段

## 5. 异常、日志与多语言基线

- 统一使用 `KDException` 体系；业务异常必须使用 `KDBizException`，不要用 `RuntimeException` 代替
- 包装异常时必须保留原始 `cause`
- 程序日志统一使用 `kd.bos.logging.Log`；业务操作日志统一使用 `BizLog.log`
- 输出日志前先判断日志级别；禁止在大循环中打日志；记录异常时用 `logger.error("描述", e)`
- 多语言字段必须使用多语言文本控件，并落库为 `NVARCHAR`
- 非多语言字段但会存中文等非英文字符时，也应优先定义为 `NVARCHAR`
- 多语言表推荐单独建表，命名 `{表名}_L`，主键字段名固定为 `FPKID`
- 提示语不要通过多个中文片段拼接；应以完整句模板配合 `ResManager.loadKDString(...)` 和 `String.format(...)`
- 运行时会按登录语言切换的提示语，不要固化成静态常量，改为方法内实时获取

## 6. 微服务与报表基线

- 二开微服务必须通过服务工厂注册；服务工厂类推荐：`<isv>.<cloud>.<app>.ServiceFactory`
- 微服务接口放在对应业务包下，命名保持“业务域 + Service”风格
- 报表场景避免在循环中做 SQL；大子单据体关联时优先拆分查询，减少 `left join`
- 充分利用 `algo` 做分组、去重、计数；避免把大数据量聚合放到 Java 循环里