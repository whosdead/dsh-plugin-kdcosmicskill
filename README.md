# dsh-plugin-kdcosmicskill

DeepSeek Harness（DSH）插件：金蝶云苍穹开发助手（ok-cosmic）。

仓库：https://github.com/whosdead/dsh-plugin-kdcosmicskill

把 `ok-cosmic`（金蝶云苍穹开发 Skill，原作者：陈泽贤、钱芮名）封装成 DeepSeek Harness（DSH）插件。

- **Skill**：`ok-cosmic` 以 skill 形式进入会话技能目录，规则、模板、参考文档、代码片段全部随插件打包。
- **工具**：8 个 `cosmic_*` 工具平移原 Skill 的脚本能力（配置预检、API 知识、元数据、基础资料、业务拓展点、生成后校验）。
- **设置**：37 项配置全部收进 DSH 设置界面，保存后**下一次工具调用 / 下一次 Skill 加载立即生效**，不需要重启 DSH。
- **无项目级配置文件**：插件每次调用都会用当前设置生成临时 `ok-cosmic.json`，传入打包的 Python 脚本，因此不需要也不应该再维护项目根目录的 `ok-cosmic.json`。

## 目录

```text
./
├─ index.js                 # Host 入口：注册工具 + Skill provider
├─ client.js                # 浏览器入口：设置页
├─ lib/
│  ├─ config.js             # 37 项 Config（全部 .volatile()）
│  ├─ settings.js           # 读取实时配置、生成脚本配置
│  ├─ runner.js             # 临时配置 + 子进程执行 + 超时/取消/截断
│  ├─ tools.js              # 8 个模型工具
│  └─ skill.js              # ok-cosmic skill provider
├─ locale/{zh,en}.json      # 插件管理器显示名
├─ skill/                   # 原 ok-cosmic 内容（SKILL.md、rules、references、assets、scripts、setup）
├─ cordis.patch.yml         # bundle 行：id = ok-cosmic
└─ package.json
```

## 安装

### 方式 A：Plugins 页面安装本地目录

1. 打开 DSH 侧边栏 **Plugins**。
2. **Add plugin**，填写本包的绝对路径，例如：
   `C:\path\to\dsh-plugin-kdcosmicskill`
3. 安装完成后，在 **Installed** 中找到 `dsh-ok-cosmic`，确认其插件行 `ok-cosmic` 已启用。

### 方式 B：交给插件管理器工具

```text
plugin_manager install_bundle { target: "C:\\path\\to\\dsh-plugin-kdcosmicskill" }
```

> 本插件是本地 bundle，安装时会以链接方式引用源码目录；修改 `client.js` 后刷新页面即可，修改 `index.js` 需要重新加载插件宿主模块。

## 打开设置

设置页注册在两个位置，内容相同：

- **Settings → 苍穹开发**（推荐，按分组展示）
- **Plugins → dsh-ok-cosmic → 配置页**（bundle 配置页）

保存前只暂存草稿；点击 **保存** 才会写入 profile 的 `cordis.patch.yml`。Host 会校验完整 Config，写入成功后配置热更新。

## 配置项

| 分组 | 字段 | 默认值 | 说明 |
|---|---|---|---|
| Skill | `skillName` | `ok-cosmic` | Skill 名称，kebab-case |
| Skill | `skillDescription` | 内置描述 | 会话技能目录中的描述 |
| Skill | `skillRank` | `600` | Skill 解析优先级 |
| Skill | `skillIncludeConfigSummary` | `true` | 把非敏感生效配置追加到 Skill 正文 |
| Skill | `skillExtraInstructions` | 空 | 追加到 Skill 正文末尾的项目规则 |
| 运行环境 | `pythonPath` | `python` | Python 可执行文件 |
| 运行环境 | `workingDirectory` | 空 | 工具进程工作目录；空 = 当前会话工作目录 |
| 运行环境 | `toolTimeoutSeconds` | `120` | 单次工具默认超时（秒） |
| 运行环境 | `maxOutputBytes` | `200000` | 单次返回模型的输出上限（字节） |
| 运行环境 | `extraEnv` | `[]` | 每行一个 `KEY=VALUE`，注入工具进程 |
| 运行环境 | `debug` | `false` | 查询脚本输出请求调试信息 |
| 知识库 | `knowledgeDbPath` | 空 | `ok-cosmic-knowledge.db` 路径；支持相对路径 |
| 知识库 | `routeApiUrl` | 空 | `runtime/route` 统一路由地址（含 `openApiSign`） |
| 知识库 | `routeTimeoutSeconds` | `10` | 路由 HTTP 超时（秒） |
| 知识库 | `routeOpenApiSign` | 空 | URL 中没有时自动追加的签名 |
| 知识库 | `routeApiToken` | 空 | 可选 Bearer Token |
| 知识库 | `routeSkipSslVerify` | `true` | 自签名开发环境跳过证书校验 |
| 扩展库 | `extensionRepos` | `[]` | 每行一个目录，内置资产未覆盖时搜索参考实现 |
| 缓存限额 | `cacheFormMetadataTtlSeconds` | `600` | 表单元数据缓存 TTL（秒） |
| 缓存限额 | `cacheBasedataTtlSeconds` | `600` | 基础资料缓存 TTL（秒） |
| 缓存限额 | `cacheSqliteBusyTimeoutMs` | `5000` | SQLite busy timeout（毫秒） |
| 缓存限额 | `limitSearchPageSize` | `20` | API 知识搜索默认每页条数 |
| 缓存限额 | `limitBatchWorkers` | `4` | 批量查询并发数 |
| 缓存限额 | `limitMaxDisplayRecords` | `120` | 单次结果最多展示条数 |
| 缓存限额 | `formMetadataDefaultSort` | `key` | `key` / `name` / `type` / `dbKey` |
| 缓存限额 | `formMetadataDefaultView` | `all` | `form` / `entity` / `operate` / `all` |
| 校验 | `postCheckRequired` | `true` | Skill 是否把生成后校验标为必做 |
| 校验 | `postCheckMaxRounds` | `3` | 最多修复-复检轮数（写入 Skill 指令） |
| 校验 | `postCheckGradleEnabled` | `true` | 检测到 Gradle 项目时是否编译 |
| 校验 | `postCheckGradleCommand` | 空 | Gradle 可执行文件；空 = 自动探测 |
| 校验 | `postCheckGradleTask` | `compileJava` | Gradle 任务名 |
| 校验 | `postCheckGradleArgs` | `--console=plain` | 额外 Gradle 参数，空格分隔 |
| 校验 | `postCheckTimeoutSeconds` | `600` | Gradle 编译超时（秒） |
| 校验 | `postCheckJavaHome` | 空 | 优先于 `gradle.properties` 和 `JAVA_HOME` |
| 校验 | `postCheckLintMinSeverity` | `info` | `error` / `warning` / `info` |
| 预检 | `configCheckAutoInstall` | `true` | 缺少 tree-sitter 时自动 pip install |
| 预检 | `configCheckInstallTimeoutSeconds` | `120` | 自动安装超时（秒） |

所有字段在 `lib/config.js` 中都是 `.volatile()`，这是 DSH 设置页能编辑并热更新的前提。

## 工具

| 工具 | 作用 |
|---|---|
| `cosmic_config_check` | Step 0：Python 依赖 + 知识库 + 路由 + 扩展库预检 |
| `cosmic_search_classes` | 离线 API 知识库按类名/包名搜索 |
| `cosmic_search_methods` | 跨类搜索方法名 |
| `cosmic_get_class_detail` | 类详情、方法签名、继承链、注释 |
| `cosmic_get_meta_fields` | 字段、类型、refType、枚举 extMap、操作、SQL |
| `cosmic_query_basedata` | 按已确认 `entityId` 查询基础资料 |
| `cosmic_query_extpoints` | 业务拓展点候选接口 |
| `cosmic_post_check` | Gradle 编译 + A/B/C 分层静态检查 |

## 准备知识库

`knowledgeDbPath` 指向的 `ok-cosmic-knowledge.db` 需要由原 Skill 的 `setup.jar` 从苍穹 SDK JAR 构建。插件已打包：

- `skill/setup/setup.jar`
- `skill/setup/ok-cosmic-docs.db`
- `skill/setup/setup-windows.bat` / `setup-mac.sh`
- `skill/setup/cuslib/*.jar`

> 本仓库使用 Git LFS 存储 `skill/setup/` 下的二进制文件（`setup.jar`、`ok-cosmic-docs.db`、`cuslib/*.jar`）。克隆前请安装 Git LFS；直接下载 ZIP 可能只得到 LFS 指针文件，而不是实际二进制。

在插件目录执行（Windows）：

```bat
cd skill\setup
setup-windows.bat -libpath=<苍穹 SDK JAR 目录>
```

构建完成后把生成的 `ok-cosmic-knowledge.db` 路径填入 **Settings → 苍穹开发 → 知识库数据库路径**。

> 知识库数据库同时被用作 SQLite 缓存。如果它位于只读目录，插件会禁用缓存但继续查询；建议把数据库复制到可写目录。

## 热更新与安全

- 每次工具调用都会重新读取当前 Config；Skill 每次加载都会重新读取 `SKILL.md` 和当前配置。
- 临时配置文件写入系统临时目录，进程结束立即删除；路由 Token 不会进入 Skill 正文或设置页回显以外的位置。
- 子进程输出写入临时文件而不是管道，兼容 DSH 受限 Shell，也避免 Gradle 长输出造成的管道死锁。
- `routeApiToken` 目前按普通字符串写入 profile 配置；如需更高安全级别，请在后续版本接入 DSH credentials 域。

## 故障排查

| 现象 | 处理 |
|---|---|
| 设置页不出现 | 确认插件行 `ok-cosmic` 已启用；Settings 页需要 `dsh-client-ui-settings`，Plugins 页需要 `dsh-client-ui-plugin-manager` |
| `spawn EPERM` | 旧版本使用管道捕获输出；本版本已改为临时文件。若仍出现，检查 DSH 沙箱是否禁止启动 Python |
| 知识库查询提示 `no such table: class_node` | `knowledgeDbPath` 指向的不是 `setup.jar` 生成的 API 知识库；重新构建 |
| 元数据/基础资料提示未配置统一路由 | 填写 `routeApiUrl`，或设置 `COSMIC_ROUTE_API` 环境变量（仅作为脚本兜底） |
| 设置保存失败 | Host 会校验完整 Config；按字段提示修正。保存失败时草稿会保留 |
| Gradle 校验超时 | 调大 `postCheckTimeoutSeconds`，或把 `postCheckGradleEnabled` 关闭后只跑静态 lint |
