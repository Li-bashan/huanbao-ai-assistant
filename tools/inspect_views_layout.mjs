import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'

const BASE_URL = process.argv[2] || process.env.AUDIT_BASE_URL || 'http://127.0.0.1:5174/'
const RUN_LABEL = process.env.AUDIT_RUN_LABEL || 'audit'
const ONLY_VIEW = process.env.AUDIT_ONLY_VIEW || ''
const PAUSE_MS = Number(process.env.AUDIT_PAUSE_MS || 0)
const CHROME_PATH = process.env.AUDIT_CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const SCREENSHOT_DIR = path.resolve('tools/screenshots')
const REPORT_PATH = path.resolve(`tools/layout-audit-report-${RUN_LABEL}.json`)

const VIEWPORTS = [
  { name: 'compact-400', width: 400, height: 900, windowView: 'compact' },
  { name: 'full-1280', width: 1280, height: 900, windowView: 'full' },
]

const VIEW_TYPES = [
  'FACT',
  'TREND',
  'RANKING',
  'COMPARISON',
  'ANOMALY',
  'DRILLDOWN',
  'OVERVIEW',
  'DISTRIBUTION',
  'RANKING_COMPARISON',
]

const makeRows = (view) => Array.from({ length: 12 }, (_, index) => ({
  organization: `项目公司${index + 1}`,
  period: `2026-${String(index + 1).padStart(2, '0')}`,
  value: 1000 - index * 37,
  rank: index + 1,
  previousRank: index + 2,
  rankChange: 1,
  view,
}))

const makePayload = (analysisType) => ({
  protocolVersion: '2.0',
  requestId: `layout-audit-${analysisType.toLowerCase()}`,
  conversationId: 'layout-audit-conversation',
  status: 'SUCCESS_WITH_DATA',
  messageType: 'analysis',
  analysisType,
  content: {
    title: `${analysisType} 自动化巡检样本`,
    summary: '用于核验视图层级、KPI 栅格、洞察排版和明细折叠状态的标准结构化结果。',
    metrics: [
      { id: 'metric-total', label: '近半年累计', value: 8234, unit: '万度' },
      { id: 'metric-latest', label: '最新月（2026-08）', value: 0.1417, unit: '万度' },
      { id: 'metric-yoy', label: '8月同比变动', value: 0.11, unit: '%' },
      { id: 'metric-mom', label: '8月环比变动', value: 13.58, unit: '%' },
    ],
    table: {
      columns: [
        { key: 'organization', label: '项目公司', type: 'text' },
        { key: 'period', label: '统计期间', type: 'text' },
        { key: 'value', label: '指标值', type: 'number', unit: '吨' },
        { key: 'rank', label: '排名', type: 'number' },
      ],
      rows: makeRows(analysisType),
      total: 12,
      defaultVisibleRows: 10,
    },
    chart: analysisType === 'FACT' ? null : {
      type: analysisType === 'RANKING' ? 'bar' : 'line',
      title: `${analysisType} 核心图表`,
      xField: 'period',
      yField: 'value',
      categories: ['1月', '2月', '3月', '4月', '5月', '6月'],
      series: [{
        name: '指标值',
        type: analysisType === 'RANKING' ? 'bar' : 'line',
        data: [980, 1040, 1010, 1100, 1140, 1230],
      }],
    },
    insights: [
      { type: 'fact', text: '本期指标值较上期保持稳定增长。' },
      { type: 'attention', text: '7月发电量环比下降 21.98%，同期生活垃圾入厂量下降 18.45%。' },
    ],
    evidence: ['数据来自已授权生产指标口径。'],
    dataInfo: {
      analysisType,
      indicatorName: '生活垃圾入厂量',
      indicatorCode: 'IND-001',
      unit: '吨',
      timeRange: { expression: '今年', label: '今年' },
      dataCutoffDate: '2026-08-31',
      aggregation: '月度汇总',
      organizationScope: '已授权项目公司',
      rowCount: 12,
      validation: '通过',
    },
    followUps: [
      { id: 'trend', label: '查看趋势', query: '查看生活垃圾入厂量趋势' },
      { id: 'yoy', label: '和去年同期相比', query: '和去年同期相比' },
      { id: 'ranking', label: '查看项目公司排名', query: '查看项目公司排名' },
      { id: 'distribution', label: '查看分布', query: '查看生活垃圾入厂量分布' },
      { id: 'drilldown', label: '下钻诊断原因', query: '下钻诊断原因' },
    ],
  },
  clarification: null,
  meta: { source: '生产指标库', durationMs: 1200 },
  protocolValid: true,
})

const invalidPayload = {
  protocolVersion: '1.9',
  requestId: 'layout-audit-invalid',
  conversationId: 'layout-audit-conversation',
  status: 'ERROR',
  messageType: 'error',
  analysisType: '',
  content: {
    title: '',
    summary: '结果协议校验失败，请稍后重试。',
    metrics: [],
    table: null,
    chart: null,
    insights: [],
    evidence: [],
    dataInfo: {},
    followUps: [],
  },
  clarification: null,
  meta: {},
  protocolValid: false,
}

const mountPayload = async (page, payload, windowView) => {
  await page.addScriptTag({
    content: `
      window.__HUANBAO_LAYOUT_AUDIT_READY__ = false;
      window.__HUANBAO_LAYOUT_AUDIT_ERROR__ = '';
      (async () => {
        try {
          const [{ createApp, nextTick }, { default: DataQueryResult }] = await Promise.all([
            import('/node_modules/.vite/deps/vue.js'),
            import('/src/components/DataQueryResult.vue'),
          ]);
          document.body.innerHTML = '<main id="layout-audit-root"></main>';
          const app = createApp(DataQueryResult, ${JSON.stringify({ data: payload, windowView })});
          app.mount(document.querySelector('#layout-audit-root'));
          await nextTick();
          window.__HUANBAO_LAYOUT_AUDIT_APP__ = app;
          window.__HUANBAO_LAYOUT_AUDIT_READY__ = true;
        } catch (error) {
          window.__HUANBAO_LAYOUT_AUDIT_ERROR__ = String(error?.stack || error);
        }
      })();
    `,
  })
  await page.waitForFunction(() => window.__HUANBAO_LAYOUT_AUDIT_READY__ || window.__HUANBAO_LAYOUT_AUDIT_ERROR__, null, { timeout: 15000 })
  const error = await page.evaluate(() => window.__HUANBAO_LAYOUT_AUDIT_ERROR__)
  if (error) throw new Error(`无法挂载问数视图：${error}`)
  await page.waitForTimeout(350)
}

const inspectPage = async (page, analysisType, viewport) => page.evaluate(({ analysisType, viewport }) => {
  const readBox = (element) => {
    if (!element) return null
    const rect = element.getBoundingClientRect()
    const style = getComputedStyle(element)
    return {
      top: Math.round(rect.top * 100) / 100,
      bottom: Math.round(rect.bottom * 100) / 100,
      height: Math.round(rect.height * 100) / 100,
      display: style.display,
      visibility: style.visibility,
    }
  }

  const root = document.querySelector('.data-query-analysis-card')
  const chart = root?.querySelector('.data-query-chart-card, .data-query-chart')
  const table = root?.querySelector('.data-query-structured-table-card')
  const metricGrid = root?.querySelector('.data-query-metric-grid')
  const metricCards = metricGrid ? [...metricGrid.querySelectorAll('.data-query-metric')] : []
  const factPrimary = root?.querySelector('.data-query-fact-primary')
  const insights = root?.querySelector('.data-query-insight-section, .data-query-insight-list')
  const insightList = root?.querySelector('.data-query-insight-list')
  const followUps = root?.querySelector('.data-query-followups')
  const tablePanel = table?.querySelector('[data-testid="analysis-table-panel"]')
  const tableToggle = table?.querySelector('[data-testid="analysis-table-toggle"]')
  const tableControls = table?.querySelector('.data-query-table-controls')
  const tableToolbar = table?.querySelector('.data-query-ranking-toolbar')
  const tableElement = table?.querySelector('table')
  const metricStyle = metricGrid ? getComputedStyle(metricGrid) : null
  const tableStyle = tablePanel ? getComputedStyle(tablePanel) : null
  const followUpLabels = followUps ? [...followUps.querySelectorAll('button')].map((button) => button.textContent.trim()) : []
  const subtitle = root?.querySelector('.data-query-insight-subtitle')
  const tableCollapsed = Boolean(
    table &&
    table.dataset.collapsed === 'true' &&
    tableToggle?.getAttribute('aria-expanded') === 'false' &&
    tablePanel &&
    (tableStyle.display === 'none' || tablePanel.getBoundingClientRect().height === 0),
  )

  return {
    analysisType,
    viewport,
    root: readBox(root),
    chart: readBox(chart),
    table: readBox(table),
    insightList: readBox(insights),
    order: {
      chartTop: chart ? Math.round(chart.getBoundingClientRect().top * 100) / 100 : null,
      tableTop: table ? Math.round(table.getBoundingClientRect().top * 100) / 100 : null,
      chartBeforeTable: analysisType === 'FACT'
        ? !chart
        : Boolean(chart && table && chart.getBoundingClientRect().top < table.getBoundingClientRect().top),
    },
    tableState: {
      exists: Boolean(table),
      collapsed: tableCollapsed,
      cardAttribute: table?.dataset.collapsed || null,
      toggleLabel: tableToggle?.textContent.trim() || null,
      toggleExpanded: tableToggle?.getAttribute('aria-expanded') || null,
      panel: readBox(tablePanel),
      table: readBox(tableElement),
      bodyDisplay: tableElement ? getComputedStyle(tableElement.querySelector('tbody'))?.display : null,
      controlsSameRow: Boolean(tableControls && tableToggle && tableToolbar && tableControls.contains(tableToggle) && tableControls.contains(tableToolbar)),
    },
    resultMeta: {
      exists: Boolean(root?.parentElement?.querySelector('.data-query-result-meta-bar')),
      text: root?.parentElement?.querySelector('.data-query-result-meta-bar')?.textContent.trim() || null,
    },
    metrics: {
      expected: Number(document.querySelector('[data-audit-expected-metrics]')?.dataset.auditExpectedMetrics || 0),
      actual: metricCards.length + (factPrimary ? 1 : 0),
      display: metricStyle?.display || null,
      gridTemplateColumns: metricStyle?.gridTemplateColumns || null,
      isGridOrFlex: analysisType === 'FACT'
        ? Boolean(factPrimary || (metricStyle && ['grid', 'flex'].includes(metricStyle.display)))
        : metricStyle ? ['grid', 'flex'].includes(metricStyle.display) : false,
      isParallel: analysisType === 'FACT'
        ? true
        : metricCards.length <= 1 || (metricStyle && (metricStyle.display === 'flex' || metricStyle.gridTemplateColumns.split(' ').length > 1)),
    },
    insights: {
      hasSubtitle: Boolean(subtitle?.textContent.trim()),
      subtitle: subtitle?.textContent.trim() || null,
      hasBulletList: Boolean(insightList && ['UL', 'OL'].includes(insightList.tagName) && insightList.querySelectorAll(':scope > li').length),
    },
    followUps: {
      labels: followUpLabels,
      hasUnsupportedIntent: followUpLabels.some((label) => /(分布|下钻|诊断|归因|相关性|异常)/.test(label)),
    },
    fallback: {
      text: document.querySelector('.data-query-analysis-fallback')?.textContent.trim() || null,
      hasDeveloperTerms: /(协议校验失败|PROTOCOL_VALIDATION_FAILED|protocol validation failed)/i.test(document.body.textContent),
    },
  }
}, { analysisType, viewport: viewport.name })

const toResult = (probe) => {
  if (probe.analysisType === 'PROTOCOL_FALLBACK') {
    const checks = { fallbackLanguage: !probe.fallback.hasDeveloperTerms }
    return { ...probe, checks, passed: checks.fallbackLanguage }
  }

  const checks = {
    chartBeforeTable: probe.order.chartBeforeTable,
    tableCollapsed: probe.tableState.exists ? probe.tableState.collapsed : true,
    tableControls: probe.tableState.exists ? probe.tableState.controlsSameRow : true,
    metricCount: probe.metrics.actual === probe.metrics.expected,
    metricLayout: probe.metrics.isGridOrFlex && probe.metrics.isParallel,
    resultMeta: probe.resultMeta.exists,
    insightSubtitle: probe.insights.hasSubtitle,
    insightBullets: probe.insights.hasBulletList,
    followUpFilter: !probe.followUps.hasUnsupportedIntent,
    fallbackLanguage: !probe.fallback.hasDeveloperTerms,
  }
  return { ...probe, checks, passed: Object.values(checks).every(Boolean) }
}

const printMatrix = (results) => {
  const columns = ['analysisType', 'viewport', 'chartBeforeTable', 'tableCollapsed', 'tableControls', 'metricCount', 'metricLayout', 'resultMeta', 'insightSubtitle', 'insightBullets', 'followUpFilter', 'fallbackLanguage', 'passed']
  console.table(results.map((result) => columns.reduce((row, column) => {
    row[column] = column in result ? result[column] : result.checks[column]
    return row
  }, {})))
}

await fs.mkdir(SCREENSHOT_DIR, { recursive: true })

const browser = await chromium.launch({
  headless: process.env.AUDIT_HEADED !== '1',
  executablePath: CHROME_PATH,
  args: ['--disable-gpu'],
})

const results = []
try {
  for (const viewport of VIEWPORTS) {
    const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } })
    page.on('pageerror', (error) => console.warn(`[pageerror] ${error.message}`))
    await page.goto(`${BASE_URL}?layout-audit=${RUN_LABEL}`, { waitUntil: 'domcontentloaded' })

    const views = ONLY_VIEW ? VIEW_TYPES.filter((view) => view === ONLY_VIEW) : VIEW_TYPES
    for (const analysisType of views) {
      await mountPayload(page, makePayload(analysisType), viewport.windowView)
      await page.evaluate((expectedMetrics) => {
        const root = document.querySelector('#layout-audit-root')
        if (root) root.dataset.auditExpectedMetrics = String(expectedMetrics)
        const metricMarker = document.createElement('span')
        metricMarker.hidden = true
        metricMarker.dataset.auditExpectedMetrics = String(expectedMetrics)
        metricMarker.setAttribute('data-audit-expected-metrics', '')
        root?.append(metricMarker)
      }, makePayload(analysisType).content.metrics.length)
      const screenshotPath = path.join(SCREENSHOT_DIR, `${RUN_LABEL}-${viewport.name}-${analysisType.toLowerCase()}.png`)
      await page.screenshot({ path: screenshotPath, fullPage: true })
      const probe = await inspectPage(page, analysisType, viewport)
      results.push(toResult(probe))
      if (PAUSE_MS > 0) await page.waitForTimeout(PAUSE_MS)
    }

    if (!ONLY_VIEW) {
      await mountPayload(page, invalidPayload, viewport.windowView)
      const fallbackProbe = await inspectPage(page, 'PROTOCOL_FALLBACK', viewport)
      results.push(toResult(fallbackProbe))
      await page.screenshot({
        path: path.join(SCREENSHOT_DIR, `${RUN_LABEL}-${viewport.name}-protocol-fallback.png`),
        fullPage: true,
      })
    }
    await page.close()
  }
} finally {
  await browser.close()
}

await fs.writeFile(REPORT_PATH, JSON.stringify({
  runLabel: RUN_LABEL,
  baseUrl: BASE_URL,
  chromePath: CHROME_PATH,
  viewports: VIEWPORTS,
  results,
}, null, 2), 'utf8')

printMatrix(results)
const failed = results.filter((result) => !result.passed)
console.log(`Layout audit ${RUN_LABEL}: ${results.length - failed.length}/${results.length} cases passed`)
console.log(`Structured report: ${REPORT_PATH}`)
console.log(`Screenshots: ${SCREENSHOT_DIR}`)
if (failed.length) {
  console.log('Violations:')
  console.table(failed.map((result) => ({
    analysisType: result.analysisType,
    viewport: result.viewport,
    failedChecks: Object.entries(result.checks).filter(([, passed]) => !passed).map(([name]) => name).join(', '),
  })))
  process.exitCode = 1
}
