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
  const toolNode = (id, title, sqlSelector, x, y, retryConfig = { max_retries: 1, retry_enabled: true, retry_interval: 1000 }) => {
    const data = cleanData(toolTemplate.data)
    data.type = 'tool'
    data.title = title
    data.error_strategy = 'fail-branch'
    data.retry_config = retryConfig
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
    if "今年" in text and "去年" in text:
        return date(today.year, 1, 1), tomorrow, "month", "current_year_to_date"
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
    # Hard business cues win over an occasionally over-broad model label.
    # The model can still provide types such as CORRELATION when the user
    # only says “compare”, but it must not turn anomaly/diagnosis language
    # into a plain trend query.
    if re.search(r"生产经营|经营情况|值得关注|整体表现", text):
        detected = "EXECUTIVE_OVERVIEW"
    elif re.search(r"为什么|原因|下钻|怎么回事|为何|下降原因", text):
        detected = "DRILLDOWN"
    elif re.search(r"异常|不正常|连续[三四五六七八九十0-9]+个月|连续[三四五六七八九十0-9]+期", text):
        detected = "ANOMALY"
    elif "同比" in text and re.search(r"排名|最高|最低|增幅|下降最多", text):
        detected = "RANKING_COMPARISON"
    elif re.search(r"排名|排行|最高|最低|前\d+|后\d+|Top|Bottom", text, flags=re.I):
        detected = "RANKING"
    elif explicit in ("CORRELATION", "DRILLDOWN", "DISTRIBUTION", "RANKING_COMPARISON"):
        detected = explicit
    elif "同比" in text or "环比" in text or re.search(r"比较|对比|哪个好|分别", text):
        detected = "COMPARISON"
    elif re.search(r"趋势|走势|变化|各月|每月|逐月|每天|日度", text):
        detected = "TREND"
    elif explicit in allowed and explicit != "FACT":
        detected = explicit
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
    value = re.sub(r"^[\"'“”‘’]+", "", value)
    value = re.sub(r"^(?:请|帮我|帮忙|查询|查看|统计|分析|比较|对比|了解|算一下)+", "", value)
    value = re.sub(r"^(?:今年|本年|去年|上年|本月|上月|本季度|近\d+个?月|近\d+天|近\d+年)+", "", value)
    value = re.sub(r"^(?:组织|公司)\s*", "", value)
    value = re.sub(r"(?:的)?(?:项目公司|各公司|每家公司|所有公司)$", "", value)
    if _compact(value) in ("", "哪些", "哪些公司", "哪些项目公司", "各", "各公司", "各项目公司", "每", "每家公司", "每个公司", "每个项目公司", "所有", "所有公司", "所有项目公司", "公司", "项目", "项目公司"):
        return ""
    return _text(value)

def _question_indicator_fallback(question):
    value = _text(question)
    if "的" in value:
        value = value.rsplit("的", 1)[-1]
    value = re.sub(r"^(?:请|帮我|帮忙|查询|查看|统计|分析|比较|对比|了解|算一下)+", "", value)
    value = re.sub(r"\d{4}年(?:\d{1,2}月)?", "", value)
    value = re.sub(r"(?:组织\s*[A-Za-z0-9_-]+|公司\s*[A-Za-z0-9_-]+)", "", value)
    value = re.sub(r"(?:今年|本年|去年|上年|本月|上月|本季度|近\d+个?月|近\d+天|近\d+年)", "", value)
    value = re.sub(r"(?:各|每|所有)?项目公司|(?:各公司|每家公司|所有公司)", "", value)
    value = re.sub(r"(?:排名|排行)\s*(?:前|后|Top|Bottom)?\s*\d*", "", value, flags=re.I)
    value = re.sub(r"(?:前|后|Top|Bottom)\s*\d+", "", value, flags=re.I)
    value = re.sub(r"(?:是多少|怎么样|趋势|排名|排行|最高|最低|同比|环比|下降原因|上升原因|下降|上升|变化|情况|表现)$", "", value)
    value = value.strip(" ：:，。！？,!?的")
    return value if len(value) >= 2 else ""

def _org_inputs(intent, previous, question):
    candidates = intent.get("organization_inputs") or intent.get("organizations") or intent.get("organization_names")
    if isinstance(candidates, str):
        candidates = re.split(r"[、,，和与及]", candidates)
    normalized_candidates = []
    for item in candidates or []:
        if isinstance(item, dict):
            item = item.get("organization_code") or item.get("org_code") or item.get("organization_name") or item.get("name") or item.get("code") or item.get("organization") or ""
        cleaned = _clean_org_candidate(item)
        if cleaned:
            normalized_candidates.append(cleaned)
    candidates = normalized_candidates
    single = _text(intent.get("organization_input") or intent.get("organization") or intent.get("org_input"))
    if single and not candidates:
        candidates = [_clean_org_candidate(item) for item in re.split(r"[、,，和与及]", single) if _clean_org_candidate(item)]
    if not candidates:
        previous_scope = (previous or {}).get("organization_scope") or {}
        candidates = list((previous or {}).get("organization_inputs") or previous_scope.get("inputs") or [])

    # The model extracts organization names; these regexes only distinguish
    # organization scope words from metric text and never choose a code.
    if not candidates:
        codes = re.findall(r"(?:组织|公司)?\s*(\d{5,})", question)
        candidates = list(dict.fromkeys(codes))
    if not candidates:
        match = re.search(r"([^，。！？,!?]{1,40}(?:公司|有限公司|本部))", question)
        if match:
            candidate = _clean_org_candidate(match.group(1))
            if candidate:
                candidates = [candidate]
    return list(dict.fromkeys(candidates))[:12]

def _region_input(intent, question, previous):
    previous_scope = (previous or {}).get("organization_scope") or {}
    value = _text(intent.get("region_input") or intent.get("region") or (previous or {}).get("region_input") or previous_scope.get("region_input"))
    if value:
        return value
    match = re.search(r"(华东|华北|华南|华中|西南|西北|东北|鲁北|北方|南方|雄安|山东|中西部)(?:大区|地区|区域)?", question)
    return _text(match.group(1)) if match else ""

def _indicator_inputs(intent, previous, question, analysis_type):
    values = intent.get("indicator_inputs") or intent.get("indicators")
    if isinstance(values, str):
        values = [values]
    normalized_values = []
    for item in values or []:
        if isinstance(item, dict):
            item = item.get("indicator_code") or item.get("metric_code") or item.get("indicator_name") or item.get("name") or item.get("code") or item.get("indicator") or ""
        item = _text(item)
        if item:
            normalized_values.append(item)
    values = normalized_values
    primary = _text(intent.get("indicator_input") or intent.get("indicator") or intent.get("metric"))
    if primary and not values:
        values = [primary]
    if not values:
        values = list((previous or {}).get("indicator_inputs") or [])
    fallback = _question_indicator_fallback(question)
    if not values:
        # This is a semantic default for open-ended operating review, not a
        # test-case answer or a database code.
        if analysis_type == "EXECUTIVE_OVERVIEW":
            values = ["生活垃圾入厂量", "全厂发电量", "全厂上网电量"]
        elif fallback:
            values = [fallback]
        else:
            match = re.search(r"(?:查询|查看|比较|对比|按|哪个|哪些|今年|去年|本月|近\d+个?月)?([^，。！？,!?]{2,30}?)(?:是多少|怎么样|趋势|排名|最高|最低|同比|环比|下降|上升|变化|的)", question)
            if match:
                values = [_text(match.group(1))]
    # Small models sometimes return only the time and organization prefix for
    # a sentence such as “查询2024年组织10004024全厂发电量”. Prefer the
    # deterministic question suffix in that narrow case.
    if fallback and values and all(
        re.search(r"\d{4}年", _text(item)) or re.search(r"(?:组织|公司)\s*[A-Za-z0-9_-]+", _text(item))
        for item in values
    ):
        values = [fallback]
    cleaned = []
    for value in values:
        value = re.sub(r"^(?:请|帮我|查询|查看|统计|分析|比较|对比|了解|算一下)+", "", value)
        value = re.sub(r"^(?:今年|本年|去年|上年|本月|上月|本季度|近\d+个?月|近\d+天|近\d+年)+", "", value)
        value = re.sub(r"(?:各|每|所有)?项目公司", "", value)
        value = re.sub(r"(?:各公司|每家公司|所有公司)$", "", value)
        value = re.sub(r"^(?:组织\s*[A-Za-z0-9_-]+|公司\s*[A-Za-z0-9_-]+)的", "", value)
        value = re.sub(r"^(?:组织|公司)\s*[A-Za-z0-9_-]+$", "", value)
        value = _text(value)
        if value and value not in ("项目公司", "各公司", "每家公司", "所有公司"):
            cleaned.append(value)
    if not cleaned:
        fallback = _question_indicator_fallback(question)
        if fallback:
            cleaned.append(fallback)
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
        if org_terms and all(re.match(r"^\d{5,}$", _text(item)) for item in org_inputs):
            org_where = '"Code" IN (' + ','.join(_literal(item) for item in org_inputs) + ')'
        else:
            org_where = _like(['"Code"', '"Name_CHS"', '"Abbreviation_CHS"', '"FullPathName_CHS"'], list(dict.fromkeys(org_terms)))
    elif region_input:
        org_where = (
            '"State_IsEnabled" = \'1\' AND '
            'COALESCE("TreeInfo_IsDetail", \'0\') = \'0\' AND '
            '(' + _like(['"Name_CHS"', '"Abbreviation_CHS"', '"FullPathName_CHS"'], _terms(region_input)) + ')'
        )
    elif scoped:
        org_where = (
            '"State_IsEnabled" = \'1\' AND '
            'COALESCE("TreeInfo_IsDetail", \'0\') = \'0\' AND '
            '("Name_CHS" ILIKE \'%公司%\' OR "FullPathName_CHS" ILIKE \'%有限公司%\')'
        )
    else:
        org_where = '1=0'
    organization_sql = 'SELECT ' + org_columns + ' FROM MSOKFPT."BFAdminOrganization" WHERE ' + org_where + ' ORDER BY "Name_CHS" LIMIT 300'
    return indicator_sql, organization_sql

def main(intent_text: str, question: str, previous_state: str = "", authorization_json: str = "", clarification_json: str = "") -> dict:
    intent = _load(intent_text)
    previous = _load(previous_state)
    authorization = _load(authorization_json)
    clarification = _load(clarification_json)
    selected_value = _text(clarification.get("selectedValue") or clarification.get("selected_value"))
    selected_label = _text(clarification.get("selectedLabel") or clarification.get("selected_label"))
    selected = selected_value or selected_label
    clarification_slot = _text(clarification.get("slot"))
    if selected:
        previous = dict(previous or {})
        if clarification_slot == "indicator":
            previous["indicator_inputs"] = [selected]
            previous["indicator"] = {**(previous.get("indicator") or {}), "code": selected_value, "name": selected_label or selected}
        elif clarification_slot == "organization":
            scope = dict(previous.get("organization_scope") or {})
            scope["inputs"] = [selected]
            previous["organization_scope"] = scope
            previous["organization_inputs"] = [selected]
    original_question = _text(question)
    if selected and selected not in original_question:
        original_question = (original_question + " " + selected_label).strip()
    today = date.today()
    next_month = _add_months(today.replace(day=1), 1)
    start, end, granularity, time_source = _date_range(original_question, today)
    previous_time = (previous or {}).get("time") or {}
    if time_source == "default_current_month" and previous_time.get("start") and previous_time.get("end"):
        try:
            previous_start = date.fromisoformat(_text(previous_time.get("start")))
            previous_end = date.fromisoformat(_text(previous_time.get("end")))
            if previous_start < previous_end:
                start, end = previous_start, previous_end
                granularity = _text(previous_time.get("granularity")) or "month"
                time_source = "previous_state"
        except Exception:
            pass
    status = "READY"
    if start >= end or time_source == "invalid_date":
        status = "TIME_PARSE_FAILED"
    elif start > today:
        status = "FUTURE_TIME"

    analysis_type = _analysis_type(original_question, intent, previous)
    indicator_inputs = _indicator_inputs(intent, previous, original_question, analysis_type)
    org_inputs = _org_inputs(intent, previous, original_question)
    region_input = _region_input(intent, original_question, previous)
    if analysis_type in ("ANOMALY", "DRILLDOWN", "EXECUTIVE_OVERVIEW") and time_source == "default_current_month":
        # Open-ended diagnosis needs enough history to establish a baseline;
        # this remains deterministic and is still bounded to six months.
        start = _add_months(today.replace(day=1), -5)
        end = next_month
        granularity = "month"
        time_source = "analysis_default_recent_six_months"
    top_n = _top_n(original_question, intent, previous)
    text = _compact(original_question)
    time_series_requested = bool(re.search(r"趋势|走势|变化|各月|每月|逐月|每天|日度|连续", text))
    ranking_mode = "bottom" if re.search(r"最低|最少|最差|后\d+|Bottom", text, flags=re.I) else "top"
    if "最高和最低" in text or "最高最低" in text:
        ranking_mode = "both"
    anomaly_focus = "CONSECUTIVE_DECREASE" if re.search(r"连续.*?(下降|减少|下滑)", text) else ("MOM_DROP" if re.search(r"异常.*?(下降|减少|下滑)|下降.*?异常", text) else "THRESHOLD")
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
        "authorization": {
            "provided": bool(authorization),
            "allow_group_ranking": bool(authorization.get("allowGroupRanking") or authorization.get("allow_group_ranking")),
            "allow_all_organizations": bool(authorization.get("allowAllOrganizations") or authorization.get("allow_all_organizations")),
            "allowed_org_codes": authorization.get("allowedOrgCodes") or authorization.get("allowed_org_codes") or [],
            "allowed_indicator_codes": authorization.get("allowedIndicatorCodes") or authorization.get("allowed_indicator_codes") or [],
        },
        "sort": {"field": "change_rate" if analysis_type == "RANKING_COMPARISON" else "value", "direction": "asc" if ranking_mode == "bottom" else "desc"},
        "ranking_mode": ranking_mode,
        "anomaly_focus": anomaly_focus,
        "top_n": top_n,
        "comparison": comparison,
        "analysis_actions": [analysis_type.lower()],
        "chart_preference": "bar" if "RANKING" in analysis_type or analysis_type in ("COMPARISON", "DISTRIBUTION") else "line",
        "time_series_requested": time_series_requested,
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
    # The dictionary is the source of identity; this small semantic adapter
    # only supplies safe capabilities that can be proved from the measure
    # shape.  Rates, ratios and unregistered calculated indicators are never
    # silently summed.
    additive = bool(re.search(r"量|发电|供汽|供热|产渣|耗量|消耗|运行时间|停机时间", name))
    if level == "rate" or not additive:
        aggregation = ""
        source = "business_rule_required"
    else:
        aggregation = "SUM"
        source = "semantic_measurement_registry_v1"
    subject = "发电" if re.search(r"发电|上网|下网|电量", name) else ("垃圾处理" if re.search(r"垃圾|入厂|入炉", name) else "生产运行")
    return {
        "level": level,
        "aggregation": aggregation,
        "aggregation_source": source,
        "business_subject": subject,
        "aliases": list(dict.fromkeys([name, _text(row.get("old_name"))] if _text(row.get("old_name")) else [name])),
        "formula": _text(row.get("formula")),
        "dependencies": row.get("dependencies") if isinstance(row.get("dependencies"), list) else [],
        "time_granularity": "day",
        "organization_granularity": "organization",
        "comparison_supported": bool(aggregation),
        "ranking_supported": bool(aggregation) and level not in ("rate", "unit"),
        "description": _text(row.get("description")),
    }

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
    region_input = _text(scope.get("region_input"))
    authorization = plan.get("authorization", {}) or {}
    allowed_org_codes = [_text(code) for code in (authorization.get("allowed_org_codes") or []) if _text(code)]
    codes = [_text(row.get("code")) for row in orgs if _text(row.get("code"))]
    metric_codes = [_text(row.get("code")) for row in indicators if _text(row.get("code"))]
    metric_code_sql = ",".join(_literal(code) for code in metric_codes)
    if not metric_code_sql:
        return "", "INDICATOR_NOT_FOUND"

    scope_type = _text(scope.get("type"))
    scoped_org_query = bool(codes or allowed_org_codes or scope_type in ("project_company", "region"))
    if scoped_org_query:
        if codes:
            org_where = '"Code" IN (' + ",".join(_literal(code) for code in codes) + ')'
        elif allowed_org_codes:
            org_where = '"Code" IN (' + ",".join(_literal(code) for code in allowed_org_codes) + ')'
        else:
            org_where = (
                '"State_IsEnabled" = \'1\' AND '
                'COALESCE("TreeInfo_IsDetail", \'0\') = \'0\' AND '
                '("Name_CHS" ILIKE \'%公司%\' OR "FullPathName_CHS" ILIKE \'%有限公司%\')'
            )
            if region_input:
                region_literal = _literal(region_input)
                org_where += (
                    ' AND ("Name_CHS" ILIKE \'%\' || ' + region_literal + ' || \'%\' '
                    'OR "Abbreviation_CHS" ILIKE \'%\' || ' + region_literal + ' || \'%\' '
                    'OR "FullPathName_CHS" ILIKE \'%\' || ' + region_literal + ' || \'%\')'
                )
        org_cte = (
            'scoped_orgs AS (SELECT CAST("Code" AS VARCHAR) AS org_code, '
            'COALESCE("Name_CHS", \'\') AS org_name '
            'FROM MSOKFPT."BFAdminOrganization" WHERE ' + org_where + '), '
        )
        org_join = 'JOIN scoped_orgs o ON CAST(t."orgcode" AS VARCHAR)=o.org_code '
        org_name_expr = 'o.org_name'
    else:
        org_cte = ''
        org_join = 'LEFT JOIN MSOKFPT."BFAdminOrganization" o ON CAST(t."orgcode" AS VARCHAR)=CAST(o."Code" AS VARCHAR) '
        org_name_expr = 'COALESCE(o."Name_CHS", \'\')'

    union_parts = []
    for table in tables:
        if not _safe_sql_identifier(table):
            return "", "SQL_VALIDATION_FAILED"
        date_condition = (
            '(t."ZBRQ" >= CAST(' + _literal(start) + ' AS DATE) AND t."ZBRQ" < CAST(' + _literal(end) + ' AS DATE))'
        )
        if baseline_start and baseline_end:
            date_condition = (
                '(t."ZBRQ" >= CAST(' + _literal(start) + ' AS DATE) AND t."ZBRQ" < CAST(' + _literal(end) + ' AS DATE) '
                'OR t."ZBRQ" >= CAST(' + _literal(baseline_start) + ' AS DATE) AND t."ZBRQ" < CAST(' + _literal(baseline_end) + ' AS DATE))'
            )
        where_parts = ['t."newIndicator" IN (' + metric_code_sql + ')', date_condition]
        if codes:
            where_parts.append('t."orgcode" IN (' + ",".join(_literal(code) for code in codes) + ')')
        elif allowed_org_codes:
            where_parts.append('t."orgcode" IN (' + ",".join(_literal(code) for code in allowed_org_codes) + ')')
        union_parts.append(
            'SELECT CAST(t."ZBRQ" AS DATE) AS event_date, '
            'CAST(t."ZBZ" AS VARCHAR) AS raw_value, '
            'CASE WHEN TRIM(CAST(t."ZBZ" AS VARCHAR)) ~ \'^[+-]?[0-9]+(\\.[0-9]+)?$\' '
            'THEN CAST(TRIM(CAST(t."ZBZ" AS VARCHAR)) AS NUMERIC(24,6)) ELSE NULL END AS value_num, '
            'CAST(t."newIndicator" AS VARCHAR) AS metric_code, '
            'CAST(t."orgcode" AS VARCHAR) AS org_code, '
            'CAST(t."ZBBM" AS VARCHAR) AS dimension_code, '
            + org_name_expr + ' AS org_name '
            'FROM MSOKFPT."' + table + '" t '
            + org_join
            + 'WHERE ' + ' AND '.join(where_parts)
        )
    raw = " UNION ALL ".join(union_parts)

    include_org = any(item.get("field") == "org_code" for item in (plan.get("dimensions") or [])) or plan_type in ("RANKING", "RANKING_COMPARISON", "ANOMALY", "EXECUTIVE_OVERVIEW") or scope.get("type") in ("company", "multi_company", "region")
    include_metric = len(indicators) > 1 or plan_type in ("DRILLDOWN", "EXECUTIVE_OVERVIEW")
    granularity = _text(plan.get("time", {}).get("granularity"))
    if plan_type in ("RANKING", "RANKING_COMPARISON", "DISTRIBUTION") or (plan_type == "COMPARISON" and not plan.get("time_series_requested")):
        period_expr = "'total'"
    elif granularity == "day":
        period_expr = "TO_CHAR(raw.event_date, 'YYYY-MM-DD')"
    else:
        period_expr = "TO_CHAR(raw.event_date, 'YYYY-MM')"
    set_expr = "CASE WHEN raw.event_date >= %s AND raw.event_date < %s THEN 'current' ELSE 'baseline' END" % (_literal(start), _literal(end)) if baseline_start and baseline_end else "'current'"
    select_fields = [set_expr + ' AS period_set', period_expr + ' AS period']
    group_fields = ['period_set', 'period']
    if include_metric:
        select_fields.extend(['raw.metric_code AS metric_code', 'MAX(raw.metric_code) AS indicator_code'])
        group_fields.append('raw.metric_code')
    else:
        select_fields.append("MAX(raw.metric_code) AS indicator_code")
    if include_org:
        select_fields.extend(['raw.org_code AS org_code', 'MAX(raw.org_name) AS org_name'])
        group_fields.append('raw.org_code')
    else:
        select_fields.extend(["'' AS org_code", "'全部组织' AS org_name"])
    select_fields.extend([
        'SUM(raw.value_num) AS value',
        'COUNT(*) AS row_count',
        'MAX(raw.event_date) AS period_data_cutoff_date',
        'COUNT(DISTINCT raw.dimension_code) AS dimension_count',
        'SUM(CASE WHEN raw.value_num IS NULL THEN 1 ELSE 0 END) AS invalid_value_count',
        'GREATEST(COUNT(*) - COUNT(DISTINCT CAST(raw.event_date AS VARCHAR) || \'|\' || COALESCE(raw.metric_code, \'\') || \'|\' || COALESCE(raw.org_code, \'\') || \'|\' || COALESCE(raw.dimension_code, \'\')), 0) AS duplicate_key_groups',
    ])
    query = (
        'WITH ' + org_cte + 'raw AS (' + raw + '), '
        'aggregated AS (SELECT ' + ', '.join(select_fields) +
        ' FROM raw '
        ' GROUP BY ' + ', '.join(group_fields) + '), '
        'labeled AS (SELECT a.*, COALESCE(i."newIndicatorname", a.indicator_code) AS indicator_name '
        'FROM aggregated a LEFT JOIN MSOKFPT."CGXTAPPMISNewIndicator" i ON CAST(i."newcode" AS VARCHAR)=a.indicator_code) '
        'SELECT labeled.*, MAX(period_data_cutoff_date) OVER () AS data_cutoff_date '
        'FROM labeled ORDER BY period_set, period, value DESC NULLS LAST LIMIT 1000'
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
    if analysis_type in ("EXECUTIVE_OVERVIEW", "COMPARISON", "CORRELATION") and len(inputs) > 1:
        for term in inputs:
            matches = [row for row in indicator_rows if _text(row.get("name")) == term or term in _text(row.get("name")) or term == _text(row.get("code"))]
            item, pool = _choose(matches, [term], prefer_plant=True)
            if item:
                semantic_indicators.append(item)
            elif pool:
                plan["status"] = "INDICATOR_AMBIGUOUS"
                plan["clarification"] = {"required": True, "slot": "indicator", "candidates": pool}
                return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
    elif analysis_type == "EXECUTIVE_OVERVIEW":
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
                if any(term in name for term in ("入厂量", "入炉量", "运行时间", "停机时间", "垃圾量")):
                    item, _ = _choose([row], [name], prefer_plant=False)
                    if item:
                        semantic_indicators.append(item)
    semantic_indicators = _unique(semantic_indicators, ["code", "name"])
    if not semantic_indicators:
        plan["status"] = "INDICATOR_NOT_FOUND"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    enriched = []
    for row in semantic_indicators:
        semantic = _semantic(row)
        enriched.append({
            "code": _text(row.get("code")),
            "name": _text(row.get("name")),
            "unit": _text(row.get("unit")),
            "old_name": _text(row.get("old_name")),
            "level": semantic.get("level"),
            "aggregation": semantic.get("aggregation"),
            "aggregation_source": semantic.get("aggregation_source"),
            "business_subject": semantic.get("business_subject"),
            "aliases": semantic.get("aliases", []),
            "formula": semantic.get("formula", ""),
            "dependencies": semantic.get("dependencies", []),
            "time_granularity": semantic.get("time_granularity", "day"),
            "organization_granularity": semantic.get("organization_granularity", "organization"),
            "comparison_supported": semantic.get("comparison_supported", False),
            "ranking_supported": semantic.get("ranking_supported", False),
            "description": semantic.get("description", ""),
            "object_code": _text(row.get("object_code")),
            "object_name": _text(row.get("object_name")),
        })
    primary = enriched[0]
    plan["indicator"] = primary
    plan["related_indicators"] = enriched[1:]
    if not primary.get("code"):
        plan["status"] = "INDICATOR_NOT_FOUND"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
    if not primary.get("aggregation") or any(not item.get("aggregation") for item in enriched):
        plan["status"] = "AGGREGATION_NOT_CONFIRMED"
        return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}

    authorization = plan.get("authorization", {}) or {}
    if authorization.get("provided"):
        allowed_indicators = [_text(code) for code in (authorization.get("allowed_indicator_codes") or []) if _text(code)]
        if allowed_indicators and _text(primary.get("code")) not in allowed_indicators:
            plan["status"] = "INDICATOR_ACCESS_DENIED"
            return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
        if analysis_type in ("RANKING", "RANKING_COMPARISON") and not authorization.get("allow_group_ranking"):
            plan["status"] = "GROUP_RANKING_NOT_ALLOWED"
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
                allowed_org_codes = [_text(code) for code in (authorization.get("allowed_org_codes") or []) if _text(code)]
                if authorization.get("provided") and allowed_org_codes and codes[0] not in allowed_org_codes and not authorization.get("allow_all_organizations"):
                    plan["status"] = "ORGANIZATION_ACCESS_DENIED"
                    return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
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
        # Keep the semantic candidate set for audit/clarification, but let the
        # fact SQL apply the same controlled organization predicate directly.
        # This avoids materializing hundreds of organization codes into an IN
        # list and keeps ranking complete rather than capped by lookup LIMIT.
        selected_orgs = []
        if not selected_orgs:
            if authorization.get("provided") and not authorization.get("allow_all_organizations") and not authorization.get("allowed_org_codes"):
                plan["status"] = "ORGANIZATION_ACCESS_DENIED"
                return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": "", "can_execute": 0, "status": plan["status"], "analysis_state": json.dumps(plan, ensure_ascii=False)}
            if not candidates:
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
        "indicators": [plan.get("indicator")] + (plan.get("related_indicators") or []),
        "related_indicators": plan.get("related_indicators") or [],
        "time": plan.get("time"),
        "dimensions": plan.get("dimensions"),
        "filters": plan.get("filters"),
        "organization_scope": {"type": scope.get("type"), "inputs": org_inputs, "codes": plan["organization_scope"].get("codes"), "names": plan["organization_scope"].get("names"), "region_input": scope.get("region_input")},
        "sort": plan.get("sort"),
        "ranking_mode": plan.get("ranking_mode"),
        "top_n": plan.get("top_n"),
        "comparison": plan.get("comparison"),
        "analysis_actions": plan.get("analysis_actions"),
        "time_series_requested": plan.get("time_series_requested"),
        "pending_clarification": plan.get("clarification"),
        "current_date": plan.get("current_date"),
    }
    return {"analysis_plan_json": json.dumps(plan, ensure_ascii=False), "query_sql": query_sql, "can_execute": 1, "status": "READY", "analysis_state": json.dumps(state, ensure_ascii=False)}
`

  const auditCode = String.raw`
import json
import math
import statistics

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
        if not math.isfinite(number):
            return None
        return int(number) if number.is_integer() else round(number, 4)
    except Exception:
        return None

def _fmt(value):
    number = _number(value)
    return "-" if number is None else format(number, ",.2f")

def _rate(current, baseline):
    current = _number(current)
    baseline = _number(baseline)
    if current is None or baseline in (None, 0):
        return None
    return round((current - baseline) / abs(baseline) * 100, 2)

def _group(rows, keys):
    result = {}
    for row in rows:
        result.setdefault(tuple(row.get(key) for key in keys), []).append(row)
    return result

def _clean_rows(rows):
    cleaned = []
    for row in rows:
        cleaned.append({
            "period_set": _text(row.get("period_set")) or "current",
            "period": _text(row.get("period")),
            "indicator_code": _text(row.get("indicator_code") or row.get("metric_code")),
            "indicator_name": _text(row.get("indicator_name")),
            "org_code": _text(row.get("org_code")),
            "org_name": _text(row.get("org_name")) or "全部组织",
            "value": _number(row.get("value")),
            "row_count": int(_number(row.get("row_count")) or 0),
            "dimension_count": int(_number(row.get("dimension_count")) or 0),
            "invalid_value_count": int(_number(row.get("invalid_value_count")) or 0),
            "duplicate_key_groups": int(_number(row.get("duplicate_key_groups")) or 0),
            "data_cutoff_date": _text(row.get("data_cutoff_date"))[:10],
        })
    return cleaned

def _totals(rows, period_set="current"):
    result = {}
    for row in rows:
        if row.get("period_set") != period_set or row.get("value") is None:
            continue
        key = (
            row.get("org_code") or "",
            row.get("org_name") or "全部组织",
            row.get("indicator_code"),
            row.get("indicator_name"),
        )
        result[key] = round(result.get(key, 0) + row.get("value"), 4)
    return result

def _comparison_items(rows, plan):
    current = _totals(rows, "current")
    baseline = _totals(rows, "baseline")
    baseline_by_identity = {(key[0], key[2]): value for key, value in baseline.items()}
    result = []
    for key, value in current.items():
        org_code, org_name, indicator_code, indicator_name = key
        baseline_value = baseline_by_identity.get((org_code, indicator_code))
        result.append({
            "org_code": org_code,
            "org_name": org_name,
            "indicator_code": indicator_code,
            "indicator_name": indicator_name,
            "value": value,
            "baseline_value": baseline_value,
            "difference": round(value - baseline_value, 4) if baseline_value is not None else None,
            "change_rate": _rate(value, baseline_value),
        })
    result.sort(key=lambda item: (_text(item.get("org_name")), _text(item.get("indicator_name"))))
    return result

def _ranking(rows, plan):
    values = _comparison_items(rows, plan)
    sort_field = "change_rate" if plan.get("analysis_type") == "RANKING_COMPARISON" else "value"
    values = [item for item in values if item.get(sort_field) is not None]
    reverse = plan.get("ranking_mode") != "bottom"
    values.sort(key=lambda item: item.get(sort_field) or 0, reverse=reverse)
    baseline_values = [item for item in _comparison_items(rows, {"analysis_type": "COMPARISON"}) if item.get("baseline_value") is not None]
    baseline_values.sort(key=lambda item: item.get("baseline_value") or 0, reverse=True)
    old_rank = {item.get("org_code"): index for index, item in enumerate(baseline_values, 1)}
    for index, item in enumerate(values, 1):
        item["rank"] = index
        item["baseline_rank"] = old_rank.get(item.get("org_code"), 0)
        item["rank_change"] = item["baseline_rank"] - index if item["baseline_rank"] else None
    top_n = max(1, min(int(plan.get("top_n") or 10), 50))
    if plan.get("ranking_mode") == "both":
        return values[:top_n] + (values[-top_n:] if len(values) > top_n else [])
    return values[:top_n]

def _trend_insights(rows):
    insights = []
    groups = _group([row for row in rows if row.get("period_set") == "current" and row.get("period") and row.get("period") != "total" and row.get("value") is not None], ["org_code", "indicator_code"])
    for _, series in groups.items():
        series = sorted(series, key=lambda row: row.get("period") or "")
        if len(series) < 2:
            continue
        first, last = series[0], series[-1]
        change = _rate(last.get("value"), first.get("value"))
        label = last.get("org_name") or "全部组织"
        metric = last.get("indicator_name") or "指标"
        if change is not None:
            insights.append("%s的%s从%s变为%s，首末周期变化%s%%。" % (label, metric, _fmt(first.get("value")), _fmt(last.get("value")), _fmt(change)))
        negative = 0
        for index in range(1, len(series)):
            if series[index].get("value") < series[index - 1].get("value"):
                negative += 1
            else:
                negative = 0
        if negative >= 3:
            insights.append("%s的%s最近连续%s个周期下降，建议继续核查相关运行指标。" % (label, metric, negative + 1))
    return insights[:8]

def _anomalies(rows, plan=None):
    plan = plan or {}
    focus = _text(plan.get("anomaly_focus")) or "THRESHOLD"
    anomalies = []
    groups = _group([row for row in rows if row.get("period_set") == "current" and row.get("period") and row.get("period") != "total" and row.get("value") is not None], ["org_code", "indicator_code"])
    for _, series in groups.items():
        series = sorted(series, key=lambda row: row.get("period") or "")
        consecutive = 0
        streak_start = None
        for index in range(1, len(series)):
            previous, current = series[index - 1], series[index]
            change = _rate(current.get("value"), previous.get("value"))
            if change is None:
                consecutive = 0
                streak_start = None
                continue
            common = {
                "org_code": current.get("org_code"),
                "org_name": current.get("org_name"),
                "indicator_code": current.get("indicator_code"),
                "indicator_name": current.get("indicator_name"),
                "period": current.get("period"),
                "actual_value": current.get("value"),
                "baseline_value": previous.get("value"),
            }
            if focus in ("THRESHOLD", "MOM_DROP") and change <= -20:
                anomalies.append(dict(common, anomaly_type="MOM_DROP", threshold=-20, evidence="%s较%s下降%s%%" % (current.get("period"), previous.get("period"), abs(change))))
            elif focus == "THRESHOLD" and change >= 20:
                anomalies.append(dict(common, anomaly_type="MOM_RISE", threshold=20, evidence="%s较%s上升%s%%" % (current.get("period"), previous.get("period"), change)))
            if current.get("value") < previous.get("value"):
                consecutive = consecutive + 1 if consecutive else 1
                streak_start = streak_start or previous.get("period")
            else:
                consecutive = 0
                streak_start = None
            if focus in ("THRESHOLD", "CONSECUTIVE_DECREASE") and consecutive >= 3 and (consecutive == 3 or index == len(series) - 1):
                anomalies.append(dict(common, anomaly_type="CONSECUTIVE_DECREASE", threshold=3, evidence="从%s开始已连续%s个周期下降" % (streak_start, consecutive + 1)))
    return anomalies[:120]

def _distribution(rows):
    totals = _totals(rows, "current")
    values = sorted([value for key, value in totals.items() if key[0] or key[1] != "全部组织"])
    if not values:
        return {"count": 0, "min": None, "max": None, "mean": None, "median": None, "q1": None, "q3": None, "buckets": []}
    q1 = values[max(0, int((len(values) - 1) * 0.25))]
    q3 = values[max(0, int((len(values) - 1) * 0.75))]
    median = statistics.median(values)
    mean = round(sum(values) / len(values), 4)
    labels = ["低于或等于 P25", "P25 至中位数", "中位数至 P75", "高于 P75"]
    counts = [0, 0, 0, 0]
    for value in values:
        if value <= q1:
            counts[0] += 1
        elif value <= median:
            counts[1] += 1
        elif value <= q3:
            counts[2] += 1
        else:
            counts[3] += 1
    buckets = [{"bucket": labels[index], "count": counts[index], "share": round(counts[index] / len(values) * 100, 2)} for index in range(4)]
    return {"count": len(values), "min": values[0], "max": values[-1], "mean": mean, "median": median, "q1": q1, "q3": q3, "buckets": buckets}

def _pearson(left, right):
    if len(left) < 3 or len(left) != len(right):
        return None
    left_mean = sum(left) / len(left)
    right_mean = sum(right) / len(right)
    numerator = sum((a - left_mean) * (b - right_mean) for a, b in zip(left, right))
    left_dev = math.sqrt(sum((a - left_mean) ** 2 for a in left))
    right_dev = math.sqrt(sum((b - right_mean) ** 2 for b in right))
    if left_dev == 0 or right_dev == 0:
        return None
    return round(numerator / (left_dev * right_dev), 4)

def _correlations(rows, plan):
    indicators = [plan.get("indicator", {})] + list(plan.get("related_indicators") or [])
    indicators = [item for item in indicators if _text(item.get("code"))]
    if len(indicators) < 2:
        return []
    current = [row for row in rows if row.get("period_set") == "current" and row.get("period") and row.get("period") != "total" and row.get("value") is not None]
    grouped = _group(current, ["org_code", "period", "indicator_code"])
    result = []
    for org_code in list(dict.fromkeys(row.get("org_code") for row in current))[:20]:
        periods = sorted(set(row.get("period") for row in current if row.get("org_code") == org_code))
        left_code, right_code = _text(indicators[0].get("code")), _text(indicators[1].get("code"))
        pairs = [(period, grouped.get((org_code, period, left_code), [{}])[0].get("value"), grouped.get((org_code, period, right_code), [{}])[0].get("value")) for period in periods]
        pairs = [(period, left, right) for period, left, right in pairs if left is not None and right is not None]
        coefficient = _pearson([item[1] for item in pairs], [item[2] for item in pairs])
        if coefficient is None:
            continue
        result.append({
            "org_code": org_code,
            "org_name": next((row.get("org_name") for row in current if row.get("org_code") == org_code), "全部组织"),
            "indicator_a": _text(indicators[0].get("name")),
            "indicator_b": _text(indicators[1].get("name")),
            "coefficient": coefficient,
            "period_count": len(pairs),
            "evidence": "基于%s个共同周期的 Pearson 相关系数；相关性不等同于因果关系。" % len(pairs),
        })
    return result

def _followups(kind):
    values = {
        "RANKING": ["只看后5名", "和去年相比", "查看排名变化最大的公司"],
        "RANKING_COMPARISON": ["只看同比下降最多的公司", "查看排名变化最大的公司", "继续下钻原因"],
        "TREND": ["做同比分析", "查看异常变化", "为什么会下降"],
        "COMPARISON": ["按同比排序", "查看各公司趋势", "只看差距最大的对象"],
        "DISTRIBUTION": ["查看最高的5家公司", "查看最低的5家公司", "查看异常对象"],
        "ANOMALY": ["查看异常公司的趋势", "和去年相比", "继续下钻原因"],
        "CORRELATION": ["查看两个指标趋势", "继续下钻原因", "查看异常月份"],
        "DRILLDOWN": ["查看相关指标趋势", "和去年相比", "查看异常月份"],
        "EXECUTIVE_OVERVIEW": ["查看重点公司排名", "查看异常变化", "继续下钻原因"],
    }
    return values.get(kind, ["查看月度趋势", "做同比分析", "查看项目公司排名"])

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
        "INDICATOR_ACCESS_DENIED": "当前账号没有权限查询该指标。",
        "ORGANIZATION_ACCESS_DENIED": "当前账号没有权限查询该组织范围。",
        "GROUP_RANKING_NOT_ALLOWED": "当前账号暂未开放跨组织排名能力。",
        "SQL_METADATA_LOOKUP_FAILED": "指标或组织事实查询失败，系统未执行后续分析。",
        "SQL_EXECUTION_FAILED": "事实查询执行失败，系统未展示未经校验的数据。",
        "RESULT_VALIDATION_FAILED": "查询结果未通过数据质量校验，系统未展示未经确认的数据。",
        "NO_DATA_IN_PERIOD": "指定时间范围内没有查到数据。",
    }
    return messages.get(status, "查询未返回可信结果，系统已阻止展示未经校验的数据。")

def _columns(items):
    return [{"key": key, "label": label, "type": kind, **({"unit": unit} if unit else {})} for key, label, kind, unit in items]

def _metric(label, value, unit=""):
    return {"label": label, "value": value, "unit": unit}

def _series_chart(categories, series, title, chart_type="line"):
    if not categories or not series:
        return None
    return {"type": chart_type, "title": title, "categories": categories[:1000], "series": series[:12]}

def _time_chart(rows, plan, title):
    current = [row for row in rows if row.get("period_set") == "current" and row.get("period") and row.get("period") != "total" and row.get("value") is not None]
    if not current:
        return None
    periods = sorted(set(row.get("period") for row in current))
    groups = _group(current, ["org_code", "indicator_code"])
    series = []
    for _, items in list(groups.items())[:12]:
        items_by_period = {item.get("period"): item.get("value") for item in items}
        first = items[0]
        name = first.get("indicator_name") or first.get("org_name") or "指标"
        if len(groups) > 1:
            name = "%s · %s" % (first.get("org_name") or "全部组织", name)
        series.append({"name": name, "type": "line", "data": [items_by_period.get(period) for period in periods]})
    return _series_chart(periods, series, title, "line")

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
    elif any(row.get("value") is None or row.get("invalid_value_count", 0) > 0 or row.get("duplicate_key_groups", 0) > 0 for row in rows):
        final_status = "RESULT_VALIDATION_FAILED"
        warnings.append("存在非法数值或重复业务键，未压平或猜选数据维度")
    else:
        final_status = "SUCCESS_WITH_DATA"

    kind = _text(plan.get("analysis_type")) or "FACT"
    ranking = _ranking(rows, plan) if final_status == "SUCCESS_WITH_DATA" and kind in ("RANKING", "RANKING_COMPARISON") else []
    comparison_items = _comparison_items(rows, plan) if final_status == "SUCCESS_WITH_DATA" and kind in ("COMPARISON", "CORRELATION", "DRILLDOWN", "EXECUTIVE_OVERVIEW") else []
    distribution = _distribution(rows) if final_status == "SUCCESS_WITH_DATA" and kind == "DISTRIBUTION" else {}
    anomalies = _anomalies(rows, plan) if final_status == "SUCCESS_WITH_DATA" and kind in ("ANOMALY", "EXECUTIVE_OVERVIEW", "TREND", "DRILLDOWN") else []
    correlations = _correlations(rows, plan) if final_status == "SUCCESS_WITH_DATA" and kind == "CORRELATION" else []
    insights = []

    if final_status == "SUCCESS_WITH_DATA":
        if ranking:
            first = ranking[0]
            if kind == "RANKING_COMPARISON":
                insights.append("按%s变化排序，%s变化%s%%，当前排第%s；这是同期数据比较结果，不等同于因果结论。" % (_text(plan.get("comparison", {}).get("type")) or "基期", _text(first.get("org_name")), _fmt(first.get("change_rate")), first.get("rank")))
            else:
                total_count = len(_totals(rows, "current"))
                last = ranking[-1]
                if plan.get("ranking_mode") == "bottom":
                    insights.append("共统计%s个对象，%s当前值最低，为%s；当前展示的后5名从低到高排列。" % (total_count, _text(first.get("org_name")), _fmt(first.get("value"))))
                else:
                    insights.append("共统计%s个对象，%s以%s位居当前榜首，当前展示末位为%s。" % (total_count, _text(first.get("org_name")), _fmt(first.get("value")), _text(last.get("org_name"))))
            movers = sorted([item for item in ranking if item.get("change_rate") is not None], key=lambda item: abs(item.get("change_rate")), reverse=True)
            if movers and plan.get("comparison", {}).get("type") != "none":
                insights.append("当前展示对象中，%s同期变化幅度最大，为%s%%；这是比较线索，不直接说明原因。" % (_text(movers[0].get("org_name")), _fmt(movers[0].get("change_rate"))))
        elif kind in ("COMPARISON", "DRILLDOWN", "EXECUTIVE_OVERVIEW"):
            comparable = [item for item in comparison_items if item.get("baseline_value") is not None]
            if comparable:
                mover = max(comparable, key=lambda item: abs(item.get("change_rate") or 0))
                insights.append("%s的%s从%s变为%s，变化%s%%；现有数据支持描述变化，不足以单独确认因果。" % (_text(mover.get("org_name")), _text(mover.get("indicator_name")), _fmt(mover.get("baseline_value")), _fmt(mover.get("value")), _fmt(mover.get("change_rate"))))
            else:
                insights.extend(_trend_insights(rows))
        elif kind == "DISTRIBUTION":
            insights.append("共纳入%s个对象，平均值%s，中位数%s，最大值%s。" % (distribution.get("count"), _fmt(distribution.get("mean")), _fmt(distribution.get("median")), _fmt(distribution.get("max"))))
        else:
            insights.extend(_trend_insights(rows))
        if correlations:
            item = correlations[0]
            insights.append("%s与%s在%s个共同周期上的相关系数为%s；相关性不等同于因果关系。" % (_text(item.get("indicator_a")), _text(item.get("indicator_b")), item.get("period_count"), _fmt(item.get("coefficient"))))
        if anomalies:
            insights.append("按月环比绝对变化20%%和连续3期下降规则，共识别%s条需要关注的异常线索。" % len(anomalies))
        elif kind in ("ANOMALY", "EXECUTIVE_OVERVIEW"):
            insights.append("按当前启用的环比20%%阈值和连续3期下降规则，暂未发现达到规则的异常线索。")
        if kind == "DRILLDOWN":
            insights.append("原因下钻只输出同期变化证据和优先排查线索；没有业务规则或直接事件证据时，不把相关性包装成因果结论。")
    if final_status == "SUCCESS_WITH_DATA" and not insights:
        insights.append("结果已通过事实、时间、指标和数据质量校验。")

    cutoff = max([row.get("data_cutoff_date") for row in rows if row.get("data_cutoff_date")] or [""])
    current_values = [row.get("value") for row in rows if row.get("period_set") == "current" and row.get("value") is not None]
    stats = {
        "row_count": len(rows),
        "current_row_count": len([row for row in rows if row.get("period_set") == "current"]),
        "baseline_row_count": len([row for row in rows if row.get("period_set") == "baseline"]),
        "total": round(sum(current_values), 4) if current_values else None,
        "max": max(current_values) if current_values else None,
        "min": min(current_values) if current_values else None,
        "cutoff": cutoff,
    }
    validation = {
        "status": final_status,
        "execution_error": error,
        "source_tables": plan.get("allowed_tables", []),
        "duplicate_key_policy": "检测到重复键时阻断，不使用 DISTINCT 掩盖",
        "numeric_policy": "非法数值不参与可信结果",
    }
    audit = {
        "status": final_status,
        "analysis_plan": plan,
        "query_context": {"analysis_type": kind, "indicator": plan.get("indicator"), "related_indicators": plan.get("related_indicators", []), "organization_scope": plan.get("organization_scope"), "time": plan.get("time"), "dimensions": plan.get("dimensions"), "filters": plan.get("filters"), "comparison": plan.get("comparison")},
        "metric": plan.get("indicator"),
        "related_metrics": plan.get("related_indicators", []),
        "organization_scope": plan.get("organization_scope"),
        "time_range": plan.get("time"),
        "dimensions": plan.get("dimensions"),
        "filters": plan.get("filters"),
        "aggregation": plan.get("indicator", {}).get("aggregation"),
        "comparison": plan.get("comparison"),
        "rows": rows[:1000],
        "statistics": stats,
        "ranking": ranking,
        "distribution": distribution,
        "anomalies": anomalies,
        "correlations": correlations,
        "insights_evidence": [{"type": "deterministic_rule", "text": item} for item in insights],
        "data_cutoff_date": cutoff,
        "warnings": warnings,
        "validation": validation,
        "source_tables": plan.get("allowed_tables", []),
    }

    indicator = plan.get("indicator", {}) or {}
    scope = plan.get("organization_scope", {}) or {}
    title = _text(indicator.get("name")) or "经营数据分析"
    unit = _text(indicator.get("unit"))
    start = _text(plan.get("time", {}).get("start"))
    end = _text(plan.get("time", {}).get("end"))
    table = None
    table_rows = []
    metrics = []
    chart = None

    if final_status != "SUCCESS_WITH_DATA":
        visible = _status_message(final_status, plan)
        followups = []
    else:
        if kind in ("RANKING", "RANKING_COMPARISON"):
            columns = [("rank", "排名", "number", ""), ("companyName", "公司名称", "text", ""), ("value", "当前值", "number", unit)]
            if kind == "RANKING_COMPARISON":
                comparison_label = _text(plan.get("comparison", {}).get("type")) or "同期"
                columns.extend([("baselineValue", "同期值", "number", unit), ("changeRate", comparison_label + " %", "number", "%"), ("rankChange", "排名变化", "number", "位")])
            for item in ranking:
                row = {"rank": item.get("rank"), "companyName": _text(item.get("org_name")), "value": item.get("value")}
                if kind == "RANKING_COMPARISON":
                    row.update({"baselineValue": item.get("baseline_value"), "changeRate": item.get("change_rate"), "rankChange": item.get("rank_change")})
                table_rows.append(row)
            table = {"columns": _columns(columns), "rows": table_rows, "total": len(_totals(rows, "current")), "defaultVisibleRows": 10}
            lead_label = "当前最低" if plan.get("ranking_mode") == "bottom" else "当前榜首"
            metrics = [_metric("统计对象", len(_totals(rows, "current")), "家"), _metric(lead_label, table_rows[0].get("companyName") if table_rows else "", "")]
            chart = _series_chart([row.get("companyName") for row in table_rows], [{"name": "同比变化" if kind == "RANKING_COMPARISON" else title, "type": "bar", "data": [row.get("changeRate") if kind == "RANKING_COMPARISON" else row.get("value") for row in table_rows]}], "项目公司排名", "bar")
        elif kind == "COMPARISON":
            table_rows = [{"organization": _text(item.get("org_name")), "indicator": _text(item.get("indicator_name")), "currentValue": item.get("value"), "baselineValue": item.get("baseline_value"), "changeRate": item.get("change_rate"), "difference": item.get("difference")} for item in comparison_items]
            table = {"columns": _columns([("organization", "对象", "text", ""), ("indicator", "指标", "text", ""), ("currentValue", "当前值", "number", unit), ("baselineValue", "同期值", "number", unit), ("changeRate", "变化 %", "number", "%"), ("difference", "差值", "number", unit)]), "rows": table_rows[:1000], "total": len(table_rows), "defaultVisibleRows": 10}
            metrics = [_metric("比较对象", len(table_rows), "个")]
            chart = _series_chart([row.get("organization") or row.get("indicator") for row in table_rows], [{"name": "当前值", "type": "bar", "data": [row.get("currentValue") for row in table_rows]}, {"name": "同期值", "type": "bar", "data": [row.get("baselineValue") for row in table_rows]}], "经营数据对比", "bar")
        elif kind == "DISTRIBUTION":
            table_rows = distribution.get("buckets", [])
            table = {"columns": _columns([("bucket", "区间", "text", ""), ("count", "对象数", "number", "家"), ("share", "占比", "number", "%")]), "rows": table_rows, "total": distribution.get("count", 0), "defaultVisibleRows": 10}
            metrics = [_metric("统计对象", distribution.get("count"), "家"), _metric("平均值", distribution.get("mean"), unit), _metric("中位数", distribution.get("median"), unit)]
            chart = _series_chart([item.get("bucket") for item in table_rows], [{"name": "对象数", "type": "bar", "data": [item.get("count") for item in table_rows]}], "数据分布", "bar")
        elif kind == "ANOMALY":
            table_rows = [{"organization": _text(item.get("org_name")), "period": _text(item.get("period")), "indicator": _text(item.get("indicator_name")), "anomalyType": _text(item.get("anomaly_type")), "evidence": _text(item.get("evidence")), "currentValue": item.get("actual_value"), "baselineValue": item.get("baseline_value")} for item in anomalies]
            table = {"columns": _columns([("organization", "对象", "text", ""), ("period", "周期", "text", ""), ("indicator", "指标", "text", ""), ("anomalyType", "规则", "text", ""), ("evidence", "证据", "text", ""), ("currentValue", "当前值", "number", unit), ("baselineValue", "基准值", "number", unit)]), "rows": table_rows, "total": len(table_rows), "defaultVisibleRows": 10}
            metrics = [_metric("异常线索", len(table_rows), "条")]
            by_org = {}
            for item in anomalies:
                by_org[item.get("org_name")] = by_org.get(item.get("org_name"), 0) + 1
            chart = _series_chart(list(by_org.keys()), [{"name": "异常线索", "type": "bar", "data": list(by_org.values())}], "异常线索分布", "bar") if by_org else None
        elif kind == "CORRELATION":
            table_rows = [{"organization": _text(item.get("org_name")), "indicatorA": _text(item.get("indicator_a")), "indicatorB": _text(item.get("indicator_b")), "coefficient": item.get("coefficient"), "periodCount": item.get("period_count"), "evidence": _text(item.get("evidence"))} for item in correlations]
            table = {"columns": _columns([("organization", "对象", "text", ""), ("indicatorA", "指标 A", "text", ""), ("indicatorB", "指标 B", "text", ""), ("coefficient", "相关系数", "number", ""), ("periodCount", "共同周期", "number", "个"), ("evidence", "证据说明", "text", "")]), "rows": table_rows, "total": len(table_rows), "defaultVisibleRows": 10}
            metrics = [_metric("相关对象", len(table_rows), "个"), _metric("相关系数", correlations[0].get("coefficient") if correlations else None, "")]
            chart = _time_chart(rows, plan, "指标同期走势")
        elif kind == "DRILLDOWN":
            current_rows = [row for row in rows if row.get("period_set") == "current" and row.get("period")]
            table_rows = [{"period": _text(row.get("period")), "organization": _text(row.get("org_name")), "indicator": _text(row.get("indicator_name")), "value": row.get("value")} for row in current_rows]
            table = {"columns": _columns([("period", "周期", "text", ""), ("organization", "对象", "text", ""), ("indicator", "指标", "text", ""), ("value", "数值", "number", unit)]), "rows": table_rows[:1000], "total": len(table_rows), "defaultVisibleRows": 10}
            metrics = [_metric("证据数据点", len(table_rows), "个"), _metric("异常线索", len(anomalies), "条")]
            chart = _time_chart(rows, plan, "目标指标与相关指标走势")
        elif kind == "EXECUTIVE_OVERVIEW":
            latest = {}
            for row in rows:
                if row.get("period_set") != "current" or row.get("value") is None:
                    continue
                key = (row.get("org_code"), row.get("indicator_code"))
                if not latest.get(key) or row.get("period", "") > latest[key].get("period", ""):
                    latest[key] = row
            table_rows = [{"organization": _text(row.get("org_name")), "indicator": _text(row.get("indicator_name")), "latestPeriod": _text(row.get("period")), "latestValue": row.get("value")} for row in list(latest.values())[:1000]]
            table = {"columns": _columns([("organization", "对象", "text", ""), ("indicator", "指标", "text", ""), ("latestPeriod", "最新周期", "text", ""), ("latestValue", "最新值", "number", unit)]), "rows": table_rows, "total": len(table_rows), "defaultVisibleRows": 10}
            metrics = [_metric("关注对象", len(set(row.get("organization") for row in table_rows)), "家"), _metric("异常线索", len(anomalies), "条")]
            chart = _time_chart(rows, plan, "经营指标走势")
        else:
            current_rows = [row for row in rows if row.get("period_set") == "current"]
            table_rows = [{"period": _text(row.get("period")), "organization": _text(row.get("org_name")), "indicator": _text(row.get("indicator_name")), "value": row.get("value")} for row in current_rows]
            table = {"columns": _columns([("period", "周期", "text", ""), ("organization", "对象", "text", ""), ("indicator", "指标", "text", ""), ("value", "数值", "number", unit)]), "rows": table_rows[:1000], "total": len(table_rows), "defaultVisibleRows": 10}
            values = [row.get("value") for row in current_rows if row.get("value") is not None]
            metrics = [_metric("数据点", len(values), "个"), _metric("最新值", values[-1] if values else None, unit)]
            chart = _time_chart(rows, plan, title)

        visible_lines = []
        if kind in ("RANKING", "RANKING_COMPARISON"):
            visible_lines.append("%s%s。" % (title, "按同期变化排名结果如下" if kind == "RANKING_COMPARISON" else "项目公司排名结果如下"))
        elif kind == "DISTRIBUTION":
            visible_lines.append("%s的对象分布已按 P25、中位数和 P75 确定性分组。" % title)
        elif kind == "ANOMALY":
            visible_lines.append("%s按预设异常规则完成扫描，共识别%s条需要关注的线索。" % (title, len(anomalies)))
        elif kind == "DRILLDOWN":
            visible_lines.append("已围绕%s查询相关指标，下面展示同期证据；相关性不等同于因果关系。" % title)
        elif kind == "EXECUTIVE_OVERVIEW":
            visible_lines.append("已完成最近经营数据概览，先展示最新值、异常线索和可继续下钻的方向。")
        elif kind == "CORRELATION":
            visible_lines.append("已按共同周期计算指标间相关系数，结果只表示统计关联，不表示因果。")
        else:
            visible_lines.append("%s查询完成，当前时间范围为%s至%s。" % (title, start, end))
        visible_lines.extend(["", "## 关键发现"])
        visible_lines.extend("- " + item for item in insights[:8])
        visible_lines.extend(["", "## 数据说明", "统计范围：%s 至 %s（结束日期不含）；数据更新至：%s。" % (start, end, cutoff or "未取得"), "需要技术细节时，可查看本次结果的审计信息。"])
        visible = "\n".join(visible_lines)
        followups = _followups(kind)

    state = {
        "analysis_type": kind,
        "indicator_inputs": plan.get("indicator_inputs"),
        "indicator": plan.get("indicator"),
        "indicators": [plan.get("indicator")] + (plan.get("related_indicators") or []),
        "time": plan.get("time"),
        "dimensions": plan.get("dimensions"),
        "filters": plan.get("filters"),
        "organization_scope": {"type": scope.get("type"), "inputs": scope.get("inputs"), "codes": scope.get("codes"), "names": scope.get("names"), "region_input": scope.get("region_input")},
        "sort": plan.get("sort"),
        "ranking_mode": plan.get("ranking_mode"),
        "anomaly_focus": plan.get("anomaly_focus"),
        "top_n": plan.get("top_n"),
        "comparison": plan.get("comparison"),
        "analysis_actions": plan.get("analysis_actions"),
        "time_series_requested": plan.get("time_series_requested"),
        "pending_clarification": plan.get("clarification"),
        "current_date": plan.get("current_date"),
    }

    protocol_type = {"EXECUTIVE_OVERVIEW": "OVERVIEW", "CORRELATION": "COMPARISON"}.get(kind, kind)
    data_info = {
        "analysisType": protocol_type,
        "indicatorName": title,
        "indicatorCode": _text(indicator.get("code")),
        "unit": unit,
        "timeRange": {"start": start, "end": end, "endExclusive": True},
        "dataCutoffDate": cutoff,
        "aggregation": _text(indicator.get("aggregation")),
        "organizationScope": {"type": scope.get("type"), "names": scope.get("names", []), "codes": scope.get("codes", [])},
        "rowCount": len(rows),
        "statistics": stats,
        "comparison": plan.get("comparison"),
        "sourceTables": plan.get("allowed_tables", []),
        "warnings": warnings,
        "validation": validation,
    }
    if final_status == "SUCCESS_WITH_DATA":
        if ranking:
            first = ranking[0]
            if kind == "RANKING_COMPARISON":
                lead_text = "按同期变化最小" if plan.get("ranking_mode") == "bottom" else "按同期变化排名第一"
            else:
                lead_text = "当前值最低" if plan.get("ranking_mode") == "bottom" else "排名第一"
            summary = "%s共统计%s家公司，%s%s，当前值%s。" % (title, len(_totals(rows, "current")), _text(first.get("org_name")), lead_text, _fmt(first.get("value")))
        elif kind == "DISTRIBUTION":
            summary = "%s共统计%s个对象，平均值%s，中位数%s。" % (title, distribution.get("count"), _fmt(distribution.get("mean")), _fmt(distribution.get("median")))
        elif kind == "ANOMALY":
            summary = "%s已完成规则扫描，共识别%s条需要关注的异常线索。" % (title, len(anomalies))
        elif kind == "CORRELATION":
            summary = "%s已完成共同周期相关性分析，得到%s个对象结果；相关性不等同于因果关系。" % (title, len(correlations))
        elif kind == "DRILLDOWN":
            summary = "%s相关指标证据查询完成，共返回%s条数据；结果用于排查线索，不直接确认因果。" % (title, len(rows))
        elif kind == "EXECUTIVE_OVERVIEW":
            summary = "最近经营数据概览完成，覆盖%s条经过校验的数据，识别%s条需要关注的线索。" % (len(rows), len(anomalies))
        else:
            summary = "%s查询完成，共返回%s条经过校验的数据。" % (title, len(rows))
    else:
        summary = visible.split("\n", 1)[0].strip() or _status_message(final_status, plan)

    message_type = "clarification" if final_status in ("INDICATOR_AMBIGUOUS", "ORGANIZATION_AMBIGUOUS") else ("analysis" if final_status == "SUCCESS_WITH_DATA" else ("empty" if final_status == "NO_DATA_IN_PERIOD" else "error"))
    response = {
        "protocolVersion": "2.0",
        "requestId": _text(plan.get("request_id")),
        "conversationId": _text(plan.get("conversation_id")),
        "status": final_status,
        "messageType": message_type,
        "analysisType": protocol_type,
        "content": {
            "title": title,
            "summary": summary,
            "metrics": metrics,
            "table": table,
            "chart": chart,
            "insights": [{"type": "attention" if ("异常" in item or "关注" in item) else ("evidence" if "因果" in item or "相关" in item else "fact"), "text": item} for item in insights[:8]],
            "evidence": [{"type": "deterministic_rule", "text": item} for item in insights[:8]],
            "dataInfo": data_info,
            "followUps": [{"id": "follow-up-%s" % index, "label": item, "query": item} for index, item in enumerate(followups[:6])],
        },
        "clarification": None,
        "meta": {
            "auditStatus": final_status,
            "validated": final_status == "SUCCESS_WITH_DATA",
            "analysisState": state,
            "auditSummary": {"statistics": stats, "ranking": ranking, "distribution": distribution, "anomalies": anomalies, "correlations": correlations, "warnings": warnings, "validation": validation, "sourceTables": plan.get("allowed_tables", [])},
        },
    }
    if final_status in ("INDICATOR_AMBIGUOUS", "ORGANIZATION_AMBIGUOUS"):
        clarification = plan.get("clarification") or (scope.get("clarification") if final_status == "ORGANIZATION_AMBIGUOUS" else {}) or {}
        candidates = clarification.get("candidates", []) if isinstance(clarification, dict) else []
        response["clarification"] = {"slot": clarification.get("slot", "organization" if final_status == "ORGANIZATION_AMBIGUOUS" else "indicator"), "title": "找到多个候选，请选择", "candidates": [{"id": _text(item.get("code")), "label": _text(item.get("name")), "description": _text(item.get("full_path"))} for item in candidates if isinstance(item, dict)]}
    audit["visible_answer"] = visible
    audit["follow_ups"] = followups
    audit["chart"] = chart
    audit["response_protocol"] = response
    return {"result_text": json.dumps(response, ensure_ascii=False, separators=(",", ":")), "audit_json": json.dumps(audit, ensure_ascii=False), "status": final_status, "analysis_state": json.dumps(state, ensure_ascii=False), "follow_ups": json.dumps(followups, ensure_ascii=False), "chart_option": json.dumps(chart, ensure_ascii=False) if chart else ""}
`

  const intentSystem = `你只负责理解当前问题和上一轮结构化分析状态，输出一个 JSON 对象。你可以识别分析意图、指标名称、组织名称列表、区域范围、时间表达、排序和 TopN，但不能查询数据库、生成 SQL、猜组织 Code、猜指标 Code、猜公式或猜数字。当前问题可能是对上一轮的补充，例如只看前5、和去年相比、秦皇岛排多少。若存在已点击的澄清候选，优先把它视为已确认的指标或组织输入。字段固定：analysis_type、indicator_inputs、organization_inputs、region_input、date_expression、granularity、top_n、sort_field、sort_direction、request_type。analysis_type 可为 FACT、TREND、RANKING、COMPARISON、RANKING_COMPARISON、DISTRIBUTION、ANOMALY、CORRELATION、DRILLDOWN、EXECUTIVE_OVERVIEW。只输出 JSON。`
  const intentUser = `上一轮分析状态（可能为空）：\n{{#1780919457192.analysis_state#}}\n\n本轮澄清选择（可能为空）：\n{{#1780919457192.clarification_json#}}\n\n当前用户问题：\n{{#sys.query#}}\n\n只输出 JSON 对象，不要 Markdown，不要解释。`

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
  }, {
    variable: 'auth_context_json',
    label: '网关授权上下文',
    type: 'paragraph',
    max_length: 30000,
    options: [],
    required: false,
  }, {
    variable: 'clarification_json',
    label: '澄清候选选择',
    type: 'paragraph',
    max_length: 6000,
    options: [],
    required: false,
  }]
  start.data = startData
  const intent = llmNode('analysis_intent', '业务意图与分析计划草拟', intentSystem, intentUser, 300, 0)
  const plan = codeNode('analysis_plan', '确定性分析计划', planCode, [
    { variable: 'intent_text', value_selector: ['analysis_intent', 'text'], value_type: 'string' },
    { variable: 'question', value_selector: ['sys', 'query'], value_type: 'string' },
    { variable: 'previous_state', value_selector: ['1780919457192', 'analysis_state'], value_type: 'string' },
    { variable: 'authorization_json', value_selector: ['1780919457192', 'auth_context_json'], value_type: 'string' },
    { variable: 'clarification_json', value_selector: ['1780919457192', 'clarification_json'], value_type: 'string' },
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
  const execute = toolNode('analysis_execute', '执行确定性事实查询', ['analysis_resolve', 'query_sql'], 1800, -100, { max_retries: 0, retry_enabled: false, retry_interval: 1000 })
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
