/*
 * Upgrade the live Dify intelligent data-query Chatflow to an analysis-plan
 * driven enterprise analysis assistant.
 *
 * This script is intentionally executed inside an authenticated Dify browser
 * page. It never stores credentials or database secrets. The caller must take
 * a live backup before running it and should publish only after draft tests
 * pass.
 */

async function run() {
  const appId = '200bb456-20bf-48ab-af38-c7b9e59ff070'
  const csrfCookie = document.cookie.split('; ').find(item => item.startsWith('csrf_token='))
  const csrf = csrfCookie
    ? decodeURIComponent(csrfCookie.split('=').slice(1).join('='))
    : ''
  const headers = {
    'Content-Type': 'application/json',
    'X-CSRF-Token': csrf,
  }
  const draftUrl = `/console/api/apps/${appId}/workflows/draft`
  const getDraft = async () => (await fetch(draftUrl, { headers })).json()
  const saveDraft = async draft => {
    const response = await fetch(draftUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        graph: draft.graph,
        features: draft.features,
        environment_variables: draft.environment_variables,
        conversation_variables: draft.conversation_variables,
        rag_pipeline_variables: draft.rag_pipeline_variables,
        hash: draft.hash,
      }),
    })
    const body = await response.json().catch(() => ({}))
    return { status: response.status, body }
  }

  const original = await getDraft()
  const templateNodes = original.graph?.nodes || []
  const codeTemplate = templateNodes.find(node => node.data?.type === 'code')
  const llmTemplate = templateNodes.find(node => node.data?.type === 'llm')
  const toolTemplate = templateNodes.find(node => node.data?.type === 'tool')
  const answerTemplate = templateNodes.find(node => node.data?.type === 'answer')
  const startTemplate = templateNodes.find(node => node.data?.type === 'start')
  const ifTemplate = templateNodes.find(node => node.data?.type === 'if-else')

  if (!codeTemplate || !llmTemplate || !toolTemplate || !answerTemplate || !startTemplate || !ifTemplate) {
    throw new Error('当前草稿缺少可复用的 Dify 节点模板，升级已停止')
  }

  const deepCopy = value => JSON.parse(JSON.stringify(value))
  const cleanData = data => {
    const result = deepCopy(data)
    delete result.parentId
    delete result.iteration_id
    delete result.isInIteration
    delete result.isInLoop
    result.selected = false
    return result
  }
  const wrapper = (template, id, data, x, y, height = 88) => {
    const node = deepCopy(template)
    node.id = id
    delete node.parentId
    node.position = { x, y }
    node.positionAbsolute = { x, y }
    node.height = height
    node.width = 242
    node.zIndex = 0
    node.selected = false
    node.sourcePosition = 'right'
    node.targetPosition = 'left'
    node.data = cleanData(data)
    return node
  }
  const codeNode = (id, title, code, variables, outputs, x, y) => {
    const data = cleanData(codeTemplate.data)
    data.type = 'code'
    data.title = title
    data.code = code
    data.code_language = 'python3'
    data.variables = variables
    data.outputs = outputs
    return wrapper(codeTemplate, id, data, x, y, 52)
  }
  const llmNode = (id, title, systemText, userText, x, y) => {
    const data = cleanData(llmTemplate.data)
    data.type = 'llm'
    data.title = title
    data.context = { enabled: false, variable_selector: [] }
    data.memory = {
      query_prompt_template: '',
      role_prefix: { assistant: '', user: '' },
      window: { enabled: false, size: 1 },
    }
    data.prompt_template = [
      { id: `${id}-system`, role: 'system', text: systemText },
      { id: `${id}-user`, role: 'user', text: userText },
    ]
    return wrapper(llmTemplate, id, data, x, y, 88)
  }
  const toolNode = (id, title, sqlSelector, x, y) => {
    const data = cleanData(toolTemplate.data)
    data.type = 'tool'
    data.title = title
    data.error_strategy = 'fail-branch'
    data.tool_parameters = deepCopy(toolTemplate.data.tool_parameters)
    data.tool_parameters.sql = { type: 'mixed', value: `{{#${sqlSelector.join('.')}#}}` }
    return wrapper(toolTemplate, id, data, x, y, 118)
  }
  const edge = (source, target, sourceType, targetType, sourceHandle = 'source') => ({
    id: `${source}-${sourceHandle}-${target}-target`,
    data: {
      isInIteration: false,
      isInLoop: false,
      sourceType,
      targetType,
    },
    source,
    sourceHandle,
    target,
    targetHandle: 'target',
    type: 'custom',
    zIndex: 0,
  })

  const planCode = String.raw`
import json
import re
from datetime import date, timedelta

def _text(value):
    return str(value or "").strip()

def _compact(value):
    return re.sub(r"\s+", "", _text(value))

def _load(value):
    if isinstance(value, dict):
        return value
    text = _text(value)
    if not text:
        return {}
    for candidate in [text, re.sub(r"<think>[\s\S]*?</think>", "", text, flags=re.I).strip()]:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except Exception:
            pass
    return {}

def _first_day(year, month):
    return date(year, month, 1)

def _add_months(value, months):
    index = value.year * 12 + value.month - 1 + months
    return date(index // 12, index % 12 + 1, 1)

def _shift_year(value, years):
    try:
        return value.replace(year=value.year + years)
    except ValueError:
        return value.replace(year=value.year + years, day=28)

def _date_range(question, today):
    text = _compact(question)
    tomorrow = today + timedelta(days=1)
    next_month = _add_months(today.replace(day=1), 1)

    match = re.search(r"(\d{4})年(\d{1,2})月(?:至|到|起至|-)\s*(?:(\d{4})年)?(\d{1,2})月", text)
    if match:
        y1, m1 = int(match.group(1)), int(match.group(2))
        y2, m2 = int(match.group(3) or y1), int(match.group(4))
        if not (1 <= m1 <= 12 and 1 <= m2 <= 12):
            return today, today, "month", "invalid_date"
        start = _first_day(y1, m1)
        return start, _add_months(_first_day(y2, m2), 1), "month", "explicit_month_range"

    match = re.search(r"(\d{4})[-年](\d{1,2})月?", text)
    if match:
        year, month = int(match.group(1)), int(match.group(2))
        if not 1 <= month <= 12:
            return today, today, "month", "invalid_date"
        start = _first_day(year, month)
        return start, _add_months(start, 1), "month", "explicit_month"

    match = re.search(r"近(\d+)个?月", text)
    if match:
        count = max(1, min(int(match.group(1)), 120))
        return _add_months(today.replace(day=1), -count + 1), next_month, "month", "relative_months"

    match = re.search(r"(?:最近|近)(\d+)天", text)
    if match:
        count = max(1, min(int(match.group(1)), 3660))
        return today - timedelta(days=count - 1), tomorrow, "day", "relative_days"

    if "今天" in text:
        return today, tomorrow, "day", "today"
    if "昨天" in text:
        return today - timedelta(days=1), today, "day", "yesterday"
    if "上月" in text:
        start = _add_months(today.replace(day=1), -1)
        return start, today.replace(day=1), "month", "previous_month"
    if "本月" in text:
        return today.replace(day=1), next_month, "month", "current_month"
    if "去年" in text:
        return date(today.year - 1, 1, 1), date(today.year, 1, 1), "month", "previous_year"
    match = re.search(r"今年(\d{1,2})月至今", text)
    if match:
        month = int(match.group(1))
        if not 1 <= month <= 12:
            return today, today, "month", "invalid_date"
        return _first_day(today.year, month), next_month, "month", "year_to_date_month"

    match = re.search(r"(\d{4})年", text)
    if match:
        year = int(match.group(1))
        if year == today.year:
            return date(year, 1, 1), tomorrow, "month", "current_year_to_date"
        return date(year, 1, 1), date(year + 1, 1, 1), "month", "explicit_year"

    if "今年" in text:
        return date(today.year, 1, 1), tomorrow, "month", "current_year_to_date"

    return today.replace(day=1), next_month, "month", "default_current_month"

def _terms(value):
    raw = _compact(value)
    terms = []
    for item in [raw, raw.replace("有限公司", ""), raw.replace("公司", ""), raw.replace("本部", "")]:
        if item and item not in terms:
            terms.append(item[:80])
    return terms

def _literal(value):
    return "'" + _text(value).replace("'", "''")[:100] + "'"

def _like(columns, terms):
    conditions = []
    for term in terms:
        literal = _literal(term)
        conditions.extend([column + " ILIKE '%' || " + literal + " || '%'" for column in columns])
    return " OR ".join(conditions) if conditions else "1=0"

def _analysis_type(question, intent, previous):
    text = _compact(question)
    explicit = _text(intent.get("analysis_type") or intent.get("analysisType")).upper()
    allowed = {"FACT", "TREND", "RANKING", "COMPARISON", "RANKING_COMPARISON", "DISTRIBUTION", "ANOMALY", "CORRELATION", "DRILLDOWN", "EXECUTIVE_OVERVIEW"}
    if explicit in allowed and explicit != "FACT":
        detected = explicit
    elif re.search(r"生产经营|经营情况|值得关注|整体表现", text):
        detected = "EXECUTIVE_OVERVIEW"
    elif re.search(r"为什么|原因|下钻|怎么回事|为何|下降原因", text):
        detected = "DRILLDOWN"
    elif re.search(r"异常|不正常|连续[三四五六七八九十0-9]+个月|连续[三四五六七八九十0-9]+期", text):
        detected = "ANOMALY"
    elif "同比" in text and re.search(r"排名|最高|最低|增幅|下降最多", text):
        detected = "RANKING_COMPARISON"
    elif re.search(r"排名|排行|最高|最低|前\d+|后\d+|Top|Bottom", text, flags=re.I):
        detected = "RANKING"
    elif "同比" in text or "环比" in text or re.search(r"比较|对比|哪个好|分别", text):
        detected = "COMPARISON"
    elif re.search(r"趋势|走势|变化|各月|每月|逐月|每天|日度", text):
        detected = "TREND"
    else:
        detected = "FACT"

    if previous and re.search(r"只看前|只看后|前\d+|后\d+|排多少|和去年|同比|环比|再看看|看近", text):
        previous_type = _text(previous.get("analysis_type")).upper()
        if previous_type in allowed and detected == "FACT":
            detected = previous_type
        if "同比" in text and previous_type == "RANKING":
            detected = "RANKING_COMPARISON"
    return detected

def _top_n(question, intent, previous):
    text = _compact(question)
    match = re.search(r"(?:前|后|Top|Bottom)\s*(\d+)", text, flags=re.I)
    if match:
        return max(1, min(int(match.group(1)), 50))
    match = re.search(r"最高和最低的?(\d+)|最高最低各?(\d+)", text)
    if match:
        return max(1, min(int(match.group(1) or match.group(2)), 50))
    value = intent.get("top_n") or intent.get("topN") or (previous or {}).get("top_n")
    try:
        return max(1, min(int(value), 50))
    except Exception:
        return 10

def _clean_org_candidate(value):
    value = _text(value)
    value = re.sub(r"^(?:请|帮我|帮忙|查询|查看|统计|分析|比较|对比|了解|算一下)+", "", value)
    value = re.sub(r"^(?:今年|本年|去年|上年|本月|上月|本季度|近\d+个?月|近\d+天|近\d+年)+", "", value)
    value = re.sub(r"(?:的)?(?:项目公司|各公司|每家公司|所有公司)$", "", value)
    if _compact(value) in ("", "公司", "项目", "项目公司", "各公司", "每家公司", "所有公司"):
        return ""
    return _text(value)

def _org_inputs(intent, previous, question):
    candidates = intent.get("organization_inputs") or intent.get("organizations") or intent.get("organization_names")
    if isinstance(candidates, str):
        candidates = re.split(r"[、,，和与及]", candidates)
    candidates = [_clean_org_candidate(item) for item in (candidates or []) if _clean_org_candidate(item)]
    single = _text(intent.get("organization_input") or intent.get("organization") or intent.get("org_input"))
    if single and not candidates:
        candidates = [_clean_org_candidate(item) for item in re.split(r"[、,，和与及]", single) if _clean_org_candidate(item)]
    if not candidates:
        candidates = list((previous or {}).get("organization_inputs") or [])

    # The model extracts organization names; these regexes only distinguish
    # organization scope words from metric text and never choose a code.
    if not candidates:
        match = re.search(r"([^，。！？,!?]{1,40}(?:公司|有限公司|本部))", question)
        if match:
            candidate = _clean_org_candidate(match.group(1))
            if candidate:
                candidates = [candidate]
    return list(dict.fromkeys(candidates))[:12]

def _region_input(intent, question, previous):
    value = _text(intent.get("region_input") or intent.get("region") or (previous or {}).get("region_input"))
    if value:
        return value
    match = re.search(r"(华东|华北|华南|华中|西南|西北|东北|鲁北|北方|南方|雄安|山东|中西部)(?:大区|地区|区域)?", question)
    return _text(match.group(1)) if match else ""

def _indicator_inputs(intent, previous, question, analysis_type):
    values = intent.get("indicator_inputs") or intent.get("indicators")
    if isinstance(values, str):
        values = [values]
    values = [_text(item) for item in (values or []) if _text(item)]
    primary = _text(intent.get("indicator_input") or intent.get("indicator") or intent.get("metric"))
    if primary and not values:
        values = [primary]
    if not values:
        values = list((previous or {}).get("indicator_inputs") or [])
    if not values:
        # This is a semantic default for open-ended operating review, not a
        # test-case answer or a database code.
        if analysis_type == "EXECUTIVE_OVERVIEW":
            values = ["生活垃圾入厂量", "全厂发电量", "全厂上网电量"]
        else:
            match = re.search(r"(?:查询|查看|比较|对比|按|哪个|哪些|今年|去年|本月|近\d+个?月)?([^，。！？,!?]{2,30}?)(?:是多少|怎么样|趋势|排名|最高|最低|同比|环比|下降|上升|变化|的)", question)
            if match:
                values = [_text(match.group(1))]
    cleaned = []
    for value in values:
        value = re.sub(r"^(?:请|帮我|查询|查看|统计|分析|比较|对比|了解|算一下)+", "", value)
        value = re.sub(r"^(?:今年|本年|去年|上年|本月|上月|本季度|近\d+个?月|近\d+天|近\d+年)+", "", value)
        value = re.sub(r"(?:各|每|所有)?项目公司", "", value)
        value = re.sub(r"(?:各公司|每家公司|所有公司)$", "", value)
        value = re.sub(r"^(?:组织\s*[A-Za-z0-9_-]+|公司\s*[A-Za-z0-9_-]+)的", "", value)
        value = _text(value)
        if value and value not in ("项目公司", "各公司", "每家公司", "所有公司"):
            cleaned.append(value)
    return list(dict.fromkeys(cleaned))[:8]

def _search_terms(indicator_inputs, analysis_type):
    terms = list(indicator_inputs)
    if analysis_type == "DRILLDOWN":
        joined = "".join(terms)
        if re.search(r"发电|上网|电量", joined):
            terms.extend(["生活垃圾入厂量", "入炉量", "运行时间", "停机时间", "汽耗率", "厂用电率"])
        elif re.search(r"垃圾|入厂", joined):
            terms.extend(["发电量", "运行时间", "停机时间"])
    return list(dict.fromkeys([_text(item) for item in terms if _text(item)]))[:20]

def _build_lookup_sql(indicator_terms, org_inputs, region_input, scoped):
    indicator_where = _like(['"newIndicatorname"', '"oldIndicatorname"'], indicator_terms)
    indicator_sql = (
        'SELECT "newcode" AS code, "newIndicatorname" AS name, '
        'COALESCE("IndicatorUnit", \'\') AS unit, '
        '"newObjectcode" AS object_code, "newObjectname" AS object_name, '
        'COALESCE("oldIndicatorname", \'\') AS old_name '
        'FROM MSOKFPT."CGXTAPPMISNewIndicator" WHERE ' + indicator_where +
        ' ORDER BY "newcode" LIMIT 300'
    )
    org_columns = (
        '"Code" AS code, "Name_CHS" AS name, '
        'COALESCE("Abbreviation_CHS", \'\') AS abbreviation, '
        'COALESCE("FullPathName_CHS", \'\') AS full_path, '
        'COALESCE(CAST("PntHrInfo_Layer" AS VARCHAR), \'\') AS layer, '
        'COALESCE("PntHrInfo_ParentElement", \'\') AS parent_code, '
        'COALESCE("IsDetailCompany", \'\') AS is_detail_company, '
        'COALESCE("TreeInfo_IsDetail", \'\') AS tree_is_detail, '
        'COALESCE("State_IsEnabled", \'1\') AS enabled'
    )
    if org_inputs:
        org_terms = []
        for item in org_inputs:
            org_terms.extend(_terms(item))
        org_where = _like(['"Code"', '"Name_CHS"', '"Abbreviation_CHS"', '"FullPathName_CHS"'], list(dict.fromkeys(org_terms)))
    elif region_input:
        org_where = _like(['"Name_CHS"', '"Abbreviation_CHS"', '"FullPathName_CHS"'], _terms(region_input))
    elif scoped:
        org_where = (
            '"State_IsEnabled" = \'1\' AND '
            'COALESCE("IsDetailCompany", \'0\') = \'1\' AND '
            '("Name_CHS" ILIKE \'%公司%\' OR "FullPathName_CHS" ILIKE \'%有限公司%\')'
        )
    else:
        org_where = '1=0'
    organization_sql = 'SELECT ' + org_columns + ' FROM MSOKFPT."BFAdminOrganization" WHERE ' + org_where + ' ORDER BY "Name_CHS" LIMIT 300'
    return indicator_sql, organization_sql

def main(intent_text: str, question: str, previous_state: str = "") -> dict:
    intent = _load(intent_text)
    previous = _load(previous_state)
    original_question = _text(question)
    today = date.today()
    start, end, granularity, time_source = _date_range(original_question, today)
    status = "READY"
    if start >= end or time_source == "invalid_date":
        status = "TIME_PARSE_FAILED"
    elif start > today:
        status = "FUTURE_TIME"

    analysis_type = _analysis_type(original_question, intent, previous)
    indicator_inputs = _indicator_inputs(intent, previous, original_question, analysis_type)
    org_inputs = _org_inputs(intent, previous, original_question)
    region_input = _region_input(intent, original_question, previous)
    top_n = _top_n(original_question, intent, previous)
    text = _compact(original_question)
    ranking_mode = "bottom" if re.search(r"最低|最少|最差|后\d+|Bottom", text, flags=re.I) else "top"
    if "最高和最低" in text or "最高最低" in text:
        ranking_mode = "both"
    comparison_type = "YOY" if "同比" in text else ("MOM" if "环比" in text else _text((previous or {}).get("comparison", {}).get("type")))
    comparison = {"type": comparison_type or "none", "baseline_start": "", "baseline_end": ""}
    if comparison_type == "YOY":
        comparison["baseline_start"] = _shift_year(start, -1).isoformat()
        comparison["baseline_end"] = _shift_year(end, -1).isoformat()
    elif comparison_type == "MOM":
        baseline_start = _add_months(start.replace(day=1), -1)
        comparison["baseline_start"] = baseline_start.isoformat()
        comparison["baseline_end"] = start.replace(day=1).isoformat()

    dimensions = []
    if analysis_type in ("RANKING", "RANKING_COMPARISON", "ANOMALY") or re.search(r"各公司|项目公司|每家公司|公司排名", text):
        dimensions = [{"name": "organization", "field": "org_code", "level": "project_company"}]
    elif len(org_inputs) > 1 or region_input:
        dimensions = [{"name": "organization", "field": "org_code", "level": "organization"}]
    else:
        dimensions = [{"name": "time", "field": "period", "level": "month" if granularity != "day" else "day"}]

    if region_input:
        scope_type = "region"
    elif len(org_inputs) > 1:
        scope_type = "multi_company"
    elif org_inputs:
        scope_type = "company"
    elif analysis_type in ("RANKING", "RANKING_COMPARISON", "ANOMALY", "EXECUTIVE_OVERVIEW"):
        scope_type = "project_company"
    else:
        scope_type = "all"

    search_terms = _search_terms(indicator_inputs, analysis_type)
    indicator_sql, organization_sql = _build_lookup_sql(
        search_terms,
        org_inputs,
        region_input,
        scope_type in ("project_company", "region", "multi_company"),
    )
    plan = {
        "original_question": original_question,
        "analysis_type": analysis_type,
        "indicator_inputs": indicator_inputs,
        "indicator_search_terms": search_terms,
        "indicator": {"code": "", "name": "", "unit": "", "level": "", "aggregation": "", "aggregation_source": "", "candidates": []},
        "related_indicators": [],
        "time": {"expression": _text(intent.get("date_expression") or intent.get("time_expression")) or time_source, "start": start.isoformat(), "end": end.isoformat(), "granularity": granularity, "comparison_period": comparison},
        "filters": [],
        "dimensions": dimensions,
        "organization_scope": {"type": scope_type, "inputs": org_inputs, "region_input": region_input, "codes": [], "names": [], "candidates": [], "grouping_level": "project_company" if scope_type == "project_company" else "organization"},
        "sort": {"field": "change_rate" if analysis_type == "RANKING_COMPARISON" else "value", "direction": "asc" if ranking_mode == "bottom" else "desc"},
        "ranking_mode": ranking_mode,
        "top_n": top_n,
        "comparison": comparison,
        "analysis_actions": [analysis_type.lower()],
        "chart_preference": "bar" if "RANKING" in analysis_type or analysis_type in ("COMPARISON", "DISTRIBUTION") else "line",
        "clarification": {"required": False, "slot": "", "candidates": []},
        "current_date": today.isoformat(),
        "allowed_tables": [],
        "status": status,
        "previous_state_used": bool(previous),
    }
    if status == "READY" and comparison_type == "YOY" and comparison["baseline_start"] > today.isoformat():
        status = "FUTURE_TIME"
        plan["status"] = status
    return {
        "analysis_plan": json.dumps(plan, ensure_ascii=False),
        "indicator_lookup_sql": indicator_sql,
        "organization_lookup_sql": organization_sql,
        "status": status,
    }
`

  const resolveCode = String.raw`
import json
import re
from datetime import date

def _text(value):
    return str(value or "").strip()

def _load(value):
    if isinstance(value, dict):
        return value
    try:
        parsed = json.loads(_text(value) or "{}")
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}

def _rows(value):
    if isinstance(value, str):
        try:
            return _rows(json.loads(value))
        except Exception:
            return []
    if isinstance(value, dict):
        if isinstance(value.get("json"), (dict, list)):
            return _rows(value.get("json"))
        if isinstance(value.get("result"), list):
            return [item for item in value["result"] if isinstance(item, dict)]
        if isinstance(value.get("data"), (dict, list)):
            return _rows(value.get("data"))
        return [value] if value else []
    if isinstance(value, list):
        output = []
        for item in value:
            output.extend(_rows(item))
        return output
    return []

def _unique(rows, keys):
    result = []
    seen = set()
    for row in rows:
        key = tuple(_text(row.get(name)) for name in keys)
        if key in seen:
            continue
        seen.add(key)
        result.append(row)
    return result

def _level(name):
    text = _text(name)
    if re.search(r"全厂|全场|总发电|总量|合计", text):
        return "plant_total"
    if re.search(r"机组|锅炉|[1-9一二三四五六七八九]号", text):
        return "unit"
    if re.search(r"率|单耗|效率|占比", text):
        return "rate"
    return "base"

def _semantic(row):
    name = _text(row.get("name") or row.get("newIndicatorname"))
    level = _level(name)
    # Only additive measures are enabled by default. Rates and ratios require
    # a confirmed weighted business definition and are deliberately blocked.
    if level == "rate":
        aggregation = ""
        source = "business_rule_required"
    elif re.search(r"量|发电|供汽|供热|产渣|耗量|消耗|运行时间|停机时间", name):
        aggregation = "SUM"
        source = "semantic_measurement_registry_v1"
    else:
        aggregation = ""
        source = "business_rule_required"
    return level, aggregation, source

def _choose(candidates, inputs, prefer_plant=True):
    if not candidates:
        return None, []
    normalized = [_text(item) for item in inputs if _text(item)]
    exact = []
    for row in candidates:
        code = _text(row.get("code"))
        name = _text(row.get("name"))
        old_name = _text(row.get("old_name"))
        if code in normalized or name in normalized or old_name in normalized:
            exact.append(row)
    pool = exact or candidates
    if prefer_plant and len(pool) > 1:
        plant = [row for row in pool if _level(row.get("name")) == "plant_total"]
        if len(plant) == 1:
            return plant[0], plant
        if plant:
            pool = plant
    codes = list(dict.fromkeys(_text(row.get("code")) for row in pool if _text(row.get("code"))))
    if len(codes) == 1:
        return next(row for row in pool if _text(row.get("code")) == codes[0]), pool
    return None, pool

def _literal(value):
    return "'" + _text(value).replace("'", "''")[:80] + "'"

def _table_names(start, end):
    try:
        start_date = date.fromisoformat(start)
        end_date = date.fromisoformat(end)
    except Exception:
        return []
    tables = []
    cursor = start_date.replace(day=1)
    while cursor < end_date:
        name = "CGXTAPPMISDate_%04d_%s" % (cursor.year, "06" if cursor.month <= 6 else "12")
        if name not in tables:
            tables.append(name)
        cursor = date(cursor.year, 7, 1) if cursor.month <= 6 else date(cursor.year + 1, 1, 1)
    return tables

def _org_match(row, value):
    term = _text(value)
    if not term:
        return False
    return term == _text(row.get("code")) or any(term in _text(row.get(key)) for key in ("name", "abbreviation", "full_path"))

def _safe_sql_identifier(value):
    return bool(re.match(r"^CGXTAPPMISDate_\d{4}_(?:06|12)$", _text(value)))

def _build_data_sql(plan, indicators, orgs):
    tables = plan.get("allowed_tables") or []
    if not tables or not indicators:
        return "", "NO_ALLOWED_TABLE"
    start = _text(plan.get("time", {}).get("start"))
    end = _text(plan.get("time", {}).get("end"))
    comparison = plan.get("comparison", {}) or {}
    baseline_start = _text(comparison.get("baseline_start"))
    baseline_end = _text(comparison.get("baseline_end"))
    plan_type = _text(plan.get("analysis_type"))
    scope = plan.get("organization_scope", {}) or {}
    codes = [_text(row.get("code")) for row in orgs if _text(row.get("code"))]
    metric_codes = [_text(row.get("code")) for row in indicators if _text(row.get("code"))]
    metric_code_sql = ",".join(_literal(code) for code in metric_codes)
    if not metric_code_sql:
        return "", "INDICATOR_NOT_FOUND"

    union_parts = []
    for table in tables:
        if not _safe_sql_identifier(table):
            return "", "SQL_VALIDATION_FAILED"
        union_parts.append(
            'SELECT CAST(t."ZBRQ" AS DATE) AS event_date, '
            'CAST(t."ZBZ" AS VARCHAR) AS raw_value, '
            'CASE WHEN TRIM(CAST(t."ZBZ" AS VARCHAR)) ~ \'^[+-]?[0-9]+(\\.[0-9]+)?$\' '
            'THEN CAST(TRIM(CAST(t."ZBZ" AS VARCHAR)) AS NUMERIC(24,6)) ELSE NULL END AS value_num, '
            'CAST(t."newIndicator" AS VARCHAR) AS metric_code, '
            'CAST(t."orgcode" AS VARCHAR) AS org_code, '
            'CAST(t."ZBBM" AS VARCHAR) AS dimension_code, '
            'COALESCE(o."Name_CHS", \'\') AS org_name '
            'FROM MSOKFPT."' + table + '" t '
            'LEFT JOIN MSOKFPT."BFAdminOrganization" o ON CAST(t."orgcode" AS VARCHAR)=CAST(o."Code" AS VARCHAR) '
            'WHERE CAST(t."newIndicator" AS VARCHAR) IN (' + metric_code_sql + ') '
            'AND CAST(t."ZBRQ" AS DATE) >= ' + _literal(start) + ' '
            'AND CAST(t."ZBRQ" AS DATE) < ' + _literal(end) + ' '
            + (' OR ' if False else '')
        )
        if baseline_start and baseline_end:
            union_parts[-1] += (
                ' OR (CAST(t."ZBRQ" AS DATE) >= ' + _literal(baseline_start) +
                ' AND CAST(t."ZBRQ" AS DATE) < ' + _literal(baseline_end) + ')'
            )
        if codes:
            union_parts[-1] += ' AND CAST(t."orgcode" AS VARCHAR) IN (' + ",".join(_literal(code) for code in codes) + ')'
        elif scope.get("type") in ("project_company", "region"):
            union_parts[-1] += (
                ' AND COALESCE(o."State_IsEnabled", \'1\') = \'1\' '
                'AND COALESCE(o."IsDetailCompany", \'0\') = \'1\' '
                'AND (o."Name_CHS" ILIKE \'%公司%\' OR o."FullPathName_CHS" ILIKE \'%有限公司%\')'
            )
        union_parts[-1] = union_parts[-1].replace(' AND CAST(t."ZBRQ" AS DATE) >= ' + _literal(start) + ' AND CAST(t."ZBRQ" AS DATE) < ' + _literal(end) + '  OR ', ' AND (CAST(t."ZBRQ" AS DATE) >= ' + _literal(start) + ' AND CAST(t."ZBRQ" AS DATE) < ' + _literal(end) + ' OR ')
        if baseline_start and baseline_end:
            union_parts[-1] += ')'
    raw = " UNION ALL ".join(union_parts)

    include_org = any(item.get("field") == "org_code" for item in (plan.get("dimensions") or [])) or plan_type in ("RANKING", "RANKING_COMPARISON", "ANOMALY", "EXECUTIVE_OVERVIEW")
    include_metric = len(indicators) > 1 or plan_type in ("DRILLDOWN", "EXECUTIVE_OVERVIEW")
    granularity = _text(plan.get("time", {}).get("granularity"))
    if plan_type in ("RANKING", "RANKING_COMPARISON", "COMPARISON", "RANKING_COMPARISON", "DISTRIBUTION", "EXECUTIVE_OVERVIEW"):
        period_expr = "'total'"
    elif granularity == "day":
        period_expr = "TO_CHAR(event_date, 'YYYY-MM-DD')"
    else:
        period_expr = "TO_CHAR(event_date, 'YYYY-MM')"
    set_expr = "CASE WHEN event_date >= %s AND event_date < %s THEN 'current' ELSE 'baseline' END" % (_literal(start), _literal(end)) if baseline_start and baseline_end else "'current'"
    select_fields = [set_expr + ' AS period_set', period_expr + ' AS period']
    group_fields = ['period_set', 'period']
    if include_metric:
        select_fields.extend(['metric_code', 'MAX(metric_code) AS indicator_code'])
        group_fields.append('metric_code')
    else:
        select_fields.append("MAX(metric_code) AS indicator_code")
    if include_org:
        select_fields.extend(['org_code', 'MAX(org_name) AS org_name'])
        group_fields.append('org_code')
    else:
        select_fields.extend(["'' AS org_code", "'全部组织' AS org_name"])
    select_fields.extend([
        'SUM(value_num) AS value',
        'COUNT(*) AS row_count',
        'MAX(event_date) AS period_data_cutoff_date',
        'COUNT(DISTINCT dimension_code) AS dimension_count',
        'SUM(CASE WHEN value_num IS NULL THEN 1 ELSE 0 END) AS invalid_value_count',
        'COUNT(DISTINCT CASE WHEN d.event_date IS NOT NULL THEN d.event_date END) AS duplicate_key_groups',
        '(SELECT MAX(event_date) FROM raw) AS data_cutoff_date',
    ])
    query = (
        'WITH raw AS (' + raw + '), '
        'dupes AS (SELECT metric_code, org_code, event_date FROM raw GROUP BY metric_code, org_code, event_date HAVING COUNT(*) > 1), '
        'aggregated AS (SELECT ' + ', '.join(select_fields) +
        ' FROM raw LEFT JOIN dupes d ON d.metric_code=raw.metric_code AND d.org_code=raw.org_code AND d.event_date=raw.event_date '
        ' GROUP BY ' + ', '.join(group_fields) + '), '
        'labeled AS (SELECT a.*, COALESCE(i."newIndicatorname", a.indicator_code) AS indicator_name '
        'FROM aggregated a LEFT JOIN MSOKFPT."CGXTAPPMISNewIndicator" i ON CAST(i."newcode" AS VARCHAR)=a.indicator_code) '
        'SELECT * FROM labeled ORDER BY period_set, period, value DESC NULLS LAST LIMIT 1000'
    )
    return query, "READY"

def main(analysis_plan: str, indicator_data, indicator_error: str, organization_data, organization_error: str) -> dict:
    plan = _load(analysis_plan)
    indicator_rows = _unique(_rows(indicator_data), ["code", "name"])
    organization_rows = _unique(_rows(organization_data), ["code", "name"])
    if _text(indicator_error) or _text(organization_error):
        plan["status"] = "SQL_METADATA_LOOKUP_FAILED"
    plan["indicator"]["candidates"] = indicator_rows
    plan["organization_scope"]["candidates"] = organization_rows
    if plan.get("status") != "READY":
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan.get("status"), "analysis_state": json.dumps(plan, ensure_ascii=False)}

    inputs = plan.get("indicator_inputs") or []
    primary_input = inputs[0] if inputs else ""
    main_candidates = indicator_rows
    if len(inputs) > 1:
        first_matches = [row for row in indicator_rows if any(_text(value) in (_text(row.get("name")), _text(row.get("old_name")), _text(row.get("code"))) for value in [primary_input])]
        main_candidates = first_matches or indicator_rows
    chosen, candidate_pool = _choose(main_candidates, [primary_input], prefer_plant=True)
    analysis_type = _text(plan.get("analysis_type"))
    if analysis_type == "EXECUTIVE_OVERVIEW":
        chosen = None
    if not chosen and analysis_type != "EXECUTIVE_OVERVIEW":
        plan["status"] = "INDICATOR_AMBIGUOUS" if candidate_pool else "INDICATOR_NOT_FOUND"
        plan["clarification"] = {"required": True, "slot": "indicator", "candidates": candidate_pool}
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    semantic_indicators = []
    if analysis_type == "EXECUTIVE_OVERVIEW":
        for term in inputs:
            matches = [row for row in indicator_rows if _text(row.get("name")) == term or term in _text(row.get("name"))]
            item, _ = _choose(matches, [term], prefer_plant=True)
            if item:
                semantic_indicators.append(item)
    else:
        semantic_indicators = [chosen]
        if analysis_type == "DRILLDOWN":
            for row in indicator_rows:
                if row is chosen:
                    continue
                name = _text(row.get("name"))
                if any(term in name for term in ("入厂量", "入炉量", "运行时间", "停机时间")):
                    item, _ = _choose([row], [name], prefer_plant=False)
                    if item:
                        semantic_indicators.append(item)
    semantic_indicators = _unique(semantic_indicators, ["code", "name"])
    if not semantic_indicators:
        plan["status"] = "INDICATOR_NOT_FOUND"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    enriched = []
    for row in semantic_indicators:
        level, aggregation, source = _semantic(row)
        enriched.append({"code": _text(row.get("code")), "name": _text(row.get("name")), "unit": _text(row.get("unit")), "level": level, "aggregation": aggregation, "aggregation_source": source, "object_code": _text(row.get("object_code")), "object_name": _text(row.get("object_name"))})
    primary = enriched[0]
    plan["indicator"] = primary
    plan["related_indicators"] = enriched[1:]
    if not primary.get("code"):
        plan["status"] = "INDICATOR_NOT_FOUND"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
    if not primary.get("aggregation"):
        plan["status"] = "AGGREGATION_NOT_CONFIRMED"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    scope = plan.get("organization_scope", {}) or {}
    org_inputs = scope.get("inputs") or []
    candidates = organization_rows
    selected_orgs = []
    if org_inputs:
        unmatched = []
        for item in org_inputs:
            matches = [row for row in candidates if _org_match(row, item)]
            codes = list(dict.fromkeys(_text(row.get("code")) for row in matches if _text(row.get("code"))))
            if len(codes) == 1:
                selected_orgs.append(next(row for row in matches if _text(row.get("code")) == codes[0]))
            elif not matches:
                unmatched.append(item)
            else:
                plan["organization_scope"]["candidates"] = matches
                plan["organization_scope"]["clarification"] = {"required": True, "slot": "organization", "candidates": matches}
                plan["status"] = "ORGANIZATION_AMBIGUOUS"
                return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
        if unmatched:
            plan["status"] = "ORGANIZATION_NOT_FOUND"
            plan["clarification"] = {"required": True, "slot": "organization", "candidates": []}
            return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
    elif scope.get("type") in ("project_company", "region"):
        selected_orgs = candidates
        if not selected_orgs:
            plan["status"] = "ORGANIZATION_SCOPE_NOT_FOUND"
            return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    plan["organization_scope"]["codes"] = list(dict.fromkeys(_text(row.get("code")) for row in selected_orgs if _text(row.get("code"))))
    plan["organization_scope"]["names"] = [_text(row.get("name")) for row in selected_orgs if _text(row.get("name"))]
    plan["allowed_tables"] = _table_names(_text(plan.get("time", {}).get("start")), _text(plan.get("time", {}).get("end")))
    baseline_start = _text(plan.get("comparison", {}).get("baseline_start"))
    baseline_end = _text(plan.get("comparison", {}).get("baseline_end"))
    if baseline_start and baseline_end:
        plan["allowed_tables"] = list(dict.fromkeys(plan["allowed_tables"] + _table_names(baseline_start, baseline_end)))
    indicators_for_query = enriched
    query_sql, query_status = _build_data_sql(plan, indicators_for_query, selected_orgs if org_inputs else [])
    if query_status != "READY" or not query_sql:
        plan["status"] = query_status
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
    plan["status"] = "READY"
    state = {
        "analysis_type": plan.get("analysis_type"),
        "indicator_inputs": plan.get("indicator_inputs"),
        "indicator": plan.get("indicator"),
        "time": plan.get("time"),
        "dimensions": plan.get("dimensions"),
        "organization_scope": {"type": scope.get("type"), "inputs": org_inputs, "codes": plan["organization_scope"].get("codes"), "names": plan["organization_scope"].get("names"), "region_input": scope.get("region_input")},
        "sort": plan.get("sort"),
        "ranking_mode": plan.get("ranking_mode"),
        "top_n": plan.get("top_n"),
        "comparison": plan.get("comparison"),
        "current_date": plan.get("current_date"),
    }
    return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": query_sql, "can_execute": 1, "status": "READY", "analysis_state": json.dumps(state, ensure_ascii=False)}
`

  const auditCode = String.raw`
import json
import math
import re
from datetime import date

def _text(value):
    return str(value or "").strip()

def _load(value):
    if isinstance(value, dict):
        return value
    try:
        parsed = json.loads(_text(value) or "{}")
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}

def _rows(value):
    if isinstance(value, str):
        try:
            return _rows(json.loads(value))
        except Exception:
            return []
    if isinstance(value, dict):
        if isinstance(value.get("json"), (dict, list)):
            return _rows(value.get("json"))
        if isinstance(value.get("result"), list):
            return [item for item in value["result"] if isinstance(item, dict)]
        if isinstance(value.get("data"), (dict, list)):
            return _rows(value.get("data"))
        return [value] if value else []
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(_rows(item))
        return result
    return []

def _number(value):
    try:
        number = float(value)
        return int(number) if number.is_integer() else round(number, 4)
    except Exception:
        return None

def _fmt(value):
    number = _number(value)
    if number is None:
        return "-"
    return format(number, ",.2f")

def _rate(current, baseline):
    current = _number(current)
    baseline = _number(baseline)
    if current is None or baseline in (None, 0):
        return None
    return round((current - baseline) / abs(baseline) * 100, 2)

def _status_message(status, plan):
    if status == "INDICATOR_AMBIGUOUS":
        items = (plan.get("clarification", {}) or {}).get("candidates", []) or plan.get("indicator", {}).get("candidates", [])
        return "指标存在多个候选，请选择：\n" + "\n".join("- %s（Code: %s）" % (_text(item.get("name")), _text(item.get("code"))) for item in items)
    if status == "ORGANIZATION_AMBIGUOUS":
        items = (plan.get("organization_scope", {}) or {}).get("candidates", [])
        return "组织存在多个候选，请选择：\n" + "\n".join("- %s（Code: %s）" % (_text(item.get("name")), _text(item.get("code"))) for item in items)
    messages = {
        "INDICATOR_NOT_FOUND": "未找到匹配的生产指标，请换一种指标名称。",
        "ORGANIZATION_NOT_FOUND": "未找到匹配的组织，请提供更准确的组织名称。",
        "ORGANIZATION_SCOPE_NOT_FOUND": "当前组织范围没有可分析的项目公司数据。",
        "TIME_PARSE_FAILED": "无法确定查询时间范围，请补充明确的日期或月份。",
        "FUTURE_TIME": "查询时间范围包含未来日期，暂不能返回未来数据。",
        "AGGREGATION_NOT_CONFIRMED": "该指标的聚合口径尚未确认，系统未执行汇总。",
        "SQL_METADATA_LOOKUP_FAILED": "指标或组织事实查询失败，系统未执行后续分析。",
        "SQL_EXECUTION_FAILED": "事实查询执行失败，系统未展示未经校验的数据。",
        "RESULT_VALIDATION_FAILED": "查询结果未通过数据质量校验，系统未展示未经确认的数据。",
        "NO_DATA_IN_PERIOD": "指定时间范围内没有查到数据。",
    }
    return messages.get(status, "")

def _clean_rows(rows):
    cleaned = []
    for row in rows:
        value = _number(row.get("value"))
        cleaned.append({
            "period_set": _text(row.get("period_set")) or "current",
            "period": _text(row.get("period")),
            "indicator_code": _text(row.get("indicator_code") or row.get("metric_code")),
            "indicator_name": _text(row.get("indicator_name")),
            "org_code": _text(row.get("org_code")),
            "org_name": _text(row.get("org_name")) or "全部组织",
            "value": value,
            "row_count": int(_number(row.get("row_count")) or 0),
            "dimension_count": int(_number(row.get("dimension_count")) or 0),
            "invalid_value_count": int(_number(row.get("invalid_value_count")) or 0),
            "duplicate_key_groups": int(_number(row.get("duplicate_key_groups")) or 0),
            "data_cutoff_date": _text(row.get("data_cutoff_date"))[:10],
        })
    return cleaned

def _group(rows, keys):
    result = {}
    for row in rows:
        key = tuple(row.get(key) for key in keys)
        result.setdefault(key, []).append(row)
    return result

def _total_by_org(rows, period_set="current"):
    result = {}
    for row in rows:
        if row.get("period_set") != period_set or row.get("value") is None:
            continue
        key = (row.get("org_code") or "", row.get("org_name") or "全部组织", row.get("indicator_code"), row.get("indicator_name"))
        result[key] = result.get(key, 0) + row.get("value")
    return result

def _trend_insights(rows, plan):
    insights = []
    groups = _group([row for row in rows if row.get("period_set") == "current" and row.get("value") is not None], ["org_code", "indicator_code"])
    for key, series in groups.items():
        series = sorted(series, key=lambda row: row.get("period") or "")
        if len(series) < 2:
            continue
        first, last = series[0].get("value"), series[-1].get("value")
        change = _rate(last, first)
        label = series[-1].get("org_name") or "全部组织"
        if change is not None:
            insights.append("%s从%s变为%s，首末周期变化%s%%。" % (label, _fmt(first), _fmt(last), _fmt(change)))
        if len(series) >= 3:
            drops = all(series[index].get("value") is not None and series[index + 1].get("value") is not None and series[index + 1]["value"] < series[index]["value"] for index in range(len(series) - 1))
            if drops:
                insights.append("%s在当前周期内连续下降，建议继续核查相关运行指标。" % label)
    return insights[:8]

def _anomalies(rows):
    anomalies = []
    groups = _group([row for row in rows if row.get("period_set") == "current" and row.get("period") != "total"], ["org_code", "indicator_code"])
    for key, series in groups.items():
        series = sorted(series, key=lambda row: row.get("period") or "")
        consecutive = 0
        for index in range(1, len(series)):
            previous, current = series[index - 1], series[index]
            change = _rate(current.get("value"), previous.get("value"))
            if change is None:
                continue
            if change <= -20:
                consecutive += 1
                anomalies.append({"anomaly_type": "MOM_DROP", "threshold": -20, "actual_value": current.get("value"), "baseline_value": previous.get("value"), "evidence": "%s较%s下降%s%%" % (current.get("period"), previous.get("period"), abs(change)), "org_code": current.get("org_code"), "org_name": current.get("org_name"), "indicator_name": current.get("indicator_name"), "period": current.get("period")})
            elif change >= 20:
                consecutive = 0
                anomalies.append({"anomaly_type": "MOM_RISE", "threshold": 20, "actual_value": current.get("value"), "baseline_value": previous.get("value"), "evidence": "%s较%s上升%s%%" % (current.get("period"), previous.get("period"), change), "org_code": current.get("org_code"), "org_name": current.get("org_name"), "indicator_name": current.get("indicator_name"), "period": current.get("period")})
            else:
                consecutive = 0
            if consecutive >= 3:
                anomalies.append({"anomaly_type": "CONSECUTIVE_DECREASE", "threshold": 3, "actual_value": current.get("value"), "baseline_value": previous.get("value"), "evidence": "%s已连续至少3个周期下降" % current.get("org_name"), "org_code": current.get("org_code"), "org_name": current.get("org_name"), "indicator_name": current.get("indicator_name"), "period": current.get("period")})
    return anomalies[:80]

def _ranking(rows, plan):
    current = _total_by_org(rows, "current")
    baseline = _total_by_org(rows, "baseline")
    values = []
    for key, value in current.items():
        org_code, org_name, indicator_code, indicator_name = key
        base_value = None
        for base_key, candidate in baseline.items():
            if base_key[0] == org_code and base_key[2] == indicator_code:
                base_value = candidate
                break
        values.append({"org_code": org_code, "org_name": org_name, "indicator_code": indicator_code, "indicator_name": indicator_name, "value": value, "baseline_value": base_value, "change_rate": _rate(value, base_value), "rank": 0, "baseline_rank": 0})
    sort_field = "change_rate" if plan.get("analysis_type") == "RANKING_COMPARISON" else "value"
    if sort_field == "change_rate":
        values = [row for row in values if row.get("change_rate") is not None]
    values.sort(key=lambda row: (row.get(sort_field) is not None, row.get(sort_field) or 0), reverse=plan.get("ranking_mode") != "bottom")
    for index, row in enumerate(values, 1):
        row["rank"] = index
    if baseline:
        old = sorted([row for row in baseline.items() if row[0][2] == plan.get("indicator", {}).get("code")], key=lambda item: item[1], reverse=True)
        old_rank = {item[0][0]: index for index, item in enumerate(old, 1)}
        for row in values:
            row["baseline_rank"] = old_rank.get(row.get("org_code"), 0)
            if row["baseline_rank"]:
                row["rank_change"] = row["baseline_rank"] - row["rank"]
    mode = plan.get("ranking_mode")
    top_n = int(plan.get("top_n") or 10)
    if mode == "both":
        return values[:top_n] + values[-top_n:]
    return values[:top_n]

def _chart(rows, ranking, plan):
    analysis_type = plan.get("analysis_type")
    if ranking:
        return {"title": {"text": "项目公司排名"}, "tooltip": {"trigger": "axis"}, "xAxis": {"type": "value"}, "yAxis": {"type": "category", "data": [row.get("org_name") for row in ranking]}, "series": [{"type": "bar", "data": [row.get("change_rate") if analysis_type == "RANKING_COMPARISON" else row.get("value") for row in ranking]}]}
    current = [row for row in rows if row.get("period_set") == "current" and row.get("period")]
    if not current:
        return None
    groups = _group(current, ["indicator_code", "org_code"])
    series = []
    for key, items in list(groups.items())[:8]:
        items = sorted(items, key=lambda row: row.get("period") or "")
        series.append({"name": items[0].get("indicator_name") or items[0].get("org_name"), "type": "line", "data": [row.get("value") for row in items]})
    periods = sorted(set(row.get("period") for row in current))
    return {"title": {"text": _text(plan.get("indicator", {}).get("name")) or "经营指标趋势"}, "tooltip": {"trigger": "axis"}, "legend": {"data": [item.get("name") for item in series]}, "xAxis": {"type": "category", "data": periods}, "yAxis": {"type": "value"}, "series": series}

def _followups(plan, ranking, anomalies):
    kind = _text(plan.get("analysis_type"))
    if kind in ("RANKING", "RANKING_COMPARISON"):
        return ["只看后5名", "和去年相比", "查看排名变化最大的公司"]
    if kind == "TREND":
        return ["做同比分析", "查看异常变化", "为什么会下降"]
    if kind == "ANOMALY":
        return ["查看异常公司的趋势", "和去年相比", "继续下钻原因"]
    if kind == "DRILLDOWN":
        return ["查看相关指标趋势", "和去年相比", "查看异常月份"]
    if kind == "COMPARISON":
        return ["按同比排序", "查看各公司趋势", "只看差距最大的对象"]
    return ["查看月度趋势", "做同比分析", "查看项目公司排名"]

def main(analysis_plan_json: str, business_status: str, execution_data, execution_error: str) -> dict:
    plan = _load(analysis_plan_json)
    status = _text(business_status or plan.get("status") or "RESULT_VALIDATION_FAILED")
    error = _text(execution_error)
    rows = _clean_rows(_rows(execution_data))
    warnings = []
    if status != "READY":
        final_status = status
        rows = []
    elif error:
        final_status = "SQL_EXECUTION_FAILED"
        rows = []
    elif not rows:
        final_status = "NO_DATA_IN_PERIOD"
    elif any(row.get("invalid_value_count", 0) > 0 or row.get("duplicate_key_groups", 0) > 0 for row in rows):
        final_status = "RESULT_VALIDATION_FAILED"
        warnings.append("存在非法数值或重复业务键，未压平或猜选数据维度")
    else:
        final_status = "SUCCESS_WITH_DATA"

    ranking = _ranking(rows, plan) if final_status == "SUCCESS_WITH_DATA" and plan.get("analysis_type") in ("RANKING", "RANKING_COMPARISON") else []
    anomalies = _anomalies(rows) if final_status == "SUCCESS_WITH_DATA" and plan.get("analysis_type") in ("ANOMALY", "EXECUTIVE_OVERVIEW", "TREND", "DRILLDOWN") else []
    insights = []
    if final_status == "SUCCESS_WITH_DATA":
        if ranking:
            if ranking:
                first, last = ranking[0], ranking[-1]
                if plan.get("analysis_type") == "RANKING_COMPARISON":
                    insights.append("按同比变化排序，%s变化%s%%，当前排第%s。" % (_text(first.get("org_name")), _fmt(first.get("change_rate")), first.get("rank")))
                else:
                    insights.append("共统计%s个对象，%s以%s位居当前榜首，末位为%s。" % (len(_total_by_org(rows, "current")), _text(first.get("org_name")), _fmt(first.get("value")), _text(last.get("org_name"))))
            if plan.get("comparison", {}).get("type") == "YOY":
                movers = sorted([row for row in ranking if row.get("change_rate") is not None], key=lambda row: abs(row.get("change_rate")), reverse=True)
                if movers:
                    insights.append("同比变化最大的是%s，变化%s%%；这是数据对比结果，不等同于因果结论。" % (_text(movers[0].get("org_name")), _fmt(movers[0].get("change_rate"))))
        else:
            insights.extend(_trend_insights(rows, plan))
        if anomalies:
            insights.append("按月环比绝对变化20%%和连续3期下降规则，共识别%s条需要关注的异常线索。" % len(anomalies))
        elif plan.get("analysis_type") in ("ANOMALY", "EXECUTIVE_OVERVIEW"):
            insights.append("按当前启用的环比20%%阈值和连续3期下降规则，暂未发现达到规则的异常线索。")
        if plan.get("analysis_type") == "DRILLDOWN":
            insights.append("现有数据只能说明指标与相关指标同期变化是否同时发生，不能仅凭相关性确认因果；建议按异常月份继续核查停机和运行记录。")
    if final_status == "SUCCESS_WITH_DATA" and not insights:
        insights.append("结果已通过事实、时间、指标和数据质量校验。")

    cutoff = ""
    for row in rows:
        if row.get("data_cutoff_date") > cutoff:
            cutoff = row.get("data_cutoff_date")
    audit = {
        "status": final_status,
        "analysis_plan": plan,
        "query_context": {"analysis_type": plan.get("analysis_type"), "indicator": plan.get("indicator"), "organization_scope": plan.get("organization_scope"), "time": plan.get("time"), "dimensions": plan.get("dimensions"), "filters": plan.get("filters"), "comparison": plan.get("comparison")},
        "metric": plan.get("indicator"),
        "organization_scope": plan.get("organization_scope"),
        "time_range": plan.get("time"),
        "dimensions": plan.get("dimensions"),
        "filters": plan.get("filters"),
        "aggregation": plan.get("indicator", {}).get("aggregation"),
        "comparison": plan.get("comparison"),
        "rows": rows[:1000],
        "statistics": {"row_count": len(rows), "total": round(sum(row.get("value") or 0 for row in rows if row.get("period") == "total" and row.get("period_set") == "current"), 4), "cutoff": cutoff},
        "ranking": ranking,
        "anomalies": anomalies,
        "insights_evidence": [{"type": "deterministic_rule", "text": text} for text in insights],
        "data_cutoff_date": cutoff,
        "warnings": warnings,
        "validation": {"status": final_status, "execution_error": error, "source_tables": plan.get("allowed_tables", []), "duplicate_key_policy": "检测到重复键时阻断，不使用 DISTINCT 掩盖"},
        "source_tables": plan.get("allowed_tables", []),
    }
    if final_status != "SUCCESS_WITH_DATA":
        visible = _status_message(final_status, plan) or "查询未返回可信结果，系统已阻止展示未经校验的数据。"
        chart = None
        followups = []
    else:
        indicator_name = _text(plan.get("indicator", {}).get("name")) or "生产指标"
        start = _text(plan.get("time", {}).get("start"))
        end = _text(plan.get("time", {}).get("end"))
        visible_lines = []
        if ranking:
            if plan.get("analysis_type") == "RANKING_COMPARISON":
                visible_lines.append("%s按同比变化排名结果如下。" % indicator_name)
            else:
                visible_lines.append("%s项目公司排名结果如下。" % indicator_name)
            visible_lines.extend(["", "| 排名 | 公司 | 当前值 | 同比变化 |", "| ---: | --- | ---: | ---: |"])
            for row in ranking:
                change = ("%s%%" % _fmt(row.get("change_rate"))) if row.get("change_rate") is not None else "-"
                visible_lines.append("| %s | %s | %s | %s |" % (row.get("rank"), _text(row.get("org_name")), _fmt(row.get("value")), change))
        else:
            total_rows = [row for row in rows if row.get("period_set") == "current"]
            visible_lines.append("%s查询完成，当前时间范围为%s至%s。" % (indicator_name, start, end))
            if total_rows and all(row.get("period") == "total" for row in total_rows):
                visible_lines.append("核心结果：%s。" % _fmt(total_rows[0].get("value")))
            if total_rows and any(row.get("period") != "total" for row in total_rows):
                visible_lines.extend(["", "| 周期 | 对象 | 数值 | 原始行数 |", "| --- | --- | ---: | ---: |"])
                for row in total_rows[:200]:
                    visible_lines.append("| %s | %s | %s | %s |" % (_text(row.get("period")), _text(row.get("org_name")), _fmt(row.get("value")), row.get("row_count")))
        visible_lines.extend(["", "## 关键发现"])
        visible_lines.extend("- " + item for item in insights[:8])
        visible_lines.extend(["", "## 数据说明", "统计范围：%s 至 %s（结束日期不含）；数据更新至：%s。" % (start, end, cutoff or "未取得"), "需要技术细节时，可查看本次结果的审计信息。"])
        visible = "\n".join(visible_lines)
        chart = _chart(rows, ranking, plan)
        followups = _followups(plan, ranking, anomalies)
    state = {"analysis_type": plan.get("analysis_type"), "indicator_inputs": plan.get("indicator_inputs"), "indicator": plan.get("indicator"), "time": plan.get("time"), "dimensions": plan.get("dimensions"), "organization_scope": {"type": plan.get("organization_scope", {}).get("type"), "inputs": plan.get("organization_scope", {}).get("inputs"), "codes": plan.get("organization_scope", {}).get("codes"), "names": plan.get("organization_scope", {}).get("names"), "region_input": plan.get("organization_scope", {}).get("region_input")}, "sort": plan.get("sort"), "ranking_mode": plan.get("ranking_mode"), "top_n": plan.get("top_n"), "comparison": plan.get("comparison"), "current_date": plan.get("current_date")}
    marker_state = "<!--HUANBAO_ANALYSIS_STATE:" + json.dumps(state, ensure_ascii=False, separators=(",", ":")) + "-->"
    marker_chart = "<!--HUANBAO_ANALYSIS_CHART:" + json.dumps(chart, ensure_ascii=False, separators=(",", ":")) + "-->" if chart else ""
    marker_followups = "<!--HUANBAO_ANALYSIS_FOLLOWUPS:" + json.dumps(followups, ensure_ascii=False, separators=(",", ":")) + "-->" if followups else ""
    result_text = visible + "\n\n" + marker_state + ("\n" + marker_chart if marker_chart else "") + ("\n" + marker_followups if marker_followups else "")
    audit["visible_answer"] = visible
    audit["follow_ups"] = followups
    audit["chart"] = chart
    return {"result_text": result_text, "audit_json": json.dumps(audit, ensure_ascii=False), "status": final_status, "analysis_state": json.dumps(state, ensure_ascii=False), "follow_ups": json.dumps(followups, ensure_ascii=False), "chart_option": json.dumps(chart, ensure_ascii=False) if chart else ""}
`

  const intentSystem = `你只负责理解当前问题和上一轮结构化分析状态，输出一个 JSON 对象。你可以识别分析意图、指标名称、组织名称列表、区域范围、时间表达、排序和 TopN，但不能查询数据库、生成 SQL、猜组织 Code、猜指标 Code、猜公式或猜数字。当前问题可能是对上一轮的补充，例如只看前5、和去年相比、秦皇岛排多少。字段固定：analysis_type、indicator_inputs、organization_inputs、region_input、date_expression、granularity、top_n、sort_field、sort_direction、request_type。analysis_type 可为 FACT、TREND、RANKING、COMPARISON、RANKING_COMPARISON、DISTRIBUTION、ANOMALY、CORRELATION、DRILLDOWN、EXECUTIVE_OVERVIEW。只输出 JSON。`
  const intentUser = `上一轮分析状态（可能为空）：\n{{#1780919457192.analysis_state#}}\n\n当前用户问题：\n{{#sys.query#}}\n\n只输出 JSON 对象，不要 Markdown，不要解释。`

  const start = wrapper(startTemplate, '1780919457192', startTemplate.data, 0, 0, 90)
  const startData = cleanData(startTemplate.data)
  startData.type = 'start'
  startData.title = '用户问题'
  startData.variables = [{
    variable: 'analysis_state',
    label: '上一轮分析状态',
    type: 'paragraph',
    max_length: 12000,
    options: [],
    required: false,
  }]
  start.data = startData
  const intent = llmNode('analysis_intent', '业务意图与分析计划草拟', intentSystem, intentUser, 300, 0)
  const plan = codeNode('analysis_plan', '确定性分析计划', planCode, [
    { variable: 'intent_text', value_selector: ['analysis_intent', 'text'], value_type: 'string' },
    { variable: 'question', value_selector: ['sys', 'query'], value_type: 'string' },
    { variable: 'previous_state', value_selector: ['1780919457192', 'analysis_state'], value_type: 'string' },
  ], {
    analysis_plan: { children: null, type: 'string' },
    indicator_lookup_sql: { children: null, type: 'string' },
    organization_lookup_sql: { children: null, type: 'string' },
    status: { children: null, type: 'string' },
  }, 600, 0)
  const indicatorLookup = toolNode('analysis_indicator_lookup', '查询指标语义层', ['analysis_plan', 'indicator_lookup_sql'], 900, -150)
  const organizationLookup = toolNode('analysis_organization_lookup', '查询组织语义层', ['analysis_plan', 'organization_lookup_sql'], 900, 150)
  const resolve = codeNode('analysis_resolve', '事实解析与查询计划', resolveCode, [
    { variable: 'analysis_plan', value_selector: ['analysis_plan', 'analysis_plan'], value_type: 'string' },
    { variable: 'indicator_data', value_selector: ['analysis_indicator_lookup', 'json'], value_type: 'object' },
    { variable: 'indicator_error', value_selector: ['analysis_indicator_lookup', 'error_message'], value_type: 'string' },
    { variable: 'organization_data', value_selector: ['analysis_organization_lookup', 'json'], value_type: 'object' },
    { variable: 'organization_error', value_selector: ['analysis_organization_lookup', 'error_message'], value_type: 'string' },
  ], {
    analysis_plan_json: { children: null, type: 'string' },
    query_sql: { children: null, type: 'string' },
    can_execute: { children: null, type: 'number' },
    status: { children: null, type: 'string' },
    analysis_state: { children: null, type: 'string' },
  }, 1200, 0)
  const gateData = cleanData(ifTemplate.data)
  gateData.type = 'if-else'
  gateData.title = '分析计划校验是否通过'
  gateData.cases = [{
    case_id: 'true',
    id: 'analysis-plan-validated',
    logical_operator: 'and',
    conditions: [{ comparison_operator: '>', id: 'analysis-condition', value: '0', variable_selector: ['analysis_resolve', 'can_execute'] }],
  }]
  const gate = wrapper(ifTemplate, 'analysis_gate', gateData, 1500, 0, 80)
  const execute = toolNode('analysis_execute', '执行确定性事实查询', ['analysis_resolve', 'query_sql'], 1800, -100)
  const audit = codeNode('analysis_audit', '统计分析与结果审计', auditCode, [
    { variable: 'analysis_plan_json', value_selector: ['analysis_resolve', 'analysis_plan_json'], value_type: 'string' },
    { variable: 'business_status', value_selector: ['analysis_resolve', 'status'], value_type: 'string' },
    { variable: 'execution_data', value_selector: ['analysis_execute', 'json'], value_type: 'object' },
    { variable: 'execution_error', value_selector: ['analysis_execute', 'error_message'], value_type: 'string' },
  ], {
    result_text: { children: null, type: 'string' },
    audit_json: { children: null, type: 'string' },
    status: { children: null, type: 'string' },
    analysis_state: { children: null, type: 'string' },
    follow_ups: { children: null, type: 'string' },
    chart_option: { children: null, type: 'string' },
  }, 2100, 0)
  const finalData = cleanData(answerTemplate.data)
  finalData.type = 'answer'
  finalData.title = '输出经营分析结果'
  finalData.answer = '{{#analysis_audit.result_text#}}'
  const finalAnswer = wrapper(answerTemplate, 'final_answer', finalData, 2400, 0, 102)

  const newNodes = [start, intent, plan, indicatorLookup, organizationLookup, resolve, gate, execute, audit, finalAnswer]
  const newEdges = [
    edge('1780919457192', 'analysis_intent', 'start', 'llm'),
    edge('analysis_intent', 'analysis_plan', 'llm', 'code'),
    edge('analysis_plan', 'analysis_indicator_lookup', 'code', 'tool'),
    edge('analysis_plan', 'analysis_organization_lookup', 'code', 'tool'),
    edge('analysis_plan', 'analysis_resolve', 'code', 'code'),
    edge('analysis_indicator_lookup', 'analysis_resolve', 'tool', 'code'),
    edge('analysis_indicator_lookup', 'analysis_resolve', 'tool', 'code', 'fail-branch'),
    edge('analysis_organization_lookup', 'analysis_resolve', 'tool', 'code'),
    edge('analysis_organization_lookup', 'analysis_resolve', 'tool', 'code', 'fail-branch'),
    edge('analysis_resolve', 'analysis_gate', 'code', 'if-else'),
    edge('analysis_gate', 'analysis_execute', 'if-else', 'tool', 'true'),
    edge('analysis_gate', 'analysis_audit', 'if-else', 'code', 'false'),
    edge('analysis_execute', 'analysis_audit', 'tool', 'code'),
    edge('analysis_execute', 'analysis_audit', 'tool', 'code', 'fail-branch'),
    edge('analysis_audit', 'final_answer', 'code', 'answer'),
  ]
  const draft = deepCopy(original)
  draft.graph.nodes = newNodes
  draft.graph.edges = newEdges
  draft.graph.viewport = { x: 0, y: 0, zoom: 0.75 }
  const saved = await saveDraft(draft)
  if (saved.status !== 200) throw new Error(`保存企业分析草稿失败: ${saved.status}`)
  return {
    status: saved.status,
    nodeCount: newNodes.length,
    edgeCount: newEdges.length,
    titles: newNodes.map(node => node.data?.title).filter(Boolean),
    hashPresent: !!saved.body?.hash,
  }
}

run()
