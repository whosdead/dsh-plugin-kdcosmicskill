/**
 * Resolve the live Cordis config into a plain, fully-defaulted settings
 * snapshot. Every consumer (tools, skill body, script config file) calls this
 * per operation, which is what makes settings changes hot.
 */
import { homedir } from 'node:os'
import path from 'node:path'

/** @param {unknown} value */
function unwrapVolatile(value) {
  if (value && typeof value === 'object' && typeof value.get === 'function') {
    try {
      return value.get()
    } catch {
      return undefined
    }
  }
  return value
}

/**
 * DSH exposes `.volatile()` Config fields as live wrappers with `.get()`, not
 * as plain values. Unwrap every top-level field so the rest of the plugin can
 * treat the result as ordinary JSON-shaped settings.
 * @param {unknown} config
 */
function plainConfig(config) {
  const source = config && typeof config === 'object' ? config : {}
  /** @type {Record<string, unknown>} */
  const plain = {}
  for (const key of Object.keys(source)) {
    plain[key] = unwrapVolatile(source[key])
  }
  return plain
}

/** @param {unknown} value @param {string} fallback */
function asString(value, fallback = '') {
  if (typeof value === 'string') return value
  if (value === null || value === undefined) return fallback
  return String(value)
}

/** @param {unknown} value @param {boolean} fallback */
function asBoolean(value, fallback) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (['true', '1', 'yes', 'on'].includes(normalized)) return true
    if (['false', '0', 'no', 'off'].includes(normalized)) return false
  }
  return fallback
}

/** @param {unknown} value @param {number} fallback @param {number} min @param {number} max */
function asInt(value, fallback, min, max) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return fallback
  return Math.min(max, Math.max(min, Math.trunc(numeric)))
}

/** @param {unknown} value @param {number} fallback @param {number} min @param {number} max */
function asNumber(value, fallback, min, max) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return fallback
  return Math.min(max, Math.max(min, numeric))
}

/** @param {unknown} value */
function asLines(value) {
  if (!Array.isArray(value)) return []
  return value.map((item) => asString(item).trim()).filter(Boolean)
}

/** @param {string} raw @param {string} baseDir */
function resolvePath(raw, baseDir) {
  let text = raw.trim()
  if (!text) return ''
  if (text === '~') text = homedir()
  else if (text.startsWith('~/') || text.startsWith('~\\')) text = path.join(homedir(), text.slice(2))
  return path.isAbsolute(text) ? path.normalize(text) : path.resolve(baseDir, text)
}

/**
 * Parse KEY=VALUE lines into an environment object. Malformed lines are
 * ignored rather than failing a tool call.
 * @param {readonly string[]} lines
 */
function parseEnv(lines) {
  /** @type {Record<string, string>} */
  const env = {}
  for (const line of lines) {
    const index = line.indexOf('=')
    if (index <= 0) continue
    const key = line.slice(0, index).trim()
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue
    env[key] = line.slice(index + 1)
  }
  return env
}

const SORTS = new Set(['key', 'name', 'type', 'dbKey'])
const VIEWS = new Set(['form', 'entity', 'operate', 'all'])
const SEVERITIES = new Set(['error', 'warning', 'info'])

/**
 * @param {any} config
 * @param {string} [baseDir] directory used when `workingDirectory` is blank
 */
export function readSettings(config, baseDir = process.cwd()) {
  const raw = plainConfig(config)
  const workingDirectoryRaw = asString(raw.workingDirectory).trim()
  const workingDirectory = workingDirectoryRaw
    ? resolvePath(workingDirectoryRaw, process.cwd())
    : baseDir

  const knowledgeDbRaw = asString(raw.knowledgeDbPath).trim()
  const routeApiUrl = asString(raw.routeApiUrl).trim()

  const sort = asString(raw.formMetadataDefaultSort, 'key').trim()
  const view = asString(raw.formMetadataDefaultView, 'all').trim()
  const severity = asString(raw.postCheckLintMinSeverity, 'info').trim().toLowerCase()

  return {
    skillName: asString(raw.skillName, 'ok-cosmic').trim() || 'ok-cosmic',
    skillDescription: asString(raw.skillDescription).trim(),
    skillRank: asInt(raw.skillRank, 600, 0, 10000),
    skillIncludeConfigSummary: asBoolean(raw.skillIncludeConfigSummary, true),
    skillExtraInstructions: asString(raw.skillExtraInstructions),

    pythonPath: asString(raw.pythonPath, 'python').trim() || 'python',
    workingDirectory,
    workingDirectoryExplicit: Boolean(workingDirectoryRaw),
    toolTimeoutSeconds: asInt(raw.toolTimeoutSeconds, 120, 1, 86400),
    maxOutputBytes: asInt(raw.maxOutputBytes, 200000, 4096, 10485760),
    extraEnv: parseEnv(asLines(raw.extraEnv)),
    debug: asBoolean(raw.debug, false),

    knowledgeDbPath: knowledgeDbRaw ? resolvePath(knowledgeDbRaw, workingDirectory) : '',
    routeApiUrl,
    routeTimeoutSeconds: asNumber(raw.routeTimeoutSeconds, 10, 0.5, 600),
    routeOpenApiSign: asString(raw.routeOpenApiSign).trim(),
    routeApiToken: asString(raw.routeApiToken).trim(),
    routeSkipSslVerify: asBoolean(raw.routeSkipSslVerify, true),

    extensionRepos: asLines(raw.extensionRepos).map((item) => resolvePath(item, workingDirectory)),

    cacheFormMetadataTtlSeconds: asInt(raw.cacheFormMetadataTtlSeconds, 600, 0, 31536000),
    cacheBasedataTtlSeconds: asInt(raw.cacheBasedataTtlSeconds, 600, 0, 31536000),
    cacheSqliteBusyTimeoutMs: asInt(raw.cacheSqliteBusyTimeoutMs, 5000, 0, 600000),
    limitSearchPageSize: asInt(raw.limitSearchPageSize, 20, 1, 500),
    limitBatchWorkers: asInt(raw.limitBatchWorkers, 4, 1, 32),
    limitMaxDisplayRecords: asInt(raw.limitMaxDisplayRecords, 120, 1, 5000),
    formMetadataDefaultSort: SORTS.has(sort) ? sort : 'key',
    formMetadataDefaultView: VIEWS.has(view) ? view : 'all',

    postCheckRequired: asBoolean(raw.postCheckRequired, true),
    postCheckMaxRounds: asInt(raw.postCheckMaxRounds, 3, 0, 10),
    postCheckGradleEnabled: asBoolean(raw.postCheckGradleEnabled, true),
    postCheckGradleCommand: asString(raw.postCheckGradleCommand).trim(),
    postCheckGradleTask: asString(raw.postCheckGradleTask, 'compileJava').trim() || 'compileJava',
    postCheckGradleArgs: asString(raw.postCheckGradleArgs, '--console=plain').trim(),
    postCheckTimeoutSeconds: asInt(raw.postCheckTimeoutSeconds, 600, 1, 86400),
    postCheckJavaHome: (() => {
      const value = asString(raw.postCheckJavaHome).trim()
      return value ? resolvePath(value, workingDirectory) : ''
    })(),
    postCheckLintMinSeverity: SEVERITIES.has(severity) ? severity : 'info',

    configCheckAutoInstall: asBoolean(raw.configCheckAutoInstall, true),
    configCheckInstallTimeoutSeconds: asInt(raw.configCheckInstallTimeoutSeconds, 120, 1, 3600),
  }
}

/**
 * The `ok-cosmic.json` payload handed to the bundled Python scripts. It is
 * written to a private temporary directory per call, so no project-level
 * config file is required and no values are duplicated in code.
 * @param {ReturnType<typeof readSettings>} settings
 */
export function buildScriptConfig(settings) {
  return {
    graph: {
      dbPath: settings.knowledgeDbPath,
    },
    route: {
      apiUrl: settings.routeApiUrl,
      timeoutSeconds: settings.routeTimeoutSeconds,
      openApiSign: settings.routeOpenApiSign,
      apiToken: settings.routeApiToken,
      skipSslVerify: settings.routeSkipSslVerify,
    },
    extensionRepos: settings.extensionRepos,
    cache: {
      formMetadataTtlSeconds: settings.cacheFormMetadataTtlSeconds,
      basedataTtlSeconds: settings.cacheBasedataTtlSeconds,
      sqliteBusyTimeoutMs: settings.cacheSqliteBusyTimeoutMs,
    },
    limits: {
      searchPageSize: settings.limitSearchPageSize,
      batchWorkers: settings.limitBatchWorkers,
      maxDisplayRecords: settings.limitMaxDisplayRecords,
    },
    postCheck: {
      required: settings.postCheckRequired,
      maxRounds: settings.postCheckMaxRounds,
      gradleEnabled: settings.postCheckGradleEnabled,
      gradleCommand: settings.postCheckGradleCommand,
      gradleTask: settings.postCheckGradleTask,
      gradleArgs: settings.postCheckGradleArgs,
      timeoutSeconds: settings.postCheckTimeoutSeconds,
      javaHome: settings.postCheckJavaHome,
      lintMinSeverity: settings.postCheckLintMinSeverity,
    },
    python: {
      autoInstallDependencies: settings.configCheckAutoInstall,
      installTimeoutSeconds: settings.configCheckInstallTimeoutSeconds,
    },
    debug: settings.debug,
    __plugin__: 'dsh-ok-cosmic',
  }
}
