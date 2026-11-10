/**
 * Model-facing tools. Every definition delegates to a bundled Python script
 * and reads the live plugin settings for paths, endpoints, limits and timeouts
 * at call time.
 */
import { defineTool } from '@deepseek-ai/dsh-tools'

import { readSettings } from './settings.js'
import { runScript } from './runner.js'

const KIND_VALUES = [
  'helper',
  'servicehelper',
  'plugin',
  'service',
  'utils',
  'runtime',
  'entity',
  'const',
  'enum',
  'controller',
]

/**
 * @param {{ name: string, description: string, parameters: any, run: (args: any, signal: AbortSignal, settings: ReturnType<import('./settings.js').readSettings>) => Promise<string> }} spec
 * @param {() => ReturnType<import('./settings.js').readSettings>} settingsOf
 */
function textTool(spec, settingsOf) {
  return defineTool({
    name: spec.name,
    description: spec.description,
    parameters: spec.parameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: String(value) }],
    },
    execute: async (args, exec) => {
      const sessionCwd = exec?.agent?.session?.header?.cwd
      const settings = settingsOf(typeof sessionCwd === 'string' && sessionCwd ? sessionCwd : undefined)
      return spec.run(args || {}, exec.signal, settings)
    },
  })
}

/** @param {string[]} values */
function splitTerms(values) {
  return values.map((value) => String(value).trim()).filter(Boolean)
}

/**
 * @param {() => ReturnType<import('./settings.js').readSettings>} settingsOf
 */
function buildTools(settingsOf) {
  const tool = (spec) => textTool(spec, settingsOf)

  const configCheck = tool({
    name: 'cosmic_config_check',
    description:
      'ok-cosmic Step 0 预检：使用 DSH 插件设置中的知识库路径、统一路由和扩展代码库，检查 Python 依赖与配置完整性。生成或修改苍穹 Java 代码前先调用。',
    parameters: {
      strict: { type: 'boolean', description: '把 WARNING 也视为失败。' },
      json: { type: 'boolean', description: '输出机器可读 JSON。' },
    },
    run: async (args, signal, settings) => {
      const timeout = Math.max(settings.toolTimeoutSeconds, settings.configCheckInstallTimeoutSeconds + 30)
      return runScript(
        settings,
        'cosmic-config-check.py',
        (configPath) => {
          const command = ['--config', configPath]
          if (args.strict) command.push('--strict')
          if (args.json) command.push('--json')
          return command
        },
        { signal, timeoutSeconds: timeout },
      )
    },
  })

  const searchClasses = tool({
    name: 'cosmic_search_classes',
    description:
      '在离线苍穹 API 知识库中按类名/包名模糊或正则搜索类。用于确认 SDK 类名、继承链和所属包；多个关键词可一次传入。',
    parameters: {
      query: { type: 'string', required: true, description: '类名或包名关键词，空格分隔。' },
      regex: { type: 'boolean', description: '把 query 当作正则表达式。' },
      matchAll: { type: 'boolean', description: '要求所有关键词都命中（默认任一命中）。' },
      classPrefix: {
        type: 'array',
        items: { type: 'string' },
        description: '只保留指定包/类前缀下的结果，可重复。',
      },
      classRegex: { type: 'string', description: '按完整类名正则进一步过滤。' },
      kinds: { type: 'array', items: { type: 'string', enum: KIND_VALUES }, description: '类目过滤。' },
      page: { type: 'integer', description: '页码，默认 1。' },
      pageSize: { type: 'integer', description: '每页条数，默认取插件设置。' },
    },
    run: async (args, signal, settings) => {
      const pageSize = Number.isInteger(args.pageSize) ? args.pageSize : settings.limitSearchPageSize
      return runScript(
        settings,
        'cosmic-api-knowledge.py',
        (configPath) => {
          const command = ['--config', configPath, 'search', ...splitTerms(String(args.query).split(/\s+/))]
          if (args.regex) command.push('--regex')
          if (args.matchAll) command.push('--all')
          for (const prefix of args.classPrefix || []) command.push('--class-prefix', String(prefix))
          if (args.classRegex) command.push('--class-regex', String(args.classRegex))
          for (const kind of args.kinds || []) command.push('--kind', String(kind))
          command.push('--page', String(Number.isInteger(args.page) ? args.page : 1))
          command.push('--page-size', String(pageSize))
          return command
        },
        { signal },
      )
    },
  })

  const searchMethods = tool({
    name: 'cosmic_search_methods',
    description:
      '在离线苍穹 API 知识库中跨类搜索方法名，返回带类名和签名的候选。确认方法签名、重载或所属类时使用。',
    parameters: {
      query: { type: 'string', required: true, description: '方法名关键词，空格分隔。' },
      matchAll: { type: 'boolean', description: '要求所有关键词都命中（默认任一命中）。' },
      classPrefix: {
        type: 'array',
        items: { type: 'string' },
        description: '只保留指定包/类前缀下的方法，可重复。',
      },
      classRegex: { type: 'string', description: '按类名正则进一步过滤。' },
      kinds: { type: 'array', items: { type: 'string', enum: KIND_VALUES }, description: '类目过滤。' },
      page: { type: 'integer', description: '页码，默认 1。' },
      pageSize: { type: 'integer', description: '每页条数，默认取插件设置。' },
    },
    run: async (args, signal, settings) => {
      const pageSize = Number.isInteger(args.pageSize) ? args.pageSize : settings.limitSearchPageSize
      return runScript(
        settings,
        'cosmic-api-knowledge.py',
        (configPath) => {
          const command = [
            '--config',
            configPath,
            'search-method',
            ...splitTerms(String(args.query).split(/\s+/)),
          ]
          if (args.matchAll) command.push('--all')
          for (const prefix of args.classPrefix || []) command.push('--class-prefix', String(prefix))
          if (args.classRegex) command.push('--class-regex', String(args.classRegex))
          for (const kind of args.kinds || []) command.push('--kind', String(kind))
          command.push('--page', String(Number.isInteger(args.page) ? args.page : 1))
          command.push('--page-size', String(pageSize))
          return command
        },
        { signal },
      )
    },
  })

  const classDetail = tool({
    name: 'cosmic_get_class_detail',
    description:
      '读取一个或多个类（逗号分隔）的完整方法签名、返回类型、注释和继承方法。可用 method 过滤、declaredOnly 只看本类声明。',
    parameters: {
      className: { type: 'string', required: true, description: '完整类名；多个用逗号分隔。' },
      method: { type: 'string', description: '按方法名模糊过滤。' },
      compact: { type: 'boolean', description: '紧凑输出（默认 true，适合模型阅读）。' },
      declaredOnly: { type: 'boolean', description: '只显示目标类自身声明的方法。' },
    },
    run: async (args, signal, settings) =>
      runScript(
        settings,
        'cosmic-api-knowledge.py',
        (configPath) => {
          const command = ['--config', configPath, 'detail', String(args.className)]
          if (args.method) command.push('--method', String(args.method))
          if (args.declaredOnly) command.push('--declared-only')
          if (args.compact !== false) command.push('--compact')
          return command
        },
        { signal },
      ),
  })

  const metaFields = tool({
    name: 'cosmic_get_meta_fields',
    description:
      '查询表单元数据字段：字段标识、类型、所属实体（表头/表体/子表体）、refType、枚举 extMap、操作和 SQL 信息。支持逗号分隔批量查询与中文单据名。',
    parameters: {
      formIdOrName: { type: 'string', required: true, description: 'formId 或中文单据名；多个用逗号分隔。' },
      fuzzy: { type: 'array', items: { type: 'string' }, description: '字段关键词或正则。' },
      types: { type: 'array', items: { type: 'string' }, description: '按字段类型过滤，支持正则。' },
      showDetail: { type: 'boolean', description: '显示枚举 extMap 或基础资料 refType。' },
      tree: { type: 'boolean', description: '按 parentKey 输出字段树。' },
      operations: { type: 'array', items: { type: 'string' }, description: '按关键词筛选操作。' },
      listOperations: { type: 'boolean', description: '列出全部操作。' },
      sql: { type: 'boolean', description: '显示库名、表名和数据库字段名。' },
      sort: { type: 'string', enum: ['key', 'name', 'type', 'dbKey'], description: '字段排序方式。' },
      view: { type: 'string', enum: ['form', 'entity', 'operate', 'all'], description: '元数据视图范围。' },
      refresh: { type: 'boolean', description: '跳过本地缓存，强制刷新。' },
      debug: { type: 'boolean', description: '输出请求调试信息。' },
    },
    run: async (args, signal, settings) =>
      runScript(
        settings,
        'cosmic-form-metadata.py',
        (configPath) => {
          const command = ['--config', configPath, 'get', String(args.formIdOrName)]
          const fuzzy = args.fuzzy || []
          if (fuzzy.length > 0) command.push('--fuzzy', ...fuzzy.map(String))
          const types = args.types || []
          if (types.length > 0) command.push('--type', ...types.map(String))
          if (args.showDetail) command.push('--show-detail')
          if (args.tree) command.push('--tree')
          const operations = args.operations || []
          if (operations.length > 0) command.push('--op', ...operations.map(String))
          else if (args.listOperations) command.push('--op')
          if (args.sql) command.push('--sql')
          command.push('--sort', String(args.sort || settings.formMetadataDefaultSort))
          command.push('--view', String(args.view || settings.formMetadataDefaultView))
          if (args.refresh) command.push('--refresh')
          if (args.debug || settings.debug) command.push('--debug')
          return command
        },
        { signal },
      ),
  })

  const basedata = tool({
    name: 'cosmic_query_basedata',
    description:
      '按已确认的 entityId 查询基础资料编码/名称/完整数据。entityId 来源不明时，先用 cosmic_get_meta_fields 查 refType，不要猜英文标识。',
    parameters: {
      entityId: { type: 'string', required: true, description: '已确认的基础资料 entityId，例如 bd_supplier。' },
      numberOrName: { type: 'string', description: '编码或名称；为空时通常返回前若干条映射。' },
      full: { type: 'boolean', description: '返回完整字段。' },
      json: { type: 'boolean', description: '直接输出 JSON。' },
      refresh: { type: 'boolean', description: '删除当前条件缓存后重新请求。' },
      debug: { type: 'boolean', description: '输出请求调试信息。' },
    },
    run: async (args, signal, settings) =>
      runScript(
        settings,
        'cosmic-basedata-query.py',
        (configPath) => {
          const command = ['--config', configPath, 'get', '--entity-id', String(args.entityId)]
          if (args.numberOrName) command.push('--number-or-name', String(args.numberOrName))
          if (args.full) command.push('--full')
          if (args.json) command.push('--json')
          if (args.refresh) command.push('--refresh')
          if (args.debug || settings.debug) command.push('--debug')
          return command
        },
        { signal },
      ),
  })

  const extpoints = tool({
    name: 'cosmic_query_extpoints',
    description:
      '按业务关键词查询苍穹业务拓展点候选接口。默认摘要；需要生成代码或查看 Java 示例时传 full=true。',
    parameters: {
      keyword: { type: 'string', required: true, description: '业务关键词，例如 应付、库存、审核。' },
      full: { type: 'boolean', description: '输出完整 JSON（含 Java 示例）。' },
      json: { type: 'boolean', description: '直接输出 JSON。' },
    },
    run: async (args, signal, settings) =>
      runScript(
        settings,
        'cosmic-extpoints-query.py',
        (configPath) => {
          const command = ['--config', configPath, 'get', '--keyword', String(args.keyword)]
          if (args.full) command.push('--full')
          if (args.json) command.push('--json')
          if (settings.debug) command.push('--debug')
          return command
        },
        { signal },
      ),
  })

  const postCheck = tool({
    name: 'cosmic_post_check',
    description:
      '苍穹 Java 代码生成/修改后的统一校验：优先 Gradle 编译，随后执行 A/B/C 分层静态检查。返回 ERROR / WARNING / INFO 与修复提示。',
    parameters: {
      path: { type: 'string', required: true, description: '要检查的 Java 文件或目录路径。' },
      fixHint: { type: 'boolean', description: '显示修复提示（默认 true）。' },
      strict: { type: 'boolean', description: '严格模式，把更多治理项纳入检查。' },
      json: { type: 'boolean', description: '输出 JSON。' },
      minSeverity: {
        type: 'string',
        enum: ['error', 'warning', 'info'],
        description: '最低报告级别，默认取插件设置。',
      },
    },
    run: async (args, signal, settings) =>
      runScript(
        settings,
        'cosmic-post-check.py',
        (configPath) => {
          const command = [String(args.path), '--config', configPath]
          if (args.fixHint !== false) command.push('--fix-hint')
          if (args.strict) command.push('--strict')
          if (args.json) command.push('--json')
          command.push('--min-severity', String(args.minSeverity || settings.postCheckLintMinSeverity))
          return command
        },
        { signal, timeoutSeconds: settings.postCheckTimeoutSeconds },
      ),
  })

  return [
    configCheck,
    searchClasses,
    searchMethods,
    classDetail,
    metaFields,
    basedata,
    extpoints,
    postCheck,
  ]
}

/**
 * Register every tool on `ctx.tools`.
 * @param {any} ctx
 * @param {any} config
 * @returns {() => void}
 */
export function registerTools(ctx, config) {
  const settingsOf = (baseDir) => readSettings(config, baseDir)
  const tools = buildTools(settingsOf)
  const disposers = tools.map((tool) => ctx.tools.register(tool))
  return () => {
    for (const dispose of disposers.reverse()) {
      try {
        dispose()
      } catch {
        // A failed unregister must not block the remaining disposers.
      }
    }
  }
}
