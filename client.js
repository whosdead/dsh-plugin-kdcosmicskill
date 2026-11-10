/**
 * dsh-plugin-kdcosmicskill browser half: the plugin's settings page.
 *
 * The page edits the `ok-cosmic` profile entry through the shared
 * `ctx.configForms` mirror. It is registered both as a Settings section and as
 * the bundle's configuration page on the sidebar Plugins page, so it is
 * reachable from either surface. Nothing is written until Save; the Host
 * validates the complete Config and the next tool call sees the new values.
 */
window.__ModuleLoader__.load({
  id: 'dsh-plugin-kdcosmicskill',
  factory(require) {
    const { jsx, jsxs } = require('react/jsx-runtime')
    const P = require('@deepseek-ai/dsh-client-ui-primitives')

    const NS = 'ok-cosmic.settings'
    const ENTRY = 'ok-cosmic'

    /** @type {{id: string, title: string}[]} */
    const GROUPS = [
      { id: 'runtime', title: 'groupRuntime' },
      { id: 'knowledge', title: 'groupKnowledge' },
      { id: 'repos', title: 'groupRepos' },
      { id: 'limits', title: 'groupLimits' },
      { id: 'postCheck', title: 'groupPostCheck' },
      { id: 'skill', title: 'groupSkill' },
      { id: 'configCheck', title: 'groupConfigCheck' },
    ]

    /** @type {{field: string, group: string, kind: 'text'|'int'|'bool'|'lines'|'longtext', label: string, hint?: string}[]} */
    const FIELDS = [
      { field: 'skillName', group: 'skill', kind: 'text', label: 'skillName', hint: 'hint_skillName', pattern: /^[a-z0-9]+(?:-[a-z0-9]+)*$/ },
      { field: 'skillDescription', group: 'skill', kind: 'longtext', label: 'skillDescription', hint: 'hint_skillDescription' },
      { field: 'skillRank', group: 'skill', kind: 'int', label: 'skillRank', hint: 'hint_skillRank' },
      { field: 'skillIncludeConfigSummary', group: 'skill', kind: 'bool', label: 'skillIncludeConfigSummary', hint: 'hint_skillIncludeConfigSummary' },
      { field: 'skillExtraInstructions', group: 'skill', kind: 'longtext', label: 'skillExtraInstructions', hint: 'hint_skillExtraInstructions' },

      { field: 'pythonPath', group: 'runtime', kind: 'text', label: 'pythonPath', hint: 'hint_pythonPath' },
      { field: 'workingDirectory', group: 'runtime', kind: 'text', label: 'workingDirectory', hint: 'hint_workingDirectory' },
      { field: 'toolTimeoutSeconds', group: 'runtime', kind: 'int', label: 'toolTimeoutSeconds' },
      { field: 'maxOutputBytes', group: 'runtime', kind: 'int', label: 'maxOutputBytes', hint: 'hint_maxOutputBytes' },
      { field: 'extraEnv', group: 'runtime', kind: 'lines', label: 'extraEnv', hint: 'hint_extraEnv' },
      { field: 'debug', group: 'runtime', kind: 'bool', label: 'debug' },

      { field: 'knowledgeDbPath', group: 'knowledge', kind: 'text', label: 'knowledgeDbPath', hint: 'hint_knowledgeDbPath' },
      { field: 'routeApiUrl', group: 'knowledge', kind: 'text', label: 'routeApiUrl', hint: 'hint_routeApiUrl' },
      { field: 'routeTimeoutSeconds', group: 'knowledge', kind: 'int', label: 'routeTimeoutSeconds' },
      { field: 'routeOpenApiSign', group: 'knowledge', kind: 'text', label: 'routeOpenApiSign', hint: 'hint_routeOpenApiSign' },
      { field: 'routeApiToken', group: 'knowledge', kind: 'text', label: 'routeApiToken', hint: 'hint_routeApiToken' },
      { field: 'routeSkipSslVerify', group: 'knowledge', kind: 'bool', label: 'routeSkipSslVerify', hint: 'hint_routeSkipSslVerify' },

      { field: 'extensionRepos', group: 'repos', kind: 'lines', label: 'extensionRepos', hint: 'hint_extensionRepos' },

      { field: 'cacheFormMetadataTtlSeconds', group: 'limits', kind: 'int', label: 'cacheFormMetadataTtlSeconds' },
      { field: 'cacheBasedataTtlSeconds', group: 'limits', kind: 'int', label: 'cacheBasedataTtlSeconds' },
      { field: 'cacheSqliteBusyTimeoutMs', group: 'limits', kind: 'int', label: 'cacheSqliteBusyTimeoutMs' },
      { field: 'limitSearchPageSize', group: 'limits', kind: 'int', label: 'limitSearchPageSize' },
      { field: 'limitBatchWorkers', group: 'limits', kind: 'int', label: 'limitBatchWorkers' },
      { field: 'limitMaxDisplayRecords', group: 'limits', kind: 'int', label: 'limitMaxDisplayRecords', hint: 'hint_limitMaxDisplayRecords' },
      { field: 'formMetadataDefaultSort', group: 'limits', kind: 'text', label: 'formMetadataDefaultSort', hint: 'hint_formMetadataDefaultSort', pattern: /^(key|name|type|dbKey)$/ },
      { field: 'formMetadataDefaultView', group: 'limits', kind: 'text', label: 'formMetadataDefaultView', hint: 'hint_formMetadataDefaultView', pattern: /^(form|entity|operate|all)$/ },

      { field: 'postCheckRequired', group: 'postCheck', kind: 'bool', label: 'postCheckRequired', hint: 'hint_postCheckRequired' },
      { field: 'postCheckMaxRounds', group: 'postCheck', kind: 'int', label: 'postCheckMaxRounds' },
      { field: 'postCheckGradleEnabled', group: 'postCheck', kind: 'bool', label: 'postCheckGradleEnabled' },
      { field: 'postCheckGradleCommand', group: 'postCheck', kind: 'text', label: 'postCheckGradleCommand', hint: 'hint_postCheckGradleCommand' },
      { field: 'postCheckGradleTask', group: 'postCheck', kind: 'text', label: 'postCheckGradleTask', hint: 'hint_postCheckGradleTask' },
      { field: 'postCheckGradleArgs', group: 'postCheck', kind: 'text', label: 'postCheckGradleArgs', hint: 'hint_postCheckGradleArgs' },
      { field: 'postCheckTimeoutSeconds', group: 'postCheck', kind: 'int', label: 'postCheckTimeoutSeconds' },
      { field: 'postCheckJavaHome', group: 'postCheck', kind: 'text', label: 'postCheckJavaHome' },
      { field: 'postCheckLintMinSeverity', group: 'postCheck', kind: 'text', label: 'postCheckLintMinSeverity', hint: 'hint_postCheckLintMinSeverity', pattern: /^(error|warning|info)$/ },

      { field: 'configCheckAutoInstall', group: 'configCheck', kind: 'bool', label: 'configCheckAutoInstall', hint: 'hint_configCheckAutoInstall' },
      { field: 'configCheckInstallTimeoutSeconds', group: 'configCheck', kind: 'int', label: 'configCheckInstallTimeoutSeconds' },
    ]

    const zh = {
      nav: '苍穹开发',
      title: '苍穹开发（ok-cosmic）',
      summary: '配置知识库、统一路由、扩展代码库、查询限额与代码校验策略；保存后立即生效。',
      description: '配置知识库、统一路由、扩展代码库、查询限额与代码校验策略；保存后立即生效。',
      unavailable: '该插件当前未加载，暂时无法配置。',
      readOnly: '本部署的设置为只读。',
      save: '保存',
      saving: '保存中…',
      saveFailed: '本部署没有接受这些值，已保留供你修改。',
      overridden: '已覆盖',
      reset: '恢复默认',
      invalidValue: '请检查输入格式；留空表示使用默认值。',
      emptyUsesDefault: '留空使用默认值',

      groupRuntime: '运行环境',
      groupKnowledge: '知识库与在线查询',
      groupRepos: '扩展代码库',
      groupLimits: '缓存与查询限额',
      groupPostCheck: '代码生成后校验',
      groupSkill: 'Skill 行为',
      groupConfigCheck: 'Step 0 配置预检',

      skillName: 'Skill 名称',
      skillDescription: 'Skill 描述',
      skillRank: 'Skill 优先级',
      skillIncludeConfigSummary: '在 Skill 中附带当前配置',
      skillExtraInstructions: '追加给模型的指令',
      hint_skillName: 'kebab-case；修改后下一次加载立即使用新名称。',
      hint_skillDescription: '显示在会话技能目录中的描述。',
      hint_skillRank: '数值越大优先级越高，默认 600。',
      hint_skillIncludeConfigSummary: '把知识库、路由、限额等非敏感配置追加到 Skill 正文，便于模型按当前配置工作。',
      hint_skillExtraInstructions: '追加到 Skill 正文末尾，可写项目特有规则；留空则不追加。',

      pythonPath: 'Python 可执行文件',
      workingDirectory: '工作目录',
      toolTimeoutSeconds: '工具超时（秒）',
      maxOutputBytes: '输出上限（字节）',
      extraEnv: '附加环境变量',
      debug: '调试日志',
      hint_pythonPath: '例如 python、python3，或绝对路径。',
      hint_workingDirectory: '留空使用当前会话工作目录；相对路径按该目录解析。',
      hint_maxOutputBytes: '超出部分截断，并提示调大此值。',
      hint_extraEnv: '每行一个 KEY=VALUE；会注入工具进程。',

      knowledgeDbPath: '知识库数据库路径',
      routeApiUrl: '统一路由 API 地址',
      routeTimeoutSeconds: '路由超时（秒）',
      routeOpenApiSign: 'OpenAPI 签名',
      routeApiToken: '路由访问令牌',
      routeSkipSslVerify: '跳过 SSL 证书校验',
      hint_knowledgeDbPath: 'ok-cosmic-knowledge.db 的路径；支持相对工作目录的路径。',
      hint_routeApiUrl: '形如 https://host/ierp/kapi/v2/.../runtime/route?openApiSign=...；留空则仅离线能力可用。',
      hint_routeOpenApiSign: 'URL 中没有 openApiSign 时自动追加；已有则保持不变。',
      hint_routeApiToken: '可选，作为 Authorization: Bearer <token> 发送。',
      hint_routeSkipSslVerify: '仅建议在自签名开发环境开启。',

      extensionRepos: '扩展代码库路径',
      hint_extensionRepos: '每行一个目录；内置资产均未覆盖时用于搜索参考实现。',

      cacheFormMetadataTtlSeconds: '表单元数据缓存 TTL（秒）',
      cacheBasedataTtlSeconds: '基础资料缓存 TTL（秒）',
      cacheSqliteBusyTimeoutMs: 'SQLite 忙等待（毫秒）',
      limitSearchPageSize: 'API 搜索每页条数',
      limitBatchWorkers: '批量查询并发数',
      limitMaxDisplayRecords: '单次最多展示条数',
      formMetadataDefaultSort: '元数据默认排序',
      formMetadataDefaultView: '元数据默认视图',
      hint_limitMaxDisplayRecords: '超出后只展示前 N 条并提示缩小关键词。',
      hint_formMetadataDefaultSort: 'key | name | type | dbKey。',
      hint_formMetadataDefaultView: 'form | entity | operate | all。',

      postCheckRequired: '生成代码后强制校验',
      postCheckMaxRounds: '修复-复检最多轮数',
      postCheckGradleEnabled: '启用 Gradle 编译校验',
      postCheckGradleCommand: 'Gradle 命令',
      postCheckGradleTask: 'Gradle 任务',
      postCheckGradleArgs: 'Gradle 附加参数',
      postCheckTimeoutSeconds: 'Gradle 超时（秒）',
      postCheckJavaHome: 'JAVA_HOME',
      postCheckLintMinSeverity: '最低报告级别',
      hint_postCheckRequired: '开启后 Skill 会把 cosmic_post_check 标记为生成代码后的必做步骤。',
      hint_postCheckGradleCommand: '留空自动探测 gradlew.bat / gradlew / gradle。',
      hint_postCheckGradleTask: '默认 compileJava；有子模块时自动加 :module: 前缀。',
      hint_postCheckGradleArgs: '空格分隔，例如 --console=plain --offline。',
      hint_postCheckLintMinSeverity: 'error | warning | info。',

      configCheckAutoInstall: '自动安装 Python 依赖',
      configCheckInstallTimeoutSeconds: '依赖安装超时（秒）',
      hint_configCheckAutoInstall: '缺少 tree-sitter 时由 Step 0 预检自动 pip install。',
    }

    const en = {
      nav: 'Cosmic Developer',
      title: 'Cosmic Developer (ok-cosmic)',
      summary: 'Configure the knowledge base, unified route, extension repositories, query budgets and post-check policy. Changes apply on save.',
      description: 'Configure the knowledge base, unified route, extension repositories, query budgets and post-check policy. Changes apply on save.',
      unavailable: 'This plugin is not loaded, so it cannot be configured right now.',
      readOnly: 'This deployment stores settings read-only.',
      save: 'Save',
      saving: 'Saving…',
      saveFailed: 'The deployment did not accept these values; they were left for you to correct.',
      overridden: 'Overridden',
      reset: 'Reset to default',
      invalidValue: 'Check the format; leave blank to use the default.',
      emptyUsesDefault: 'Blank uses the default',

      groupRuntime: 'Runtime',
      groupKnowledge: 'Knowledge base and online queries',
      groupRepos: 'Extension repositories',
      groupLimits: 'Caches and query budgets',
      groupPostCheck: 'Post-generation checks',
      groupSkill: 'Skill behaviour',
      groupConfigCheck: 'Step 0 preflight',

      skillName: 'Skill name',
      skillDescription: 'Skill description',
      skillRank: 'Skill rank',
      skillIncludeConfigSummary: 'Append current settings to the skill',
      skillExtraInstructions: 'Extra model instructions',
      hint_skillName: 'kebab-case; the next catalog refresh uses the new name.',
      hint_skillDescription: 'Description shown in the session skill catalog.',
      hint_skillRank: 'Higher wins; default 600.',
      hint_skillIncludeConfigSummary: 'Append non-secret effective settings to the skill body.',
      hint_skillExtraInstructions: 'Appended to the skill body; leave blank to add nothing.',

      pythonPath: 'Python executable',
      workingDirectory: 'Working directory',
      toolTimeoutSeconds: 'Tool timeout (s)',
      maxOutputBytes: 'Output cap (bytes)',
      extraEnv: 'Extra environment',
      debug: 'Debug logging',
      hint_pythonPath: 'For example python, python3, or an absolute path.',
      hint_workingDirectory: 'Blank uses the current session workspace; relative paths resolve there.',
      hint_maxOutputBytes: 'Output beyond this is truncated with a hint.',
      hint_extraEnv: 'One KEY=VALUE per line; injected into tool processes.',

      knowledgeDbPath: 'Knowledge database path',
      routeApiUrl: 'Unified route API URL',
      routeTimeoutSeconds: 'Route timeout (s)',
      routeOpenApiSign: 'OpenAPI sign',
      routeApiToken: 'Route access token',
      routeSkipSslVerify: 'Skip SSL verification',
      hint_knowledgeDbPath: 'Path to ok-cosmic-knowledge.db; relative paths resolve against the working directory.',
      hint_routeApiUrl: 'For example https://host/ierp/kapi/v2/.../runtime/route?openApiSign=...; blank keeps offline capabilities only.',
      hint_routeOpenApiSign: 'Appended when the URL does not already carry openApiSign.',
      hint_routeApiToken: 'Optional; sent as Authorization: Bearer <token>.',
      hint_routeSkipSslVerify: 'Only for self-signed development servers.',

      extensionRepos: 'Extension repository paths',
      hint_extensionRepos: 'One directory per line; searched for reference implementations when bundled assets miss.',

      cacheFormMetadataTtlSeconds: 'Form metadata cache TTL (s)',
      cacheBasedataTtlSeconds: 'Base data cache TTL (s)',
      cacheSqliteBusyTimeoutMs: 'SQLite busy timeout (ms)',
      limitSearchPageSize: 'API search page size',
      limitBatchWorkers: 'Batch query workers',
      limitMaxDisplayRecords: 'Max rows per result',
      formMetadataDefaultSort: 'Default metadata sort',
      formMetadataDefaultView: 'Default metadata view',
      hint_limitMaxDisplayRecords: 'Rows beyond this are omitted with a hint to narrow the query.',
      hint_formMetadataDefaultSort: 'key | name | type | dbKey.',
      hint_formMetadataDefaultView: 'form | entity | operate | all.',

      postCheckRequired: 'Require post-generation checks',
      postCheckMaxRounds: 'Max fix/recheck rounds',
      postCheckGradleEnabled: 'Enable Gradle compile check',
      postCheckGradleCommand: 'Gradle command',
      postCheckGradleTask: 'Gradle task',
      postCheckGradleArgs: 'Extra Gradle arguments',
      postCheckTimeoutSeconds: 'Gradle timeout (s)',
      postCheckJavaHome: 'JAVA_HOME',
      postCheckLintMinSeverity: 'Minimum lint severity',
      hint_postCheckRequired: 'When on, the skill marks cosmic_post_check as mandatory after code generation.',
      hint_postCheckGradleCommand: 'Blank auto-detects gradlew.bat / gradlew / gradle.',
      hint_postCheckGradleTask: 'Default compileJava; a module prefix is added automatically.',
      hint_postCheckGradleArgs: 'Space-separated, for example --console=plain --offline.',
      hint_postCheckLintMinSeverity: 'error | warning | info.',

      configCheckAutoInstall: 'Auto-install Python dependencies',
      configCheckInstallTimeoutSeconds: 'Dependency install timeout (s)',
      hint_configCheckAutoInstall: 'Step 0 runs pip install when tree-sitter is missing.',
    }

    const S = {
      form: { display: 'flex', flexDirection: 'column', gap: '18px' },
      group: { display: 'flex', flexDirection: 'column', gap: '10px' },
      groupTitle: {
        fontSize: '13px',
        fontWeight: 600,
        color: 'var(--dsw-alias-label-primary)',
        paddingBottom: '4px',
        borderBottom: '1px solid var(--dsw-alias-border-l1)',
      },
      ta: { display: 'flex', flexDirection: 'column', gap: '4px' },
      taHead: { display: 'flex', alignItems: 'center', gap: '8px' },
      taLabel: { fontSize: '12px', color: 'var(--dsw-alias-label-secondary)' },
      badge: {
        fontSize: '11px',
        color: 'var(--dsw-alias-label-tertiary)',
        border: '1px solid var(--dsw-alias-border-l2)',
        borderRadius: '999px',
        padding: '0 6px',
      },
      reset: {
        background: 'none',
        border: 'none',
        color: 'var(--dsw-alias-state-business-primary)',
        cursor: 'pointer',
        fontSize: '11px',
        padding: 0,
      },
      textarea: {
        boxSizing: 'border-box',
        width: '100%',
        minHeight: '72px',
        padding: '6px 10px',
        border: '1px solid var(--dsw-alias-border-l2)',
        borderRadius: '8px',
        background: 'var(--dsw-alias-bg-layer-1)',
        color: 'var(--dsw-alias-label-primary)',
        font: 'inherit',
        fontSize: '13px',
        lineHeight: '20px',
        outline: 'none',
        resize: 'vertical',
      },
      hint: { fontSize: '11px', lineHeight: '16px', color: 'var(--dsw-alias-label-tertiary)' },
      invalid: { fontSize: '11px', lineHeight: '16px', color: 'var(--dsw-alias-state-error-primary)' },
    }

    function integerField(field) {
      return {
        field,
        format: (value) => (value === null || value === undefined ? '' : String(value)),
        parse: (text) => {
          const trimmed = text.trim()
          if (!trimmed) return { kind: 'clear' }
          const numeric = Number(trimmed)
          if (!Number.isFinite(numeric) || !Number.isInteger(numeric)) return undefined
          return { kind: 'set', value: numeric }
        },
      }
    }

    function booleanField(field) {
      return {
        field,
        format: (value) => (value === null || value === undefined ? '' : value ? 'true' : 'false'),
        parse: (text) => {
          const normalized = text.trim().toLowerCase()
          if (!normalized) return { kind: 'clear' }
          if (['true', '1', 'yes', 'on'].includes(normalized)) return { kind: 'set', value: true }
          if (['false', '0', 'no', 'off'].includes(normalized)) return { kind: 'set', value: false }
          return undefined
        },
      }
    }

    function linesField(field) {
      return {
        field,
        format: (value) => (Array.isArray(value) ? value.join('\n') : ''),
        parse: (text) => {
          if (!text.trim()) return { kind: 'clear' }
          return {
            kind: 'set',
            value: text
              .split(/\r?\n/)
              .map((line) => line.trim())
              .filter(Boolean),
          }
        },
      }
    }

    function longTextField(field) {
      return {
        field,
        format: (value) => (value === null || value === undefined ? '' : String(value)),
        parse: (text) => (text.trim() ? { kind: 'set', value: text } : { kind: 'clear' }),
      }
    }

    function patternField(field, pattern) {
      return {
        field,
        format: (value) => (value === null || value === undefined ? '' : String(value)),
        parse: (text) => {
          const trimmed = text.trim()
          if (!trimmed) return { kind: 'clear' }
          if (!pattern.test(trimmed)) return undefined
          return { kind: 'set', value: trimmed }
        },
      }
    }

    function specFor(descriptor) {
      if (descriptor.pattern) return patternField(descriptor.field, descriptor.pattern)
      switch (descriptor.kind) {
        case 'int':
          return integerField(descriptor.field)
        case 'bool':
          return booleanField(descriptor.field)
        case 'lines':
          return linesField(descriptor.field)
        case 'longtext':
          return longTextField(descriptor.field)
        default:
          return P.settingsTextField(descriptor.field)
      }
    }

    class Controller {
      constructor(scope) {
        this.form = new P.SettingsFormModel(scope, FIELDS.map(specFor))
        this.store = this.form.bind(() => this.projection())
      }

      projection() {
        const state = { ...this.form.shell() }
        for (const descriptor of FIELDS) state[descriptor.field] = this.form.field(descriptor.field)
        return state
      }

      inject() {
        return { hooks: { okCosmic: this.store }, ...this.form.actions() }
      }

      dispose() {
        this.form.dispose()
      }
    }

    function TextAreaField(props) {
      const state = props.state || { text: '', overridden: false, invalid: false }
      const head = [
        jsx('label', { key: 'label', htmlFor: props.id, style: S.taLabel, children: props.label }),
      ]
      if (state.overridden) head.push(jsx('span', { key: 'badge', style: S.badge, children: props.overriddenLabel }))
      if (state.overridden) {
        head.push(
          jsx('button', {
            key: 'reset',
            type: 'button',
            style: S.reset,
            disabled: props.disabled,
            onClick: props.onReset,
            children: props.resetLabel,
          }),
        )
      }
      return jsxs('div', {
        style: S.ta,
        children: [
          jsxs('div', { key: 'head', style: S.taHead, children: head }),
          jsx('textarea', {
            key: 'input',
            id: props.id,
            style: S.textarea,
            value: state.text,
            disabled: props.disabled,
            placeholder: props.placeholder,
            rows: props.rows || 4,
            spellCheck: false,
            onChange: (event) => props.onEdit(event.target.value),
          }),
          jsx('div', {
            key: 'hint',
            style: state.invalid ? S.invalid : S.hint,
            children: state.invalid ? props.invalidLabel : props.hint,
          }),
        ],
      })
    }

    function Card(props) {
      const t = props.t
      const state = props.useOkCosmic((snapshot) => snapshot)
      if (props.view === 'summary') return t('summary')
      const labels = {
        unavailable: t('unavailable'),
        readOnly: t('readOnly'),
        saveFailed: t('saveFailed'),
        save: t('save'),
        saving: t('saving'),
      }
      const groups = GROUPS.map((group) => {
        const children = [
          jsx('div', { key: 'title', style: S.groupTitle, children: t(group.title) }),
        ]
        for (const descriptor of FIELDS) {
          if (descriptor.group !== group.id) continue
          const fieldState = state[descriptor.field] || { text: '', overridden: false, invalid: false }
          const common = {
            key: descriptor.field,
            id: `ok-cosmic-${descriptor.field}`,
            label: t(descriptor.label),
            hint: descriptor.hint ? t(descriptor.hint) : '',
            text: fieldState.text,
            overridden: fieldState.overridden,
            invalid: fieldState.invalid,
            overriddenLabel: t('overridden'),
            resetLabel: t('reset'),
            invalidLabel: t('invalidValue'),
            placeholder: t('emptyUsesDefault'),
            disabled: !state.writable,
            onEdit: (text) => props.edit(descriptor.field, text),
            onReset: () => props.resetField(descriptor.field),
          }
          if (descriptor.kind === 'lines' || descriptor.kind === 'longtext') {
            children.push(
              jsx(TextAreaField, {
                ...common,
                state: fieldState,
                rows: descriptor.kind === 'longtext' ? 5 : 3,
              }),
            )
          } else {
            children.push(jsx(P.SettingsValueField, { ...common, numeric: descriptor.kind === 'int' }))
          }
        }
        return jsxs('div', { key: group.id, style: S.group, children })
      })

      return jsx(P.SettingsForm, {
        labels,
        state,
        onSave: props.save,
        onDiscard: props.discard,
        children: jsx('div', { style: S.form, children: groups }),
      })
    }

    const inject = ['slots', 'locale', 'configForms']

    function apply(ctx) {
      const t = ctx.locale.bind(NS)
      ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'ok-cosmic: dictionaries')

      const controller = new Controller(ctx.configForms.get(ENTRY))
      ctx.effect(() => () => controller.dispose(), 'ok-cosmic: form subscription')

      ctx.effect(
        () =>
          ctx.configForms.whileServed([ENTRY], () => {
            const section = ctx.slots.inject('settings.section', () =>
              ctx.slots.register(
                {
                  name: 'settings.section',
                  id: 'ok-cosmic',
                  order: 40,
                  label: () => t('nav'),
                  locale: NS,
                  inject: () => controller.inject(),
                },
                Card,
              ),
            )
            const bundle = ctx.slots.inject('plugins.bundle.config', () =>
              ctx.slots.register(
                {
                  name: 'plugins.bundle.config',
                  key: 'dsh-plugin-kdcosmicskill',
                  locale: NS,
                  inject: () => controller.inject(),
                },
                Card,
              ),
            )
            return () => {
              if (typeof section === 'function') section()
              if (typeof bundle === 'function') bundle()
            }
          }),
        'ok-cosmic: settings pages',
      )
    }

    return { name: 'dsh-plugin-kdcosmicskill-client', inject, apply }
  },
})
