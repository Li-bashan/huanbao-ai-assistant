/*
 * Rebuild the Dify intelligent data-query graph in the already existing app.
 *
 * This file is intentionally a browser-context script.  Dify's console API
 * requires the authenticated browser session and CSRF token, so the caller
 * must execute run() through agent-browser in the target app origin.
 *
 * No credential is stored here.  The existing Dify environment variables are
 * preserved by the script and the database password is never read or printed.
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
  const nodes = original.graph.nodes
  const codeTemplate = nodes.find(node => node.data?.type === 'code')
  const llmTemplate = nodes.find(node => node.data?.type === 'llm')
  const toolTemplate = nodes.find(node => node.data?.type === 'tool')
  const parserTemplate = nodes.find(node => node.id === '1780923024622') || nodes.find(node => node.id === 'rebuild_answer_parser')
  const answerTemplate = nodes.find(node => node.data?.type === 'answer')
  const startTemplate = nodes.find(node => node.data?.type === 'start')
  const ifTemplate = nodes.find(node => node.data?.type === 'if-else') || {
    id: 'if-template',
    position: { x: 0, y: 0 },
    positionAbsolute: { x: 0, y: 0 },
    height: 80,
    width: 242,
    sourcePosition: 'right',
    targetPosition: 'left',
    data: { type: 'if-else', title: '', cases: [], selected: false },
  }

  if (!codeTemplate || !llmTemplate || !toolTemplate || !parserTemplate || !answerTemplate || !startTemplate) {
    throw new Error('当前草稿缺少可复用的 Dify 节点模板，已停止重构')
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
  const llmNode = (id, title, systemText, userText, x, y, contextSelector = []) => {
    const data = cleanData(llmTemplate.data)
    data.type = 'llm'
    data.title = title
    data.context = contextSelector.length
      ? { enabled: true, variable_selector: contextSelector }
      : { enabled: false, variable_selector: [] }
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

  const intentCode = String.raw`
import json
import re
from datetime import date, timedelta

def _clean(value):
    return re.sub(r"\s+", "", str(value or "")).strip()

def _json(text):
    text = re.sub(r"<think>[\s\S]*?</think>", "", str(text or ""), flags=re.I).strip()
    blocks = re.findall(r"[\x60]{3}(?:json)?\s*([\s\S]*?)[\x60]{3}", text, flags=re.I)
    candidates = list(reversed(blocks)) + [text]
    for candidate in candidates:
        try:
            value = json.loads(candidate.strip())
            if isinstance(value, dict):
                return value
        except Exception:
            continue
    return {}

def _first_day(year, month):
    return date(year, month, 1)

def _add_months(value, months):
    index = value.year * 12 + value.month - 1 + months
    return date(index // 12, index % 12 + 1, 1)

def _date_range(question, today):
    text = _clean(question)
    next_month = _add_months(today.replace(day=1), 1)

    m = re.search(r"(\d{4})年(\d{1,2})月(?:至|到|起至|-)\s*(?:(\d{4})年)?(\d{1,2})月", text)
    if m:
        y1, m1 = int(m.group(1)), int(m.group(2))
        y2 = int(m.group(3) or y1)
        m2 = int(m.group(4))
        if not (1 <= m1 <= 12 and 1 <= m2 <= 12):
            return today, today, "month", "invalid_date"
        return _first_day(y1, m1), _add_months(_first_day(y2, m2), 1), "month", "explicit_month_range"

    m = re.search(r"(\d{4})[-年](\d{1,2})月?", text)
    if m:
        y, month = int(m.group(1)), int(m.group(2))
        if not 1 <= month <= 12:
            return today, today, "month", "invalid_date"
        start = _first_day(y, month)
        return start, _add_months(start, 1), "month", "explicit_month"

    m = re.search(r"(\d{4})年", text)
    if m:
        y = int(m.group(1))
        return date(y, 1, 1), date(y + 1, 1, 1), "month", "explicit_year"

    m = re.search(r"近(\d+)个?月", text)
    if m:
        count = max(1, min(int(m.group(1)), 120))
        return _add_months(today.replace(day=1), -count + 1), next_month, "month", "relative_months"

    m = re.search(r"(?:最近|近)(\d+)天", text)
    if m:
        count = max(1, min(int(m.group(1)), 3660))
        return today - timedelta(days=count - 1), today + timedelta(days=1), "day", "relative_days"

    if "今天" in text:
        return today, today + timedelta(days=1), "day", "today"
    if "昨天" in text:
        return today - timedelta(days=1), today, "day", "yesterday"
    if "上月" in text:
        start = _add_months(today.replace(day=1), -1)
        return start, today.replace(day=1), "month", "previous_month"
    if "本月" in text:
        return today.replace(day=1), next_month, "month", "current_month"
    if "去年" in text:
        return date(today.year - 1, 1, 1), date(today.year, 1, 1), "month", "previous_year"
    if re.search(r"今年\d{1,2}月至今", text):
        month = int(re.search(r"今年(\d{1,2})月", text).group(1))
        if not 1 <= month <= 12:
            return today, today, "month", "invalid_date"
        start = _first_day(today.year, month)
        return start, next_month, "month", "year_to_date_month"
    if "今年" in text:
        return date(today.year, 1, 1), date(today.year + 1, 1, 1), "month", "current_year"

    # No hidden LLM default: an unspecified period is made explicit as the current month.
    return today.replace(day=1), next_month, "month", "default_current_month"

def _table_intersection(start, end):
    tables = []
    cursor = start.replace(day=1)
    while cursor < end:
        suffix = "06" if cursor.month <= 6 else "12"
        name = "CGXTAPPMISDate_%04d_%s" % (cursor.year, suffix)
        if name not in tables:
            tables.append(name)
        cursor = date(cursor.year + 1, 1, 1) if cursor.month >= 7 else date(cursor.year, 7, 1)
    return tables

def _terms(value):
    raw = _clean(value)
    terms = []
    for item in [raw, raw.replace("公司", ""), raw.replace("有限公司", ""), raw.replace("本部", "")]:
        item = item.strip()
        if item and item not in terms:
            terms.append(item[:80])
    return terms

def _literal(value):
    return "'" + str(value).replace("'", "''")[:80] + "'"

def _auth_list(value):
    if isinstance(value, str):
        try:
            value = json.loads(value or "[]")
        except Exception:
            value = [item.strip() for item in value.split(",")]
    if not isinstance(value, list):
        return []
    return list(dict.fromkeys(str(item).strip()[:120] for item in value if str(item).strip()))[:1000]

def _auth_context(value):
    try:
        parsed = json.loads(value or "{}") if isinstance(value, str) else (value or {})
    except Exception:
        parsed = {}
    return {
        "allowed_org_codes": _auth_list(parsed.get("allowedOrgCodes")),
        "allowed_indicator_codes": _auth_list(parsed.get("allowedIndicatorCodes")),
        "allow_all_organizations": bool(parsed.get("allowAllOrganizations")),
        "allow_group_ranking": bool(parsed.get("allowGroupRanking")),
    }

def _lookup_sql(indicator_input, organization_input, auth):
    iterms = _terms(indicator_input)
    iconditions = []
    for term in iterms:
        lit = _literal(term)
        iconditions.extend([
            '"newIndicatorname" ILIKE \'%\' || ' + lit + ' || \'%\'',
            '"oldIndicatorname" ILIKE \'%\' || ' + lit + ' || \'%\'',
        ])
    if str(indicator_input or '').strip().isdigit():
        iconditions.append('"newcode" = ' + _literal(str(indicator_input).strip()))
    indicator_where = " OR ".join(iconditions) if iconditions else "1=0"
    allowed_indicators = auth.get("allowed_indicator_codes") or []
    if allowed_indicators:
        indicator_where = "(" + indicator_where + ") AND \"newcode\" IN (" + ",".join(_literal(item) for item in allowed_indicators) + ")"

    oterms = _terms(organization_input)
    oconditions = []
    for term in oterms:
        lit = _literal(term)
        oconditions.append('"Name_CHS" ILIKE \'%\' || ' + lit + ' || \'%\'')
        if re.match(r"^[A-Za-z0-9-]{5,40}$", term):
            oconditions.append('"Code" = ' + lit)
    org_where = " OR ".join(oconditions) if oconditions else "1=0"
    allowed_orgs = auth.get("allowed_org_codes") or []
    if allowed_orgs and not auth.get("allow_all_organizations"):
        org_where = "(" + org_where + ") AND \"Code\" IN (" + ",".join(_literal(item) for item in allowed_orgs) + ")"
    elif not auth.get("allow_all_organizations"):
        org_where = "1=0"
    indicator_sql = (
        'SELECT "newcode" AS code, "newIndicatorname" AS name, '
        'COALESCE("IndicatorUnit", \'\') AS unit, '
        '"newObjectcode" AS object_code, "newObjectname" AS object_name '
        'FROM MSOKFPT."CGXTAPPMISNewIndicator" WHERE ' + indicator_where +
        ' ORDER BY "newcode" LIMIT 100'
    )
    organization_sql = (
        'SELECT "Code" AS code, "Name_CHS" AS name '
        'FROM MSOKFPT."BFAdminOrganization" WHERE ' + org_where +
        ' ORDER BY "Name_CHS" LIMIT 100'
    )
    return indicator_sql, organization_sql

def main(intent_text: str, question: str, auth_context_json: str) -> dict:
    intent = _json(intent_text)
    original_question = str(question or "").strip()
    indicator_input = str(intent.get("indicator_input") or "").strip()
    organization_input = str(intent.get("organization_input") or "").strip()
    date_expression = str(intent.get("date_expression") or "").strip()
    if not indicator_input:
        indicator_input = original_question
    if not organization_input:
        match = re.search(r"([^，。！？,!?\s]{1,40}(?:公司|有限公司|本部))", original_question)
        organization_input = match.group(1) if match else ""

    today = date.today()
    # The LLM may describe intent, but it cannot define the time fact.  Parse
    # the original user question with the deterministic whitelist above.
    start, end, granularity, time_source = _date_range(original_question, today)
    status = "READY"
    if start >= end:
        status = "TIME_PARSE_FAILED"
    elif start >= today + timedelta(days=1):
        status = "FUTURE_TIME"
    tables = _table_intersection(start, end) if status == "READY" else []
    if status == "READY" and not tables:
        status = "NO_ALLOWED_TABLE"

    # This is a maintainable alias registry, not a case branch.  Formal names and
    # codes are still resolved from CGXTAPPMISNewIndicator below.
    alias_registry = {
        "全厂发电量": ["全场发电量", "全厂发了多少电", "总发电量"],
        "全厂上网电量": ["全场上网电量", "上网电量"],
        "全厂下网电量": ["全场下网电量", "下网电量"],
        "综合厂用电量": ["综合厂用电量", "厂用电量"],
        "综合厂用电率": ["综合厂用电率", "厂用电率"],
    }
    normalized_indicator = indicator_input
    for formal, aliases in alias_registry.items():
        if indicator_input == formal or indicator_input in aliases:
            normalized_indicator = formal
            break

    auth = _auth_context(auth_context_json)
    indicator_sql, organization_sql = _lookup_sql(normalized_indicator, organization_input, auth)
    base = {
        "original_question": original_question,
        "current_date": today.isoformat(),
        "query_start_date": start.isoformat(),
        "query_end_date": end.isoformat(),
        "date_granularity": granularity,
        "time_source": time_source,
        "query_years": list(range(start.year, end.year + 1)),
        "allowed_tables": tables,
        "data_cutoff_date": "",
        "organization": {"input_name": organization_input, "standard_name": "", "code": "", "status": "PENDING", "candidates": []},
        "indicator": {"input_name": indicator_input, "standard_name": normalized_indicator, "code": "", "unit": "", "type": "base", "aggregation": "", "status": "PENDING"},
        "formula": None,
        "formula_source": "",
        "authorized_indicator_codes": [],
        "allowed_org_codes": auth.get("allowed_org_codes", []),
        "allowed_indicator_codes": auth.get("allowed_indicator_codes", []),
        "allow_all_organizations": auth.get("allow_all_organizations", False),
        "allow_group_ranking": auth.get("allow_group_ranking", False),
        "has_indicator_scope": bool(auth.get("allowed_indicator_codes")),
        "status": status,
        "request_type": str(intent.get("request_type") or "aggregate"),
    }
    return {"base_json": json.dumps(base, ensure_ascii=False), "indicator_lookup_sql": indicator_sql, "organization_lookup_sql": organization_sql, "status": status}
`

  const contextCode = String.raw`
import json
import re

AGGREGATION_REGISTRY = {
    # Confirmed from the live data sample: one daily 1001 row per date,
    # numeric ZBZ, therefore period totals use SUM of daily values.
    "1001": {"aggregation": "SUM", "source": "live sample: daily value"},
}

def _rows(value):
    if isinstance(value, str):
        try:
            return _rows(json.loads(value))
        except Exception:
            return []
    if isinstance(value, dict):
        if isinstance(value.get("json"), (list, dict)):
            return _rows(value.get("json"))
        if isinstance(value.get("result"), list):
            return [row for row in value["result"] if isinstance(row, dict)]
        if isinstance(value.get("data"), (list, dict)):
            return _rows(value.get("data"))
        if "code" in value or "Code" in value:
            return [value]
        return []
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(_rows(item))
        return result
    return []

def _error(value):
    return str(value or "").strip()

def main(base_json: str, indicator_data, indicator_error: str, organization_data, organization_error: str) -> dict:
    base = json.loads(base_json or "{}")
    indicator_rows = _rows(indicator_data)
    organization_rows = _rows(organization_data)
    indicator_error = _error(indicator_error)
    organization_error = _error(organization_error)

    indicators = []
    seen = set()
    for row in indicator_rows:
        code = str(row.get("code") or row.get("newcode") or row.get("newCode") or "").strip()
        name = str(row.get("name") or row.get("newIndicatorname") or "").strip()
        if not code or not name or (code, name) in seen:
            continue
        seen.add((code, name))
        indicators.append({"code": code, "name": name, "unit": str(row.get("unit") or "").strip()})

    organizations = []
    seen_org = set()
    for row in organization_rows:
        code = str(row.get("code") or row.get("Code") or "").strip()
        name = str(row.get("name") or row.get("Name_CHS") or "").strip()
        if code and (code, name) not in seen_org:
            seen_org.add((code, name))
            organizations.append({"code": code, "name": name})

    base["organization"]["candidates"] = organizations
    base["indicator"]["candidates"] = indicators
    status = str(base.get("status") or "READY")
    if status == "READY" and (indicator_error or organization_error):
        status = "SQL_METADATA_LOOKUP_FAILED"

    input_name = str(base.get("indicator", {}).get("input_name") or "").strip()
    standard_name = str(base.get("indicator", {}).get("standard_name") or "").strip()
    exact = [row for row in indicators if row["code"] == input_name or row["name"] == input_name or row["name"] == standard_name]
    if not exact:
        exact = [row for row in indicators if standard_name and standard_name in row["name"]]
    codes = list(dict.fromkeys(row["code"] for row in exact))
    if status == "READY":
        if not indicators:
            status = "INDICATOR_NOT_FOUND"
            base["indicator"]["status"] = "NOT_FOUND"
        elif len(codes) != 1:
            status = "INDICATOR_AMBIGUOUS"
            base["indicator"]["status"] = "AMBIGUOUS"
        else:
            chosen = next(row for row in indicators if row["code"] == codes[0])
            base["indicator"].update({"code": chosen["code"], "standard_name": chosen["name"], "unit": chosen["unit"], "status": "RESOLVED"})
            registry = AGGREGATION_REGISTRY.get(chosen["code"])
            if registry:
                base["indicator"].update(registry)
                base["indicator"]["status"] = "RESOLVED"
            else:
                status = "AGGREGATION_NOT_CONFIRMED"
            base["authorized_indicator_codes"] = [chosen["code"]]
            allowed_indicator_codes = base.get("allowed_indicator_codes") or []
            if allowed_indicator_codes and chosen["code"] not in allowed_indicator_codes:
                status = "DATA_SCOPE_DENIED"
                base["indicator"]["status"] = "DENIED"

    org_input = str(base.get("organization", {}).get("input_name") or "").strip()
    if status == "READY" and org_input:
        if len(organizations) == 0:
            if not base.get("allow_all_organizations"):
                status = "DATA_SCOPE_DENIED"
                base["organization"]["status"] = "DENIED"
            else:
                status = "ORGANIZATION_NOT_FOUND"
                base["organization"]["status"] = "NOT_FOUND"
        elif len(organizations) > 1:
            status = "ORGANIZATION_AMBIGUOUS"
            base["organization"]["status"] = "AMBIGUOUS"
        else:
            chosen_org = organizations[0]
            base["organization"].update({"standard_name": chosen_org["name"], "code": chosen_org["code"], "status": "RESOLVED"})
    elif status == "READY":
        base["organization"].update({"standard_name": "全部组织", "status": "ALL"})
        if not base.get("allow_all_organizations") and not base.get("allowed_org_codes"):
            status = "DATA_SCOPE_DENIED"

    request_type = str(base.get("request_type") or "")
    if status == "READY" and (request_type == "calculation" or re.search(r"(?:同比|环比|率|效率|占比|平均)", input_name)):
        status = "FORMULA_NOT_FOUND"
        base["formula_source"] = "未找到已确认的公式注册项"

    base["status"] = status
    base["audit_stage"] = "context_resolved"
    return {
        "context_json": json.dumps(base, ensure_ascii=False),
        "status": status,
        "query_start_date": base.get("query_start_date", ""),
        "query_end_date": base.get("query_end_date", ""),
        "allowed_tables": json.dumps(base.get("allowed_tables", []), ensure_ascii=False),
        "organization_code": base.get("organization", {}).get("code", ""),
        "indicator_code": base.get("indicator", {}).get("code", ""),
        "indicator_name": base.get("indicator", {}).get("standard_name", ""),
        "aggregation": base.get("indicator", {}).get("aggregation", ""),
        "unit": base.get("indicator", {}).get("unit", ""),
        "data_cutoff_date": "",
    }
`

  const sqlCode = String.raw`
import json
import re

DANGEROUS = ("insert", "update", "delete", "drop", "truncate", "alter", "create", "grant", "revoke", "copy")

def _lit(value):
    return "'" + str(value).replace("'", "''") + "'"

def _numeric():
    return "CASE WHEN TRIM(\"ZBZ\") ~ '^[+-]?[0-9]+(\\.[0-9]+)?$' THEN CAST(TRIM(\"ZBZ\") AS NUMERIC(20,4)) ELSE NULL END"

def _build(context):
    tables = context.get("allowed_tables") or []
    indicator = context.get("indicator", {})
    organization = context.get("organization", {})
    code = str(indicator.get("code") or "")
    start = str(context.get("query_start_date") or "")
    end = str(context.get("query_end_date") or "")
    aggregation = str(indicator.get("aggregation") or "")
    allowed_org_codes = [str(item).strip() for item in (context.get("allowed_org_codes") or []) if re.match(r"^[A-Za-z0-9_-]{1,120}$", str(item).strip())]
    allowed_indicator_codes = [str(item).strip() for item in (context.get("allowed_indicator_codes") or []) if re.match(r"^[A-Za-z0-9_-]{1,120}$", str(item).strip())]
    allow_all_organizations = bool(context.get("allow_all_organizations"))
    if allowed_indicator_codes and code not in allowed_indicator_codes:
        return "", "DATA_SCOPE_DENIED", "当前账号无权查询该指标"
    if not allow_all_organizations and not allowed_org_codes:
        return "", "DATA_SCOPE_DENIED", "当前账号没有可查询的组织范围"
    if context.get("status") != "READY":
        return "", "SQL_VALIDATION_FAILED", "查询上下文状态不是 READY，禁止执行 SQL"
    if not tables or not code or not start or not end or aggregation != "SUM":
        return "", "SQL_VALIDATION_FAILED", "查询上下文未达到可执行条件"
    org_status = str(organization.get("status") or "")
    if organization.get("input_name") and org_status != "RESOLVED":
        return "", "SQL_VALIDATION_FAILED", "组织未唯一解析，禁止执行 SQL"
    selects = []
    for table in tables:
        if not re.match(r"^CGXTAPPMISDate_\d{4}_(?:06|12)$", table):
            return "", "SQL_VALIDATION_FAILED", "allowed_tables 含有非法表名"
        selects.append('SELECT "ZBRQ","ZBZ","newIndicator","orgcode","ZBBM","Version","dr_falg" FROM MSOKFPT."' + table + '"')
    org_filter = ""
    if organization.get("status") == "RESOLVED":
        org_code = str(organization.get("code") or "")
        if not re.match(r"^[A-Za-z0-9-]{5,40}$", org_code):
            return "", "SQL_VALIDATION_FAILED", "resolved organization code 非法"
        if not allow_all_organizations and org_code not in allowed_org_codes:
            return "", "DATA_SCOPE_DENIED", "当前账号无权查询该组织"
        org_filter = ' AND "orgcode" = ' + _lit(org_code)
    elif not allow_all_organizations:
        org_filter = ' AND "orgcode" IN (' + ",".join(_lit(item) for item in allowed_org_codes) + ")"
    union = " UNION ALL ".join(selects)
    where = '"newIndicator" = ' + _lit(code) + ' AND "ZBRQ" >= DATE ' + _lit(start) + ' AND "ZBRQ" < DATE ' + _lit(end) + org_filter
    granularity = str(context.get("date_granularity") or "month")
    if granularity == "day":
        period = 'TO_CHAR("ZBRQ", \'YYYY-MM-DD\')'
    elif granularity == "year":
        period = 'TO_CHAR("ZBRQ", \'YYYY\')'
    elif granularity == "month":
        period = 'TO_CHAR("ZBRQ", \'YYYY-MM\')'
    else:
        period = "'total'"
    number = _numeric()
    sql = (
        'WITH base AS (' + union + '), filtered AS (SELECT * FROM base WHERE ' + where + '), '
        'cutoff AS (SELECT MAX("ZBRQ") AS data_cutoff_date, COUNT(*) AS raw_rows, '
        'SUM(CASE WHEN TRIM("ZBZ") !~ \'^[+-]?[0-9]+(\\.[0-9]+)?$\' THEN 1 ELSE 0 END) AS invalid_value_count, '
        '(SELECT COUNT(*) FROM (SELECT "orgcode","newIndicator","ZBRQ" FROM filtered GROUP BY "orgcode","newIndicator","ZBRQ" HAVING COUNT(*) > 1) d) AS duplicate_key_groups FROM filtered), '
        'aggregated AS (SELECT ' + period + ' AS period, SUM(' + number + ') AS value, COUNT(*) AS row_count, '
        'COUNT(DISTINCT "ZBBM") AS dimension_count, MAX("ZBRQ") AS period_data_cutoff_date FROM filtered '
        + (" GROUP BY " + period if period != "'total'" else "") + '), '
        'final AS (SELECT a.*, c.data_cutoff_date, c.raw_rows, c.invalid_value_count, c.duplicate_key_groups FROM aggregated a CROSS JOIN cutoff c) '
        'SELECT * FROM final ORDER BY period LIMIT 1000'
    )

    if not re.match(r"^(?:WITH|SELECT)\b", sql, flags=re.I) or ";" in sql or re.search(r"\b(?:CURRENT_DATE|NOW|CURRENT_TIMESTAMP)\b", sql, flags=re.I):
        return "", "SQL_VALIDATION_FAILED", "SQL 不是单条安全 SELECT 或使用了动态时间函数"
    if any(re.search(r"\b" + word + r"\b", sql, flags=re.I) for word in DANGEROUS):
        return "", "SQL_VALIDATION_FAILED", "SQL 含有写操作或 DDL 关键字"
    if code not in sql or start not in sql or end not in sql:
        return "", "SQL_VALIDATION_FAILED", "SQL 未完整绑定指标或固定时间边界"
    return sql, "SQL_VALIDATED", "表、日期、指标、组织、聚合和安全规则校验通过"

def main(context_json: str) -> dict:
    context = json.loads(context_json or "{}")
    sql, status, message = _build(context)
    audit = {"status": status, "message": message, "allowed_tables": context.get("allowed_tables", []), "query_start_date": context.get("query_start_date", ""), "query_end_date": context.get("query_end_date", ""), "authorized_indicator_codes": context.get("authorized_indicator_codes", []), "organization_code": context.get("organization", {}).get("code", ""), "aggregation": context.get("indicator", {}).get("aggregation", "")}
    return {"sql": sql, "business_status": status, "can_execute": 1 if status == "SQL_VALIDATED" else 0, "validation_message": message, "audit_json": json.dumps(audit, ensure_ascii=False), "query_context": json.dumps(context, ensure_ascii=False)}
`

  const resultCode = String.raw`
import json
import re

def _rows(value):
    if isinstance(value, str):
        try:
            return _rows(json.loads(value))
        except Exception:
            return []
    if isinstance(value, dict):
        if isinstance(value.get("json"), (list, dict)):
            return _rows(value.get("json"))
        if isinstance(value.get("result"), list):
            return [x for x in value["result"] if isinstance(x, dict)]
        if isinstance(value.get("data"), (list, dict)):
            return _rows(value.get("data"))
        return [value] if "period" in value or "data_cutoff_date" in value else []
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(_rows(item))
        return result
    return []

def _date(value):
    return str(value or "").replace(" 00:00:00", "")[:10]

def _period_start(value):
    text = str(value or "").strip()
    if text == "total" or not text:
        return text
    if re.match(r"^\d{4}-\d{2}$", text):
        return text + "-01"
    if re.match(r"^\d{4}$", text):
        return text + "-01-01"
    return _date(text)

def _status_message(status, context):
    organization = context.get("organization", {}) or {}
    indicator = context.get("indicator", {}) or {}
    candidates = organization.get("candidates", []) or []
    indicator_candidates = indicator.get("candidates", []) or []
    if status == "ORGANIZATION_AMBIGUOUS":
        lines = ["组织存在多个候选，请选择："]
        lines.extend("- %s（Code: %s）" % (item.get("name", ""), item.get("code", "")) for item in candidates)
        return "\n".join(lines)
    if status == "INDICATOR_AMBIGUOUS":
        lines = ["指标存在多个候选，请选择："]
        lines.extend("- %s（Code: %s）" % (item.get("name", ""), item.get("code", "")) for item in indicator_candidates)
        return "\n".join(lines)
    if status == "ORGANIZATION_NOT_FOUND":
        return "未找到匹配的组织，请提供更准确的组织名称。"
    if status == "INDICATOR_NOT_FOUND":
        return "未找到匹配的指标，请提供制度字典中的正式指标名称或编码。"
    if status == "TIME_PARSE_FAILED":
        return "无法确定查询时间范围，请补充明确的日期或月份。"
    if status == "FUTURE_TIME":
        return "查询时间范围包含当前日期之后的时间，暂不能返回未来数据。"
    if status == "DATA_SCOPE_DENIED":
        return "当前账号无权查询该组织或指标，未执行查询。"
    if status == "FORMULA_NOT_FOUND":
        return "该查询涉及公式或派生指标，但当前没有已确认的公式定义，未执行计算。"
    if status == "AGGREGATION_NOT_CONFIRMED":
        return "该指标的聚合口径尚未确认，未执行汇总。"
    if status == "NO_DATA_IN_PERIOD":
        return "在指定时间范围内没有查到数据。"
    if status == "SUCCESS_EMPTY":
        return "查询已完成，但结果为空或无法形成可信数值。"
    if status in ("SQL_VALIDATION_FAILED", "SQL_METADATA_LOOKUP_FAILED", "SQL_EXECUTION_FAILED", "RESULT_VALIDATION_FAILED"):
        return "查询未返回可信结果，系统已阻止展示未经校验的数据。"
    return ""

def main(context_json: str, business_status: str, execution_data, execution_error: str) -> dict:
    context = json.loads(context_json or "{}")
    status = str(business_status or context.get("status") or "SQL_VALIDATION_FAILED")
    error = str(execution_error or "").strip()
    rows = _rows(execution_data)
    if status != "SQL_VALIDATED":
        final_status = context.get("status") or status
        rows = []
    elif error:
        final_status = "SQL_EXECUTION_FAILED"
    elif not rows:
        final_status = "NO_DATA_IN_PERIOD"
    else:
        valid_rows = []
        start = _date(context.get("query_start_date"))
        end = _date(context.get("query_end_date"))
        bad = []
        for row in rows:
            period = _period_start(row.get("period"))
            if period and (period < start or period >= end) and period != "total":
                bad.append("period_out_of_range")
            if "value" not in row and "data_cutoff_date" not in row:
                bad.append("missing_value")
            valid_rows.append(row)
        if bad:
            final_status = "RESULT_VALIDATION_FAILED"
        else:
            cutoff = _date(rows[0].get("data_cutoff_date"))
            if not cutoff or all(row.get("value") is None for row in rows):
                final_status = "SUCCESS_EMPTY"
            elif any(int(row.get("invalid_value_count") or 0) > 0 for row in rows):
                final_status = "RESULT_VALIDATION_FAILED"
            elif any(int(row.get("duplicate_key_groups") or 0) > 0 for row in rows):
                final_status = "RESULT_VALIDATION_FAILED"
            else:
                final_status = "SUCCESS_WITH_DATA"

    cutoff = ""
    if rows:
        cutoff = _date(rows[0].get("data_cutoff_date"))
    message = _status_message(final_status, context)
    audit = {
        "status": final_status,
        "message": message,
        "row_count": len(rows),
        "data_cutoff_date": cutoff,
        "query_start_date": context.get("query_start_date", ""),
        "query_end_date": context.get("query_end_date", ""),
        "indicator": context.get("indicator", {}),
        "organization": context.get("organization", {}),
        "execution_error": error,
        "rows": rows[:500],
    }
    return {"result_text": json.dumps(audit, ensure_ascii=False), "status": final_status, "audit_json": json.dumps(audit, ensure_ascii=False), "row_count": len(rows), "data_cutoff_date": cutoff}
`

  const intentSystem = `你只负责把当前用户问题抽取成结构化意图，不查数据库，不生成 SQL，不补全业务事实，不使用历史对话。只输出一个 JSON 对象，字段必须完整：indicator_input、organization_input、date_expression、granularity、request_type。granularity 只能是 day、month、year、total、auto；request_type 只能是 aggregate、detail、trend、calculation、unknown。无法确定时留空或填 unknown，禁止猜测。`
  const intentUser = `当前用户问题：\n{{#sys.query#}}\n\n只输出 JSON 对象，不要 Markdown，不要解释。`
  const answerSystem = `你是企业生产数据查询结果回答器。你只能根据上下文 JSON 生成答案，不能重新猜指标、组织、日期、表、公式或聚合方式，不能展示 SQL、数据库连接信息或思考过程。上下文中的 status 不是 SUCCESS_WITH_DATA 时，必须明确说明对应状态；如果上下文提供了 message，results 必须逐字复制 message，不得改写候选名称、Code、状态或错误原因；没有 message 时才可以用上下文已有字段做简短说明。ORGANIZATION_AMBIGUOUS 要列候选组织让用户选择；INDICATOR_AMBIGUOUS 要列候选指标；DATA_SCOPE_DENIED、INDICATOR_NOT_FOUND、ORGANIZATION_NOT_FOUND、TIME_PARSE_FAILED、NO_ALLOWED_TABLE、FORMULA_NOT_FOUND、SQL_VALIDATION_FAILED、SQL_EXECUTION_FAILED、RESULT_VALIDATION_FAILED、NO_DATA_IN_PERIOD、SUCCESS_EMPTY 都不能编造数值。只有 SUCCESS_WITH_DATA 才能引用 rows 中的 value，并在有 data_cutoff_date 时说明数据更新至该日期。公式只能展示上下文中明确存在的 formula。输出严格 JSON 对象，字段固定为 results、ECharts、chartType、chartTitle、chartData、chartXAxis；ECharts 只能是 0 或 1，不能有其它字段。`
  const answerUser = `用户问题：\n{{#sys.query#}}\n\n请依据上下文生成中文回答。`

  const startData = cleanData(startTemplate.data)
  startData.variables = [
    'auth_context_json',
    'auth_data_scope_json',
    'auth_user_id',
    'auth_user_code',
    'auth_user_name',
    'auth_org_code',
    'auth_org_name',
    'auth_tenant_id',
    'auth_allowed_org_codes',
    'auth_allowed_indicator_codes',
    'auth_allow_group_ranking',
    'auth_allow_all_organizations',
  ].map(variable => ({
    variable,
    label: `Gateway ${variable}`,
    type: 'text-input',
    required: variable === 'auth_context_json',
    max_length: 12000,
    default: '',
  }))
  const start = wrapper(startTemplate, '1780919457192', startData, 0, 0, 73)
  const intent = llmNode('rebuild_intent', '用户问题结构化解析', intentSystem, intentUser, 300, 0)
  const preContext = codeNode('rebuild_pre_context', '时间范围与元数据查询准备', intentCode, [
    { variable: 'intent_text', value_selector: ['rebuild_intent', 'text'], value_type: 'string' },
    { variable: 'question', value_selector: ['sys', 'query'], value_type: 'string' },
    { variable: 'auth_context_json', value_selector: ['1780919457192', 'auth_context_json'], value_type: 'string' },
  ], {
    base_json: { children: null, type: 'string' },
    indicator_lookup_sql: { children: null, type: 'string' },
    organization_lookup_sql: { children: null, type: 'string' },
    status: { children: null, type: 'string' },
  }, 600, 0)
  const indicatorLookup = toolNode('rebuild_indicator_lookup', '查询数据库指标字典', ['rebuild_pre_context', 'indicator_lookup_sql'], 900, -150)
  const organizationLookup = toolNode('rebuild_organization_lookup', '查询组织候选', ['rebuild_pre_context', 'organization_lookup_sql'], 900, 150)
  const context = codeNode('rebuild_context', '统一查询上下文', contextCode, [
    { variable: 'base_json', value_selector: ['rebuild_pre_context', 'base_json'], value_type: 'string' },
    { variable: 'indicator_data', value_selector: ['rebuild_indicator_lookup', 'json'], value_type: 'object' },
    { variable: 'indicator_error', value_selector: ['rebuild_indicator_lookup', 'error_message'], value_type: 'string' },
    { variable: 'organization_data', value_selector: ['rebuild_organization_lookup', 'json'], value_type: 'object' },
    { variable: 'organization_error', value_selector: ['rebuild_organization_lookup', 'error_message'], value_type: 'string' },
  ], {
    context_json: { children: null, type: 'string' },
    status: { children: null, type: 'string' },
    query_start_date: { children: null, type: 'string' },
    query_end_date: { children: null, type: 'string' },
    allowed_tables: { children: null, type: 'string' },
    organization_code: { children: null, type: 'string' },
    indicator_code: { children: null, type: 'string' },
    indicator_name: { children: null, type: 'string' },
    aggregation: { children: null, type: 'string' },
    unit: { children: null, type: 'string' },
    data_cutoff_date: { children: null, type: 'string' },
  }, 1200, 0)
  const sql = codeNode('rebuild_sql', '生成并校验受约束 SQL', sqlCode, [
    { variable: 'context_json', value_selector: ['rebuild_context', 'context_json'], value_type: 'string' },
  ], {
    sql: { children: null, type: 'string' },
    business_status: { children: null, type: 'string' },
    can_execute: { children: null, type: 'number' },
    validation_message: { children: null, type: 'string' },
    audit_json: { children: null, type: 'string' },
    query_context: { children: null, type: 'string' },
  }, 1500, 0)
  const gateData = cleanData(ifTemplate.data || { type: 'if-else', title: 'SQL 业务校验是否通过', cases: [] })
  gateData.type = 'if-else'
  gateData.title = 'SQL 业务校验是否通过'
  gateData.cases = [{
    case_id: 'true',
    id: 'rebuild-sql-validated',
    logical_operator: 'and',
    conditions: [{ comparison_operator: '>', id: 'rebuild-condition', value: '0', variable_selector: ['rebuild_sql', 'can_execute'] }],
  }]
  const gate = wrapper(ifTemplate, 'rebuild_gate', gateData, 1800, 0, 80)
  const execute = toolNode('rebuild_execute', '执行受约束 SQL', ['rebuild_sql', 'sql'], 2100, -100)
  const result = codeNode('rebuild_result', '结果合理性校验与状态归一', resultCode, [
    { variable: 'context_json', value_selector: ['rebuild_context', 'context_json'], value_type: 'string' },
    { variable: 'business_status', value_selector: ['rebuild_sql', 'business_status'], value_type: 'string' },
    { variable: 'execution_data', value_selector: ['rebuild_execute', 'json'], value_type: 'object' },
    { variable: 'execution_error', value_selector: ['rebuild_execute', 'error_message'], value_type: 'string' },
  ], {
    result_text: { children: null, type: 'string' },
    status: { children: null, type: 'string' },
    audit_json: { children: null, type: 'string' },
    row_count: { children: null, type: 'number' },
    data_cutoff_date: { children: null, type: 'string' },
  }, 2400, 0)
  const answer = llmNode('rebuild_answer', '受约束结果回答', answerSystem, answerUser, 2700, 0, ['rebuild_result', 'result_text'])
  const safeParserCode = String.raw`
import json

def _load(value):
    if isinstance(value, dict):
        return value
    try:
        parsed = json.loads(str(value or "{}"))
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}

def _number(value):
    if value is None:
        return ""
    if isinstance(value, bool):
        return ""
    try:
        number = float(value)
        if number.is_integer():
            return str(int(number))
        return ("%.4f" % number).rstrip("0").rstrip(".")
    except Exception:
        return str(value).strip()

def _text(value):
    return str(value or "").replace("|", "\\|").replace("\\n", " ").strip()

def _protocol(audit):
    status = str(audit.get("status") or "RESULT_VALIDATION_FAILED")
    message = str(audit.get("message") or "").strip()
    indicator = audit.get("indicator", {}) or {}
    organization = audit.get("organization", {}) or {}
    rows = [row for row in audit.get("rows", []) if isinstance(row, dict)]
    title = str(indicator.get("standard_name") or indicator.get("input_name") or "指标")
    start = str(audit.get("query_start_date") or "")
    end = str(audit.get("query_end_date") or "")
    cutoff = str(audit.get("data_cutoff_date") or "")
    unit = str(indicator.get("unit") or "")
    values = [row.get("value") for row in rows if row.get("value") is not None]
    categories = [_text(row.get("period")) for row in rows if _text(row.get("period"))]
    valid_rows = [{"period": _text(row.get("period")), "value": row.get("value"), "rowCount": row.get("row_count")} for row in rows]
    is_success = status == "SUCCESS_WITH_DATA"
    response = {
        "protocolVersion": "2.0",
        "requestId": "",
        "conversationId": "",
        "status": status if is_success else ("SUCCESS_EMPTY" if status == "NO_DATA_IN_PERIOD" else status),
        "messageType": "analysis" if is_success else ("empty" if status == "NO_DATA_IN_PERIOD" else "information"),
        "analysisType": "TREND" if len(categories) > 1 else "FACT",
        "content": {
            "title": title,
            "summary": message if not is_success else "%s查询完成，共返回%s个数据点。" % (title, len(valid_rows)),
            "metrics": ([{"label": "数据点", "value": len(values), "unit": "个"}] if is_success else []),
            "table": ({"columns": [{"key": "period", "label": "周期", "type": "text"}, {"key": "value", "label": "数值", "type": "number", "unit": unit}, {"key": "rowCount", "label": "原始行数", "type": "number"}], "rows": valid_rows[:1000], "total": len(valid_rows), "defaultVisibleRows": 10} if is_success else None),
            "chart": ({"type": "line", "title": title, "categories": categories, "series": [{"name": title, "type": "line", "data": [row.get("value") for row in valid_rows]}]} if len(categories) > 1 else None),
            "insights": [],
            "evidence": [],
            "dataInfo": {"indicatorName": title, "unit": unit, "timeRange": {"start": start, "end": end, "endExclusive": True}, "dataCutoffDate": cutoff, "aggregation": str(indicator.get("aggregation") or ""), "organizationScope": str(organization.get("standard_name") or organization.get("input_name") or "全部组织"), "rowCount": len(rows)},
            "followUps": [],
        },
        "clarification": None,
        "meta": {"legacyWorkflow": True, "auditStatus": status},
    }
    return response

def main(args: str, audit_json: str) -> dict:
    audit = _load(audit_json)
    response = _protocol(audit)
    chart = response["content"].get("chart") or {}
    chart_data = chart.get("series", [{}])[0].get("data", []) if chart else []
    chart_axis = chart.get("categories", []) if chart else []
    text = json.dumps(response, ensure_ascii=False, separators=(",", ":"))
    return {"results": text, "text": text, "protocol_json": text, "ECharts": "1" if chart else "0", "chartType": chart.get("type", "") if chart else "", "chartTitle": chart.get("title", "") if chart else "", "chartData": json.dumps(chart_data, ensure_ascii=False), "chartXAxis": json.dumps(chart_axis, ensure_ascii=False)}
`
  const parserData = cleanData(parserTemplate.data)
  parserData.type = 'code'
  parserData.title = '解析回答 JSON'
  parserData.code = safeParserCode
  parserData.code_language = 'python3'
  parserData.outputs = {
    results: { children: null, type: 'string' },
    text: { children: null, type: 'string' },
    ECharts: { children: null, type: 'string' },
    chartType: { children: null, type: 'string' },
    chartTitle: { children: null, type: 'string' },
    chartData: { children: null, type: 'string' },
    chartXAxis: { children: null, type: 'string' },
    protocol_json: { children: null, type: 'string' },
  }
  parserData.variables = [
    { variable: 'args', value_selector: ['rebuild_answer', 'text'], value_type: 'string' },
    { variable: 'audit_json', value_selector: ['rebuild_result', 'audit_json'], value_type: 'string' },
  ]
  const parser = wrapper(parserTemplate, 'rebuild_answer_parser', parserData, 3000, 0, 52)
  const finalData = cleanData(answerTemplate.data)
  finalData.type = 'answer'
  finalData.title = '输出最终回答'
  finalData.answer = '{{#rebuild_answer_parser.text#}}'
  const finalAnswer = wrapper(answerTemplate, 'final_answer', finalData, 3300, 0, 102)

  const newNodes = [start, intent, preContext, indicatorLookup, organizationLookup, context, sql, gate, execute, result, answer, parser, finalAnswer]
  const newEdges = [
    edge('1780919457192', 'rebuild_intent', 'start', 'llm'),
    edge('rebuild_intent', 'rebuild_pre_context', 'llm', 'code'),
    edge('rebuild_pre_context', 'rebuild_indicator_lookup', 'code', 'tool'),
    edge('rebuild_pre_context', 'rebuild_organization_lookup', 'code', 'tool'),
    edge('rebuild_pre_context', 'rebuild_context', 'code', 'code'),
    edge('rebuild_indicator_lookup', 'rebuild_context', 'tool', 'code'),
    edge('rebuild_indicator_lookup', 'rebuild_context', 'tool', 'code', 'fail-branch'),
    edge('rebuild_organization_lookup', 'rebuild_context', 'tool', 'code'),
    edge('rebuild_organization_lookup', 'rebuild_context', 'tool', 'code', 'fail-branch'),
    edge('rebuild_context', 'rebuild_sql', 'code', 'code'),
    edge('rebuild_sql', 'rebuild_gate', 'code', 'if-else'),
    edge('rebuild_gate', 'rebuild_execute', 'if-else', 'tool', 'true'),
    edge('rebuild_gate', 'rebuild_result', 'if-else', 'code', 'false'),
    edge('rebuild_execute', 'rebuild_result', 'tool', 'code'),
    edge('rebuild_execute', 'rebuild_result', 'tool', 'code', 'fail-branch'),
    edge('rebuild_result', 'rebuild_answer', 'code', 'llm'),
    edge('rebuild_answer', 'rebuild_answer_parser', 'llm', 'code'),
    edge('rebuild_answer_parser', 'final_answer', 'code', 'answer'),
  ]
  const draft = deepCopy(original)
  draft.graph.nodes = newNodes
  draft.graph.edges = newEdges
  draft.graph.viewport = { x: 0, y: 0, zoom: 0.75 }
  const saved = await saveDraft(draft)
  if (saved.status !== 200) {
    throw new Error(`保存重构草稿失败: ${saved.status}`)
  }
  return {
    status: saved.status,
    nodeCount: newNodes.length,
    edgeCount: newEdges.length,
    titles: newNodes.map(node => node.data?.title).filter(Boolean),
    hashPresent: !!saved.body?.hash,
  }
}

run()
