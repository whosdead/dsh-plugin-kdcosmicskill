/**
 * Every configurable value of the ok-cosmic plugin.
 *
 * All fields are `.volatile()` so the DSH settings service projects them into
 * an editable form. The plugin never caches these values: each tool call and
 * each skill load re-reads the live Cordis config object, so a saved settings
 * form takes effect on the next call without a restart.
 */
import z from '@deepseek-ai/schemastery'

/** Bundled skill description, used as the default when the user clears it. */
export const DEFAULT_SKILL_DESCRIPTION =
  '金蝶云苍穹开发主 Skill（DSH 插件版），优先复用 kd-cd-cosmic-commons 封装。适用于插件开发、单据/列表/表单逻辑、操作服务、BOTP 转换、后台视图打开、附件处理、DynamicObject 与元数据处理、弹性域解析及 OpenAPI 集成。所有配置由 DSH 插件设置驱动。'

export const Config = z
  .object({
    // ── Skill behaviour ───────────────────────────────────────────────────
    skillName: z
      .string()
      .pattern(/^[a-z0-9]+(?:-[a-z0-9]+)*$/)
      .default('ok-cosmic')
      .description('Skill catalog name (kebab-case).')
      .volatile(),
    skillDescription: z
      .string()
      .default(DEFAULT_SKILL_DESCRIPTION)
      .description('Description shown in the session skill catalog.')
      .volatile(),
    skillRank: z
      .number()
      .default(600)
      .description('Skill resolution rank; higher wins over same-name providers.')
      .volatile(),
    skillIncludeConfigSummary: z
      .boolean()
      .default(true)
      .description('Append the effective non-secret settings to the loaded skill body.')
      .volatile(),
    skillExtraInstructions: z
      .string()
      .default('')
      .description('Extra project rules appended to the loaded skill body.')
      .volatile(),

    // ── Runtime ───────────────────────────────────────────────────────────
    pythonPath: z
      .string()
      .default('python')
      .description('Python executable used to run the bundled ok-cosmic scripts.')
      .volatile(),
    workingDirectory: z
      .string()
      .default('')
      .description('Working directory for tool processes; empty uses the DSH process cwd.')
      .volatile(),
    toolTimeoutSeconds: z
      .number()
      .default(120)
      .description('Default timeout for one tool process, in seconds.')
      .volatile(),
    maxOutputBytes: z
      .number()
      .default(200000)
      .description('Maximum captured stdout/stderr bytes returned to the model.')
      .volatile(),
    extraEnv: z
      .array(z.string())
      .default([])
      .description('Extra environment variables for tool processes, each as KEY=VALUE.')
      .volatile(),
    debug: z
      .boolean()
      .default(false)
      .description('Pass --debug to query scripts and print request diagnostics.')
      .volatile(),

    // ── Knowledge base and unified route ──────────────────────────────────
    knowledgeDbPath: z
      .string()
      .default('')
      .description('Path to ok-cosmic-knowledge.db (built by the bundled setup tool).')
      .volatile(),
    routeApiUrl: z
      .string()
      .default('')
      .description('Cosmic runtime/route OpenAPI URL, including openApiSign when required.')
      .volatile(),
    routeTimeoutSeconds: z
      .number()
      .default(10)
      .description('HTTP timeout for runtime/route queries, in seconds.')
      .volatile(),
    routeOpenApiSign: z
      .string()
      .default('')
      .description('Optional openApiSign appended to the route URL when it is not already present.')
      .volatile(),
    routeApiToken: z
      .string()
      .default('')
      .description('Optional Bearer token sent to the route API. Stored in the profile config.')
      .volatile(),
    routeSkipSslVerify: z
      .boolean()
      .default(true)
      .description('Skip TLS certificate verification for self-signed development servers.')
      .volatile(),

    // ── Extension repositories ────────────────────────────────────────────
    extensionRepos: z
      .array(z.string())
      .default([])
      .description('Extension source directories used as the fallback reference corpus.')
      .volatile(),

    // ── Caches and limits ─────────────────────────────────────────────────
    cacheFormMetadataTtlSeconds: z
      .number()
      .default(600)
      .description('Form metadata SQLite cache TTL, in seconds.')
      .volatile(),
    cacheBasedataTtlSeconds: z
      .number()
      .default(600)
      .description('Base data SQLite cache TTL, in seconds.')
      .volatile(),
    cacheSqliteBusyTimeoutMs: z
      .number()
      .default(5000)
      .description('SQLite busy timeout used by the cache tables, in milliseconds.')
      .volatile(),
    limitSearchPageSize: z
      .number()
      .default(20)
      .description('Default page size for API knowledge searches.')
      .volatile(),
    limitBatchWorkers: z
      .number()
      .default(4)
      .description('Concurrency for batch metadata and API queries.')
      .volatile(),
    limitMaxDisplayRecords: z
      .number()
      .default(120)
      .description('Maximum rows rendered by one metadata or extension-point result.')
      .volatile(),
    formMetadataDefaultSort: z
      .string()
      .pattern(/^(key|name|type|dbKey)$/)
      .default('key')
      .description('Default metadata sort: key | name | type | dbKey.')
      .volatile(),
    formMetadataDefaultView: z
      .string()
      .pattern(/^(form|entity|operate|all)$/)
      .default('all')
      .description('Default metadata view: form | entity | operate | all.')
      .volatile(),

    // ── Post-check ────────────────────────────────────────────────────────
    postCheckRequired: z
      .boolean()
      .default(true)
      .description('Tell the model that cosmic_post_check is mandatory after generating Java code.')
      .volatile(),
    postCheckMaxRounds: z
      .number()
      .default(3)
      .description('Maximum fix-and-recheck rounds the skill instructs the model to run.')
      .volatile(),
    postCheckGradleEnabled: z
      .boolean()
      .default(true)
      .description('Run a Gradle compile when a Gradle project root is detected.')
      .volatile(),
    postCheckGradleCommand: z
      .string()
      .default('')
      .description('Gradle executable; empty auto-detects gradlew.bat / gradlew / gradle.')
      .volatile(),
    postCheckGradleTask: z
      .string()
      .default('compileJava')
      .description('Gradle task used for the compile check; a module prefix is added automatically.')
      .volatile(),
    postCheckGradleArgs: z
      .string()
      .default('--console=plain')
      .description('Extra Gradle arguments, separated by spaces.')
      .volatile(),
    postCheckTimeoutSeconds: z
      .number()
      .default(600)
      .description('Gradle compile timeout, in seconds.')
      .volatile(),
    postCheckJavaHome: z
      .string()
      .default('')
      .description('JAVA_HOME for the Gradle compile; empty uses gradle.properties then the environment.')
      .volatile(),
    postCheckLintMinSeverity: z
      .string()
      .pattern(/^(error|warning|info)$/)
      .default('info')
      .description('Lowest lint severity reported: error | warning | info.')
      .volatile(),

    // ── Step 0 config check ───────────────────────────────────────────────
    configCheckAutoInstall: z
      .boolean()
      .default(true)
      .description('Let the config check install missing tree-sitter dependencies with pip.')
      .volatile(),
    configCheckInstallTimeoutSeconds: z
      .number()
      .default(120)
      .description('Timeout for the automatic pip install, in seconds.')
      .volatile(),
  })
  .description('ok-cosmic plugin settings')
