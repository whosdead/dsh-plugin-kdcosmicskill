/**
 * The ok-cosmic skill provider.
 *
 * The body is re-read from the bundled SKILL.md on every load, so edits to the
 * resource file and to the plugin settings are visible without reinstalling or
 * restarting the plugin.
 */
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import { DEFAULT_SKILL_DESCRIPTION } from './config.js'
import { readSettings } from './settings.js'
import { SKILL_ROOT } from './runner.js'

const PROVIDER_NAME = 'ok-cosmic'
const SKILL_FILE = path.join(SKILL_ROOT, 'SKILL.md')
const RESOURCE_BASE = { kind: 'directory', path: SKILL_ROOT }

/** @param {string} url */
function describeUrl(url) {
  if (!url) return '未配置'
  try {
    const parsed = new URL(url)
    return `${parsed.protocol}//${parsed.host}${parsed.pathname}`
  } catch {
    return url.split('?')[0] || '已配置'
  }
}

/** @param {string} raw */
function stripFrontmatter(raw) {
  const match = raw.match(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/)
  return match ? raw.slice(match[0].length) : raw
}

/**
 * @param {ReturnType<import('./settings.js').readSettings>} settings
 * @param {string} body
 */
function appendEffectiveConfig(settings, body) {
  const repos =
    settings.extensionRepos.length > 0
      ? settings.extensionRepos.map((repo) => `  - \`${repo}\``).join('\n')
      : '  - 未配置'
  const lines = [
    '',
    '## 当前生效配置（DSH 插件设置）',
    '',
    '> 以下值来自 DSH 设置中的 ok-cosmic 插件，**修改后下一次调用立即生效**。不要在项目中创建或修改 `ok-cosmic.json`，也不要直接执行 `scripts/*.py`；请使用 `cosmic_*` 工具。',
    '',
    `- 知识库：\`${settings.knowledgeDbPath || '未配置'}\``,
    `- 统一路由：\`${describeUrl(settings.routeApiUrl)}\``,
    `- 扩展代码库：`,
    repos,
    `- Python：\`${settings.pythonPath}\`；工作目录：\`${settings.workingDirectoryExplicit ? settings.workingDirectory : '(会话工作目录)'}\`；工具超时 ${settings.toolTimeoutSeconds} 秒`,
    `- 查询限额：每页 ${settings.limitSearchPageSize} 条，并发 ${settings.limitBatchWorkers}，单次最多展示 ${settings.limitMaxDisplayRecords} 条；缓存 TTL ${settings.cacheFormMetadataTtlSeconds}/${settings.cacheBasedataTtlSeconds} 秒`,
    `- 代码校验：${settings.postCheckRequired ? '生成后必须执行' : '按需执行'}，最多 ${settings.postCheckMaxRounds} 轮；Gradle ${settings.postCheckGradleEnabled ? `启用（${settings.postCheckGradleCommand || '自动探测'}，任务 ${settings.postCheckGradleTask}，超时 ${settings.postCheckTimeoutSeconds} 秒）` : '禁用'}；lint 最低级别 \`${settings.postCheckLintMinSeverity}\``,
    '',
  ]
  return `${body.trimEnd()}\n${lines.join('\n')}`
}

/**
 * @param {ReturnType<import('./settings.js').readSettings>} settings
 */
async function buildContent(settings) {
  const raw = await readFile(SKILL_FILE, 'utf8')
  let body = stripFrontmatter(raw)
    .replaceAll('{{POST_CHECK_MAX_ROUNDS}}', String(settings.postCheckMaxRounds))
    .replaceAll('{{POST_CHECK_REQUIRED}}', settings.postCheckRequired ? '必须' : '按需')
    .replaceAll('{{SKILL_NAME}}', settings.skillName)

  if (settings.skillIncludeConfigSummary) {
    body = appendEffectiveConfig(settings, body)
  }

  const extra = settings.skillExtraInstructions.trim()
  if (extra) {
    body = `${body.trimEnd()}\n\n## 用户补充指令\n\n${extra}\n`
  }

  const description = (settings.skillDescription || DEFAULT_SKILL_DESCRIPTION).replace(/\s+/g, ' ').trim()
  const frontmatter = `---\nname: ${JSON.stringify(settings.skillName)}\ndescription: ${JSON.stringify(description)}\n---\n\n`
  return `${frontmatter}${body.trimStart()}`
}

/**
 * @param {ReturnType<import('./settings.js').readSettings>} settings
 */
function buildCandidate(settings) {
  return {
    name: settings.skillName,
    description: (settings.skillDescription || DEFAULT_SKILL_DESCRIPTION).replace(/\s+/g, ' ').trim(),
    invocation: { modelInvocable: true, userInvocable: true },
    provider: PROVIDER_NAME,
    source: 'bundled',
    resourceBase: RESOURCE_BASE,
    rank: settings.skillRank,
    locator: pathToFileURL(SKILL_FILE),
  }
}

/**
 * Register the ok-cosmic skill provider.
 * @param {any} ctx
 * @param {any} config
 * @returns {() => void}
 */
export function registerSkill(ctx, config) {
  const provider = {
    name: PROVIDER_NAME,
    list: async () => [buildCandidate(readSettings(config))],
    get: async () => {
      const settings = readSettings(config)
      return {
        ...buildCandidate(settings),
        content: await buildContent(settings),
      }
    },
  }
  return ctx.skills.registerProvider(() => provider)
}
