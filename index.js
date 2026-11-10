/**
 * dsh-ok-cosmic host half.
 *
 * Registers the eight ok-cosmic tools and the `ok-cosmic` skill provider. The
 * browser half (client.js) registers the settings page that edits the same
 * Cordis Config row.
 */
import { Config } from './lib/config.js'
import { registerSkill } from './lib/skill.js'
import { registerTools } from './lib/tools.js'

/** Cordis plugin name. */
export const name = 'ok-cosmic'

/** Both registries must be composed; the DSH base bundle provides them. */
export const inject = ['tools', 'skills']

export { Config }

/**
 * @param {any} ctx
 * @param {any} config
 */
export function apply(ctx, config) {
  ctx.effect(() => registerTools(ctx, config), 'ok-cosmic: tools')
  ctx.effect(() => registerSkill(ctx, config), 'ok-cosmic: skill')
}
