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
from datetime import date

payload = json.load(sys.stdin)

def load(name):
    return base64.b64decode(payload[name]).decode('utf-8')

plan_ns = {}
resolve_ns = {}
audit_ns = {}
exec(load('plan'), plan_ns)
exec(load('resolve'), resolve_ns)
exec(load('audit'), audit_ns)

# Synthetic resolver fixtures must be registered explicitly. This keeps the
# regression deterministic without weakening the production unknown-metric
# aggregation gate.
resolve_ns['SEMANTIC_REGISTRY'].update({
    'M1': {'level': 'plant_total', 'aggregation': 'SUM', 'aggregation_source': 'test_registry', 'comparison_supported': True, 'ranking_supported': True},
    'M2': {'level': 'plant_total', 'aggregation': 'SUM', 'aggregation_source': 'test_registry', 'comparison_supported': True, 'ranking_supported': True},
})

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

half_year_plan = json.loads(plan({}, '查询秦皇岛公司近半年的全厂发电量')['analysis_plan'])
half_year_start = date.fromisoformat(half_year_plan['time']['start'])
half_year_end = date.fromisoformat(half_year_plan['time']['end'])
half_year_span = (half_year_end.year - half_year_start.year) * 12 + half_year_end.month - half_year_start.month
check('half-year-window', half_year_plan['time']['granularity'] == 'month' and half_year_span == 6, json.dumps(half_year_plan, ensure_ascii=False))

ranking_followup_plan = json.loads(plan({}, '查看项目公司排名', half_year_plan)['analysis_plan'])
check(
  'group-ranking-clears-company-scope',
  ranking_followup_plan['analysis_type'] == 'RANKING'
    and ranking_followup_plan['organization_scope']['type'] == 'project_company'
    and ranking_followup_plan['organization_scope']['inputs'] == []
    and ranking_followup_plan['time']['start'] == half_year_plan['time']['start']
    and ranking_followup_plan['time']['end'] == half_year_plan['time']['end'],
  json.dumps(ranking_followup_plan, ensure_ascii=False),
)

group_overview = plan({}, '请做2024年全集团发电量经营总览')
group_overview_plan = json.loads(group_overview['analysis_plan'])
check('group-scope-is-collection', group_overview_plan['organization_scope']['inputs'] == [], json.dumps(group_overview_plan, ensure_ascii=False))

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

qhd_plan = json.loads(plan(
  {'analysis_type': 'FACT', 'indicator_inputs': ['全厂发电量'], 'organization_inputs': ['秦皇岛公司']},
  '查询秦皇岛公司近半年的全厂发电量',
)['analysis_plan'])
qhd_resolved = resolve_ns['main'](
    json.dumps(qhd_plan, ensure_ascii=False),
    [{'code': '1001', 'name': '全厂发电量', 'unit': '', 'old_name': '', 'object_code': '1001', 'object_name': '电量'}],
    '',
    [
      {'code': '10004024', 'name': '中节能（秦皇岛）环保能源有限公司', 'full_path': '项目公司/中节能（秦皇岛）环保能源有限公司', 'is_detail_company': '1', 'tree_is_detail': '1'},
      {'code': '10004011', 'name': '中节能（秦皇岛）环保能源有限公司10004011', 'full_path': '项目公司/中节能（秦皇岛）环保能源有限公司10004011', 'is_detail_company': '1', 'tree_is_detail': '1'},
      {'code': '10004793', 'name': '中节能秦皇岛泰盛水务有限公司本部', 'full_path': '项目公司/中节能秦皇岛泰盛水务有限公司本部', 'is_detail_company': '1', 'tree_is_detail': '1'},
      {'code': '10004791', 'name': '中节能泰盛秦皇岛水务有限公司', 'full_path': '项目公司/中节能泰盛秦皇岛水务有限公司', 'is_detail_company': '1', 'tree_is_detail': '1'},
    ],
    '',
)
qhd_resolved_plan = json.loads(qhd_resolved['analysis_plan_json'])
check(
  'qhd-generation-company-preference',
  qhd_resolved['status'] == 'READY' and qhd_resolved_plan['organization_scope']['codes'] == ['10004024'],
  json.dumps(qhd_resolved, ensure_ascii=False),
)

ambiguous_plan = dict(resolved_plan)
ambiguous_plan['analysis_type'] = 'DRILLDOWN'
ambiguous_plan['organization_scope'] = {
    'type': 'company',
    'inputs': ['秦皇岛'],
    'codes': [],
    'names': [],
    'clarification': {
        'required': True,
        'slot': 'organization',
        'candidates': [
            {'code': '10004024', 'name': '中节能（秦皇岛）环保能源有限公司', 'full_path': '项目公司/中节能（秦皇岛）环保能源有限公司'},
            {'code': '10004011', 'name': '中节能（秦皇岛）环保能源有限公司10004011', 'full_path': '项目公司/中节能（秦皇岛）环保能源有限公司10004011'},
        ],
    },
}
ambiguous_plan['clarification'] = {'required': False, 'slot': '', 'candidates': []}
ambiguous_result = audit_ns['main'](
    json.dumps(ambiguous_plan, ensure_ascii=False),
    'ORGANIZATION_AMBIGUOUS',
    [],
    '',
)
ambiguous_protocol = json.loads(ambiguous_result['result_text'])
check(
  'organization-clarification-preserves-candidates',
  ambiguous_protocol['messageType'] == 'clarification'
    and ambiguous_protocol['clarification']['slot'] == 'organization'
    and [item['id'] for item in ambiguous_protocol['clarification']['candidates']] == ['10004024', '10004011'],
  ambiguous_result['result_text'],
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

drilldown_plan = json.loads(plan(
  {'analysis_type': 'DRILLDOWN', 'indicator_inputs': ['全厂发电量', '主设备运行时间'], 'organization_inputs': ['10004024']},
  '为什么组织10004024近6个月全厂发电量下降，参考主设备运行时间',
)['analysis_plan'])
drilldown_resolved = resolve_ns['main'](
    json.dumps(drilldown_plan, ensure_ascii=False),
    [
      {'code': '1001', 'name': '全厂发电量', 'unit': '', 'old_name': '', 'object_code': 'plant', 'object_name': '厂级'},
      {'code': '1712', 'name': '主设备运行时间', 'unit': '', 'old_name': '', 'object_code': '1025', 'object_name': '主设备'},
    ],
    '',
    [{'code': '10004024', 'name': '示例项目公司', 'abbreviation': '', 'full_path': '示例项目公司', 'layer': '4', 'parent_code': '', 'is_detail_company': '1', 'tree_is_detail': '1', 'enabled': '1'}],
    '',
)
drilldown_resolved_plan = json.loads(drilldown_resolved['analysis_plan_json'])
check(
  'drilldown-unregistered-related-does-not-block',
  drilldown_resolved['status'] == 'READY'
    and drilldown_resolved['can_execute'] == 1
    and drilldown_resolved_plan['indicator']['code'] == '1001'
    and drilldown_resolved_plan['related_indicators'] == []
    and drilldown_resolved_plan['coverage']['unresolved_indicators'][0]['code'] == '1712'
    and '1712' not in drilldown_resolved['query_sql'],
  json.dumps(drilldown_resolved, ensure_ascii=False),
)
check(
  'drilldown-does-not-auto-expand-fuzzy-metrics',
  drilldown_plan['indicator_search_terms'] == ['全厂发电量', '主设备运行时间']
    and drilldown_resolved_plan['coverage']['candidates'][0]['code'] == '1712',
  json.dumps(drilldown_plan, ensure_ascii=False) + json.dumps(drilldown_resolved_plan, ensure_ascii=False),
)

overview = plan({}, '看看最近生产经营有什么值得关注的问题')
overview_plan = json.loads(overview['analysis_plan'])
overview_resolved = resolve_ns['main'](
    json.dumps(overview_plan, ensure_ascii=False),
    [
      {'code': '1001', 'name': '全厂发电量', 'unit': '', 'old_name': '', 'object_code': 'plant', 'object_name': '厂级'},
      {'code': '1700', 'name': '生活垃圾入厂量', 'unit': '', 'old_name': '', 'object_code': 'waste', 'object_name': '垃圾'},
      {'code': '1701', 'name': '全厂上网电量', 'unit': '', 'old_name': '', 'object_code': 'grid', 'object_name': '上网'},
    ],
    '',
    [{'code': '10004024', 'name': '示例项目公司', 'abbreviation': '', 'full_path': '示例项目公司', 'layer': '4', 'parent_code': '', 'is_detail_company': '1', 'tree_is_detail': '1', 'enabled': '1'}],
    '',
)
overview_resolved_plan = json.loads(overview_resolved['analysis_plan_json'])
overview_state = json.loads(overview_resolved['analysis_state'])
check(
  'overview-unregistered-defaults-are-coverage-only',
  overview_resolved['status'] == 'READY'
    and overview_resolved_plan['indicator']['code'] == '1001'
    and overview_resolved_plan['related_indicators'] == []
    and len(overview_resolved_plan['coverage']['unresolved_indicators']) == 2
    and overview_state['analysis_steps'][0] == 'EXECUTIVE_OVERVIEW',
  json.dumps(overview_resolved, ensure_ascii=False),
)

unregistered_main_plan = json.loads(plan(
  {'analysis_type': 'FACT', 'indicator_inputs': ['主设备运行时间'], 'organization_inputs': ['10004024']},
  '查询组织10004024近6个月主设备运行时间',
)['analysis_plan'])
unregistered_main = resolve_ns['main'](
    json.dumps(unregistered_main_plan, ensure_ascii=False),
    [{'code': '1712', 'name': '主设备运行时间', 'unit': '', 'old_name': '', 'object_code': '1025', 'object_name': '主设备'}],
    '',
    [{'code': '10004024', 'name': '示例项目公司', 'abbreviation': '', 'full_path': '示例项目公司', 'layer': '4', 'parent_code': '', 'is_detail_company': '1', 'tree_is_detail': '1', 'enabled': '1'}],
    '',
)
check(
  'unregistered-primary-still-blocks',
  unregistered_main['status'] == 'AGGREGATION_NOT_CONFIRMED' and unregistered_main['can_execute'] == 0,
  json.dumps(unregistered_main, ensure_ascii=False),
)

anomaly = plan({}, '哪些公司连续3个月发电量下降')
anomaly_plan = json.loads(anomaly['analysis_plan'])
check('anomaly-plan', anomaly_plan['analysis_type'] == 'ANOMALY' and anomaly_plan['time']['start'].endswith('-01'), json.dumps(anomaly_plan, ensure_ascii=False))
check('anomaly-focus', anomaly_plan['anomaly_focus'] == 'CONSECUTIVE_DECREASE', json.dumps(anomaly_plan, ensure_ascii=False))
anomaly_model_override = plan({'analysis_type': 'TREND', 'indicator_inputs': ['发电量'], 'organization_inputs': ['哪些公司']}, '哪些公司连续3个月发电量下降')
anomaly_model_override_plan = json.loads(anomaly_model_override['analysis_plan'])
check('anomaly-cue-overrides-model', anomaly_model_override_plan['analysis_type'] == 'ANOMALY' and anomaly_model_override_plan['organization_scope']['type'] == 'project_company', json.dumps(anomaly_model_override_plan, ensure_ascii=False))

check('overview-plan', overview_plan['analysis_type'] == 'EXECUTIVE_OVERVIEW' and overview_plan['time']['start'] != overview_plan['time']['end'], json.dumps(overview_plan, ensure_ascii=False))

compound = plan({}, '请做最近生产经营总览，包含发电量排名、近6个月趋势和异常，再分析原因')
compound_plan = json.loads(compound['analysis_plan'])
check(
    'compound-overview-plan',
    compound_plan['analysis_type'] == 'EXECUTIVE_OVERVIEW'
      and compound_plan['analysis_steps'][0] == 'EXECUTIVE_OVERVIEW'
      and all(item in compound_plan['analysis_steps'] for item in ['RANKING', 'TREND', 'ANOMALY', 'DRILLDOWN']),
    json.dumps(compound_plan, ensure_ascii=False),
)
check('overview-primary-default', compound_plan['indicator_inputs'] == ['全厂发电量'], json.dumps(compound_plan, ensure_ascii=False))

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
  env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
  input: JSON.stringify({ plan: encode(planCode), resolve: encode(resolveCode), audit: encode(auditCode) }),
  maxBuffer: 8 * 1024 * 1024,
})

if (result.error) throw result.error
if (result.status !== 0) {
  process.stderr.write(result.stderr || result.stdout)
  process.exit(result.status || 1)
}

process.stdout.write(result.stdout)
