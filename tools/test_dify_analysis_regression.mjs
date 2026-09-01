import fs from 'node:fs'
import { spawnSync } from 'node:child_process'

const workflowSource = fs.readFileSync(new URL('./upgrade_dify_enterprise_analysis.js', import.meta.url), 'utf8')

const extract = (name, nextName) => {
  const startMarker = 'const ' + name + ' = String.raw' + '`'
  const endMarker = '`' + '\n\n  const ' + nextName
  const start = workflowSource.indexOf(startMarker)
  const end = start < 0 ? -1 : workflowSource.indexOf(endMarker, start + startMarker.length)
  if (start < 0 || end < 0) throw new Error(`cannot extract ${name}`)
  return workflowSource.slice(start + startMarker.length, end)
}

const planCode = extract('planCode', 'resolveCode')
const resolveCode = extract('resolveCode', 'auditCode')
const auditCode = extract('auditCode', 'intentSystem')

const encode = (value) => Buffer.from(value, 'utf8').toString('base64')

const runner = String.raw`
import base64
import json
import sys

payload = json.load(sys.stdin)

def load(name):
    return base64.b64decode(payload[name]).decode('utf-8')

plan_ns = {}
resolve_ns = {}
audit_ns = {}
exec(load('plan'), plan_ns)
exec(load('resolve'), resolve_ns)
exec(load('audit'), audit_ns)

def plan(intent, question, previous=None):
    return plan_ns['main'](json.dumps(intent, ensure_ascii=False), question, json.dumps(previous or {}, ensure_ascii=False), '')

def audit(kind, rows, comparison='none', indicators=None):
    indicators = indicators or [{'code': 'M1', 'name': '指标一', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}]
    p = {
        'analysis_type': kind,
        'indicator_inputs': [x.get('name', '') for x in indicators],
        'indicator': indicators[0],
        'related_indicators': indicators[1:],
        'time': {'start': '2026-01-01', 'end': '2026-07-01', 'granularity': 'month'},
        'dimensions': [{'name': 'time', 'field': 'period', 'level': 'month'}],
        'organization_scope': {'type': 'company', 'inputs': [], 'codes': [], 'names': []},
        'filters': [], 'comparison': {'type': comparison, 'baseline_start': '2025-01-01', 'baseline_end': '2025-07-01'},
        'allowed_tables': ['CGXTAPPMISDate_2026_06', 'CGXTAPPMISDate_2026_12'],
        'top_n': 5, 'ranking_mode': 'top', 'sort': {'field': 'value', 'direction': 'desc'},
        'analysis_actions': [kind.lower()], 'time_series_requested': kind in ('TREND', 'CORRELATION', 'DRILLDOWN', 'EXECUTIVE_OVERVIEW'),
    }
    result = audit_ns['main'](json.dumps(p, ensure_ascii=False), 'READY', rows, '')
    protocol = json.loads(result['result_text'])
    return {'status': result['status'], 'messageType': protocol.get('messageType'), 'analysisType': protocol.get('analysisType'), 'table': bool(protocol.get('content', {}).get('table')), 'chart': bool(protocol.get('content', {}).get('chart')), 'auditKeys': sorted(json.loads(result['audit_json']).keys())}

def row(period, value, org='A', metric='M1', period_set='current'):
    return {'period_set': period_set, 'period': period, 'indicator_code': metric, 'indicator_name': '指标一' if metric == 'M1' else '指标二', 'org_code': org, 'org_name': org, 'value': value, 'row_count': 1, 'dimension_count': 1, 'invalid_value_count': 0, 'duplicate_key_groups': 0, 'data_cutoff_date': '2026-06-30'}

checks = []
def check(name, condition, detail):
    if not condition:
        raise AssertionError(name + ': ' + detail)
    checks.append(name)

trend = plan({}, '查询近12个月组织10004024的全厂发电量趋势')
trend_plan = json.loads(trend['analysis_plan'])
check('trend-plan', trend_plan['analysis_type'] == 'TREND' and trend_plan['time_series_requested'], json.dumps(trend_plan, ensure_ascii=False))

fact_fallback = plan({}, '查询2024年组织10004024全厂发电量')
fact_fallback_plan = json.loads(fact_fallback['analysis_plan'])
check('fact-indicator-fallback', fact_fallback_plan['indicator_inputs'] == ['全厂发电量'], json.dumps(fact_fallback_plan, ensure_ascii=False))

ranking = plan({}, '今年项目公司发电量排名前5')
ranking_plan = json.loads(ranking['analysis_plan'])
check('ranking-plan', ranking_plan['analysis_type'] == 'RANKING' and ranking_plan['top_n'] == 5 and ranking_plan['organization_scope']['type'] == 'project_company', json.dumps(ranking_plan, ensure_ascii=False))
check('ranking-indicator-fallback', ranking_plan['indicator_inputs'] == ['发电量'], json.dumps(ranking_plan, ensure_ascii=False))
ranking_followup = plan({}, '只看后5名', ranking_plan)
ranking_followup_plan = json.loads(ranking_followup['analysis_plan'])
check('conversation-slot-fill', ranking_followup_plan['analysis_type'] == 'RANKING' and ranking_followup_plan['ranking_mode'] == 'bottom' and ranking_followup_plan['top_n'] == 5 and ranking_followup_plan['time']['start'] == ranking_plan['time']['start'], json.dumps(ranking_followup_plan, ensure_ascii=False))

comparison = plan({}, '比较组织10004024和组织10004011近6个月的全厂发电量')
comparison_plan = json.loads(comparison['analysis_plan'])
check('comparison-plan', comparison_plan['analysis_type'] == 'COMPARISON' and len(comparison_plan['organization_scope']['inputs']) == 2, json.dumps(comparison_plan, ensure_ascii=False))

correlation = plan({'analysis_type': 'CORRELATION', 'indicator_inputs': ['生活垃圾入厂量', '全厂发电量'], 'organization_inputs': ['10004024']}, '比较组织10004024近6个月生活垃圾入厂量和全厂发电量走势')
correlation_plan = json.loads(correlation['analysis_plan'])
check('correlation-plan', correlation_plan['analysis_type'] == 'CORRELATION' and correlation_plan['time_series_requested'], json.dumps(correlation_plan, ensure_ascii=False))

ranking_comparison = plan({}, '今年各项目公司发电量同比去年排名前5')
ranking_comparison_plan = json.loads(ranking_comparison['analysis_plan'])
check('ranking-comparison-plan', ranking_comparison_plan['analysis_type'] == 'RANKING_COMPARISON' and ranking_comparison_plan['organization_scope']['type'] == 'project_company' and ranking_comparison_plan['time']['start'].startswith('2026-01-01'), json.dumps(ranking_comparison_plan, ensure_ascii=False))

resolved = resolve_ns['main'](
    json.dumps(correlation_plan, ensure_ascii=False),
    [{'code': 'M2', 'name': '全厂发电量', 'unit': '', 'old_name': '', 'object_code': 'M2', 'object_name': '电量'}, {'code': 'M1', 'name': '生活垃圾入厂量', 'unit': '', 'old_name': '', 'object_code': 'M1', 'object_name': '垃圾'}],
    '',
    [{'code': '10004024', 'name': '甲公司', 'abbreviation': '甲', 'full_path': '甲公司', 'layer': '5', 'parent_code': '', 'is_detail_company': '1', 'tree_is_detail': '1', 'enabled': '1'}],
    '',
)
resolved_plan = json.loads(resolved['analysis_plan_json'])
check('resolve-multi-indicator', resolved['status'] == 'READY' and resolved['can_execute'] == 1 and resolved_plan['indicator']['code'] == 'M1' and len(resolved_plan['related_indicators']) == 1, json.dumps(resolved, ensure_ascii=False))
check(
  'resolve-safe-sql',
  'WITH scoped_orgs AS' in resolved['query_sql']
    and 't."newIndicator" IN' in resolved['query_sql']
    and 'AND (t."ZBRQ" >=' in resolved['query_sql']
    and 'COALESCE(raw.metric_code' in resolved['query_sql'],
  resolved['query_sql'][:1200],
)

comparison_plan = json.loads(plan({}, '查询今年发电量同比去年')['analysis_plan'])
comparison_resolved = resolve_ns['main'](
  json.dumps(comparison_plan, ensure_ascii=False),
  [{'code': 'M1', 'name': '全厂发电量', 'unit': '万千瓦时', 'object_code': 'plant', 'object_name': '厂级', 'old_name': '发电量'}],
  '',
  [{'code': '10004024', 'name': '示例项目公司', 'abbreviation': '', 'full_path': '示例项目公司', 'layer': '4', 'parent_code': '', 'is_detail_company': '1', 'tree_is_detail': '1', 'enabled': '1'}],
  '',
)
check(
  'resolve-baseline-safe-sql',
  ' OR t."ZBRQ"' in comparison_resolved['query_sql']
    and 't."newIndicator" IN' in comparison_resolved['query_sql']
    and comparison_resolved['query_sql'].index('t."newIndicator" IN') < comparison_resolved['query_sql'].index(' OR t."ZBRQ"'),
  comparison_resolved['query_sql'][:1600],
)

anomaly = plan({}, '哪些公司连续3个月发电量下降')
anomaly_plan = json.loads(anomaly['analysis_plan'])
check('anomaly-plan', anomaly_plan['analysis_type'] == 'ANOMALY' and anomaly_plan['time']['start'].endswith('-01'), json.dumps(anomaly_plan, ensure_ascii=False))
check('anomaly-focus', anomaly_plan['anomaly_focus'] == 'CONSECUTIVE_DECREASE', json.dumps(anomaly_plan, ensure_ascii=False))
anomaly_model_override = plan({'analysis_type': 'TREND', 'indicator_inputs': ['发电量'], 'organization_inputs': ['哪些公司']}, '哪些公司连续3个月发电量下降')
anomaly_model_override_plan = json.loads(anomaly_model_override['analysis_plan'])
check('anomaly-cue-overrides-model', anomaly_model_override_plan['analysis_type'] == 'ANOMALY' and anomaly_model_override_plan['organization_scope']['type'] == 'project_company', json.dumps(anomaly_model_override_plan, ensure_ascii=False))

overview = plan({}, '看看最近生产经营有什么值得关注的问题')
overview_plan = json.loads(overview['analysis_plan'])
check('overview-plan', overview_plan['analysis_type'] == 'EXECUTIVE_OVERVIEW' and overview_plan['time']['start'] != overview_plan['time']['end'], json.dumps(overview_plan, ensure_ascii=False))

trend_rows = [row('2026-01', 100), row('2026-02', 90), row('2026-03', 70), row('2026-04', 60)]
check('trend-audit', audit('TREND', trend_rows)['status'] == 'SUCCESS_WITH_DATA' and audit('TREND', trend_rows)['chart'], 'trend audit did not create chart')

rank_rows = [row('total', 100, 'A'), row('total', 80, 'B'), row('total', 60, 'C')]
check('ranking-audit', audit('RANKING', rank_rows)['table'] and audit('RANKING', rank_rows)['chart'], 'ranking audit missing table/chart')

comparison_rows = [row('total', 120, 'A', period_set='current'), row('total', 100, 'A', period_set='baseline'), row('total', 80, 'B', period_set='current'), row('total', 100, 'B', period_set='baseline')]
check('comparison-audit', audit('COMPARISON', comparison_rows, 'YOY')['table'], 'comparison audit missing table')
check('ranking-comparison-audit', audit('RANKING_COMPARISON', comparison_rows, 'YOY')['table'], 'ranking comparison audit missing table')

distribution = audit('DISTRIBUTION', [row('total', 10, str(i)) for i in range(1, 9)])
check('distribution-audit', distribution['table'] and distribution['chart'], 'distribution audit missing output')

anomaly_audit = audit('ANOMALY', trend_rows)
check('anomaly-audit', anomaly_audit['table'] and anomaly_audit['auditKeys'].__contains__('anomalies'), 'anomaly audit missing evidence')

multi_rows = []
for period, left, right in [('2026-01', 1, 2), ('2026-02', 2, 4), ('2026-03', 3, 6), ('2026-04', 4, 8)]:
    multi_rows.extend([row(period, left, 'A', 'M1'), row(period, right, 'A', 'M2')])
correlation_audit = audit('CORRELATION', multi_rows, indicators=[{'code': 'M1', 'name': '指标一', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}, {'code': 'M2', 'name': '指标二', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}])
check('correlation-audit', correlation_audit['table'], 'correlation audit missing table')
check('drilldown-audit', audit('DRILLDOWN', multi_rows, indicators=[{'code': 'M1', 'name': '指标一', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}, {'code': 'M2', 'name': '指标二', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}])['table'], 'drilldown audit missing table')
check('overview-audit', audit('EXECUTIVE_OVERVIEW', multi_rows, indicators=[{'code': 'M1', 'name': '指标一', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}, {'code': 'M2', 'name': '指标二', 'unit': '', 'level': 'plant_total', 'aggregation': 'SUM'}])['table'], 'overview audit missing table')

print(json.dumps({'ok': True, 'checks': checks}, ensure_ascii=False))
`

const result = spawnSync('python', ['-c', runner], {
  cwd: new URL('..', import.meta.url),
  encoding: 'utf8',
  input: JSON.stringify({ plan: encode(planCode), resolve: encode(resolveCode), audit: encode(auditCode) }),
  maxBuffer: 8 * 1024 * 1024,
})

if (result.error) throw result.error
if (result.status !== 0) {
  process.stderr.write(result.stderr || result.stdout)
  process.exit(result.status || 1)
}

process.stdout.write(result.stdout)
