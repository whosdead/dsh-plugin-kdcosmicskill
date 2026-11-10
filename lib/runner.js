/**
 * Spawn the bundled ok-cosmic Python scripts with a per-call generated config
 * file. The config file lives in a private temporary directory and is removed
 * after the process settles; nothing is written into the user's project.
 *
 * Child output goes to two temporary files instead of pipes. Pipes are blocked
 * in confined DSH shells on Windows, and file-backed output also avoids pipe
 * buffer deadlocks for verbose Gradle builds.
 */
import { spawn } from 'node:child_process'
import { closeSync, existsSync, openSync, readFileSync } from 'node:fs'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { buildScriptConfig } from './settings.js'

/** Absolute directory that holds SKILL.md, scripts, rules, references and assets. */
export const SKILL_ROOT = fileURLToPath(new URL('../skill/', import.meta.url))
/** Absolute directory that holds the bundled Python entry points. */
export const SCRIPT_DIR = path.join(SKILL_ROOT, 'scripts')

const ANSI_PATTERN = /\u001b(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001b\\))/g

/** @param {string} text */
export function stripAnsi(text) {
  return text.replace(ANSI_PATTERN, '')
}

/** @param {string} text */
function trimTrailing(text) {
  return text.replace(/\s+$/u, '')
}

/**
 * Read at most `maxBytes` of a captured stream.
 * @param {string} file
 * @param {number} maxBytes
 */
function readCaptured(file, maxBytes) {
  try {
    const buffer = readFileSync(file)
    if (buffer.length <= maxBytes) return { text: buffer.toString('utf8'), truncated: false }
    return { text: buffer.subarray(0, maxBytes).toString('utf8'), truncated: true }
  } catch {
    return { text: '', truncated: false }
  }
}

/**
 * @param {ReturnType<import('./settings.js').readSettings>} settings
 * @param {string} scriptName
 * @param {(configPath: string) => string[]} buildArgs
 * @param {{ signal?: AbortSignal, timeoutSeconds?: number }} [options]
 * @returns {Promise<string>}
 */
export async function runScript(settings, scriptName, buildArgs, options = {}) {
  const scriptPath = path.join(SCRIPT_DIR, scriptName)
  if (!existsSync(scriptPath)) {
    throw new Error(`未找到内置脚本 ${scriptName}，请重新安装 dsh-plugin-kdcosmicskill 插件。`)
  }

  const tempDir = await mkdtemp(path.join(tmpdir(), 'dsh-plugin-kdcosmicskill-'))
  const configPath = path.join(tempDir, 'ok-cosmic.json')
  const stdoutPath = path.join(tempDir, 'stdout.log')
  const stderrPath = path.join(tempDir, 'stderr.log')
  await writeFile(configPath, `${JSON.stringify(buildScriptConfig(settings), null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  })

  const timeoutSeconds = Number.isFinite(options.timeoutSeconds)
    ? Number(options.timeoutSeconds)
    : settings.toolTimeoutSeconds
  const maxBytes = settings.maxOutputBytes

  /** @type {Record<string, string>} */
  const env = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (key.startsWith('COSMIC_')) continue
    if (value !== undefined) env[key] = value
  }
  Object.assign(
    env,
    {
      PYTHONIOENCODING: 'utf-8',
      PYTHONUTF8: '1',
      PYTHONUNBUFFERED: '1',
      NO_COLOR: '1',
      FORCE_COLOR: '0',
      TERM: 'dumb',
    },
    settings.extraEnv,
  )

  const args = buildArgs(configPath)

  try {
    const result = await new Promise((resolve) => {
      /** @type {import('node:child_process').ChildProcess | undefined} */
      let child
      let outFd = -1
      let errFd = -1
      let timedOut = false
      let aborted = Boolean(options.signal?.aborted)
      let settled = false
      /** @type {NodeJS.Timeout | undefined} */
      let timeoutTimer
      /** @type {NodeJS.Timeout | undefined} */
      let forceTimer

      const closeFiles = () => {
        if (outFd >= 0) {
          try {
            closeSync(outFd)
          } catch {
            // Best effort.
          }
          outFd = -1
        }
        if (errFd >= 0) {
          try {
            closeSync(errFd)
          } catch {
            // Best effort.
          }
          errFd = -1
        }
      }

      const finish = (code, signal, spawnError) => {
        if (settled) return
        settled = true
        if (timeoutTimer) clearTimeout(timeoutTimer)
        if (forceTimer) clearTimeout(forceTimer)
        options.signal?.removeEventListener('abort', onAbort)
        closeFiles()
        const out = readCaptured(stdoutPath, maxBytes)
        const err = readCaptured(stderrPath, maxBytes)
        resolve({
          code: typeof code === 'number' ? code : null,
          signal: signal ?? null,
          stdout: stripAnsi(out.text),
          stderr: stripAnsi(err.text),
          truncated: out.truncated || err.truncated,
          timedOut,
          aborted,
          spawnError: spawnError ? String(spawnError.message || spawnError) : '',
        })
      }

      const terminate = () => {
        if (!child?.pid) return
        if (process.platform === 'win32') {
          try {
            const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
              windowsHide: true,
              stdio: 'ignore',
            })
            killer.on('error', () => {})
          } catch {
            // Best effort only.
          }
        } else {
          try {
            process.kill(-child.pid, 'SIGKILL')
          } catch {
            try {
              child.kill('SIGKILL')
            } catch {
              // Best effort only.
            }
          }
        }
        forceTimer = setTimeout(() => finish(null, 'SIGKILL'), 5000)
      }

      const onAbort = () => {
        aborted = true
        terminate()
      }

      if (aborted) {
        settled = true
        resolve({
          code: null,
          signal: null,
          stdout: '',
          stderr: '',
          truncated: false,
          timedOut: false,
          aborted: true,
          spawnError: '',
        })
        return
      }

      try {
        outFd = openSync(stdoutPath, 'w')
        errFd = openSync(stderrPath, 'w')
        child = spawn(settings.pythonPath, [scriptPath, ...args], {
          cwd: settings.workingDirectory,
          env,
          windowsHide: true,
          detached: process.platform !== 'win32',
          stdio: ['ignore', outFd, errFd],
        })
      } catch (error) {
        finish(null, null, error)
        return
      }

      child.on('error', (error) => finish(null, null, error))
      child.on('close', (code, signal) => finish(code, signal))

      timeoutTimer = setTimeout(() => {
        timedOut = true
        terminate()
      }, Math.max(1, timeoutSeconds) * 1000)

      options.signal?.addEventListener('abort', onAbort, { once: true })
      if (options.signal?.aborted) onAbort()
    })

    const chunks = []
    const out = trimTrailing(result.stdout)
    const err = trimTrailing(result.stderr)
    if (out) chunks.push(out)
    if (err) chunks.push(`[stderr]\n${err}`)
    if (result.spawnError) chunks.push(`[启动失败] ${result.spawnError}`)
    if (result.timedOut) chunks.push(`[超时] 工具在 ${timeoutSeconds} 秒后仍未结束，已终止。`)
    if (result.aborted) chunks.push('[已取消] 调用方取消了本次执行。')
    if (typeof result.code === 'number' && result.code !== 0) chunks.push(`[exit code: ${result.code}]`)
    if (result.truncated) {
      chunks.push(`[输出截断] 仅返回前 ${maxBytes} 字节；调大 maxOutputBytes 可查看完整输出。`)
    }
    return chunks.join('\n\n') || '(无输出)'
  } finally {
    await rm(tempDir, { recursive: true, force: true }).catch(() => {})
  }
}
