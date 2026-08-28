const runtimeEnv = typeof import.meta.env === 'object' ? import.meta.env : {}

const DEFAULT_CAPABILITIES = {
  yearly: true,
  yoy: true,
  regionCompare: true,
  companyRank: true,
  monthly: true,
}

function createIndicator(id, name, group, aliases = [], capabilities = {}) {
  return {
    id,
    name,
    group,
    aliases,
    unit: '',
    capabilities: { ...DEFAULT_CAPABILITIES, ...capabilities },
  }
}

export const DATA_QUERY_INDICATOR_GROUPS = [
  {
    key: 'output',
    label: '产量指标',
    indicators: [
      createIndicator('waste_inbound', '生活垃圾入厂量', '产量指标', [
        '入厂垃圾量', '垃圾入厂量', '垃圾进厂量', '生活垃圾量', '垃圾量', '入厂量',
      ]),
      createIndicator('power_generation', '发电量', '产量指标', [
        '全厂发电量', '总发电量', '发电总量', '发电', '电量',
      ]),
      createIndicator('grid_power', '上网电量', '产量指标', ['上网电', '上网量', '并网电量']),
      createIndicator('steam_supply', '外供汽量', '产量指标', ['供汽量', '外供蒸汽量', '供汽']),
      createIndicator('heat_supply', '外供热量', '产量指标', ['供热量', '外供热']),
      createIndicator('sludge_inbound', '污泥入厂量', '产量指标', ['污泥量', '污泥进厂量']),
      createIndicator('food_waste_inbound', '餐厨厨余入厂量', '产量指标', ['餐厨量', '厨余量', '餐厨厨余量']),
      createIndicator('bottom_ash', '炉渣产生量', '产量指标', ['炉渣量', '产渣量']),
    ],
  },
  {
    key: 'efficiency',
    label: '效率指标',
    indicators: [
      createIndicator('auxiliary_power_rate', '综合厂用电率', '效率指标', ['厂用电率', '厂用电']),
      createIndicator('power_per_ton_inbound', '吨入厂发电量', '效率指标', ['吨发电量', '吨发电']),
      createIndicator('grid_power_per_ton_inbound', '吨入厂上网电量', '效率指标', ['吨上网电量', '吨上网']),
      createIndicator('boiler_runtime', '锅炉平均运行时间', '效率指标', ['锅炉运行时间', '锅炉时间']),
      createIndicator('power_load_rate', '发电负荷率', '效率指标', ['负荷率', '发电负荷']),
      createIndicator('steam_per_ton_inbound', '吨入炉产汽量', '效率指标', ['吨产汽量', '吨入炉产汽']),
      createIndicator('steam_consumption_rate', '汽耗率', '效率指标', ['汽耗']),
      createIndicator('waste_inbound_rate', '垃圾入厂率', '效率指标', ['入厂率', '垃圾进厂率']),
      createIndicator('leachate_rate', '渗沥液产生率', '效率指标', ['渗滤液产生率', '渗沥液率']),
      createIndicator('ash_rate', '产渣产生率', '效率指标', ['产渣率']),
      createIndicator('raw_ash_rate', '原灰产生率', '效率指标'),
      createIndicator('food_oil_rate', '协同餐厨提油率', '效率指标'),
      createIndicator('steam_auxiliary_power_rate', '折汽综合厂用电率', '效率指标'),
      createIndicator('steam_power_per_ton_inbound', '折汽吨入厂发电量', '效率指标'),
      createIndicator('steam_grid_power_per_ton_inbound', '折汽吨入厂上网电量', '效率指标'),
      createIndicator('steam_consumption_rate_equivalent', '折汽汽耗率', '效率指标'),
      createIndicator('fly_ash_outbound_rate', '外运飞灰产生率（入厂）', '效率指标'),
      createIndicator('bottom_ash_rate_inbound', '炉渣产生率（入厂）', '效率指标'),
    ],
  },
  {
    key: 'fuel',
    label: '燃料动力类指标',
    indicators: [
      createIndicator('diesel_consumption', '柴油消耗量', '燃料动力类指标'),
      createIndicator('diesel_unit_consumption', '柴油单耗', '燃料动力类指标'),
      createIndicator('natural_gas_consumption', '天然气消耗量', '燃料动力类指标', ['天然气量', '燃气量']),
      createIndicator('natural_gas_unit_consumption', '天然气单耗', '燃料动力类指标', ['燃气单耗']),
      createIndicator('production_water', '生产用水量', '燃料动力类指标', ['用水量', '生产水量']),
      createIndicator('production_water_unit_consumption', '生产用水单耗', '燃料动力类指标', ['用水单耗', '水耗']),
    ],
  },
  {
    key: 'environmental',
    label: '环保耗材类指标',
    indicators: [
      createIndicator('calcium_hydroxide', '氢氧化钙耗量', '环保耗材类指标', ['熟石灰耗量', '石灰耗量']),
      createIndicator('calcium_hydroxide_unit', '氢氧化钙单耗', '环保耗材类指标', ['熟石灰单耗', '石灰单耗']),
      createIndicator('urea_consumption', '尿素耗量', '环保耗材类指标', ['尿素量']),
      createIndicator('urea_unit', '尿素单耗', '环保耗材类指标', ['尿素消耗率']),
      createIndicator('ammonia_water_consumption', '氨水耗量', '环保耗材类指标', ['氨水量']),
      createIndicator('ammonia_water_unit', '氨水单耗', '环保耗材类指标', ['氨水消耗率']),
      createIndicator('sodium_bicarbonate', '碳酸氢钠耗量', '环保耗材类指标', ['小苏打耗量', '碳酸氢钠量']),
      createIndicator('sodium_bicarbonate_unit', '碳酸氢钠单耗', '环保耗材类指标', ['小苏打单耗']),
      createIndicator('activated_carbon_consumption', '活性炭耗量', '环保耗材类指标', ['活性炭量']),
      createIndicator('activated_carbon_unit', '活性炭单耗', '环保耗材类指标', ['活性炭消耗率']),
      createIndicator('leachate_consumption', '渗沥液产生量', '环保耗材类指标', ['渗滤液量', '渗沥液量']),
      createIndicator('fly_ash_raw_consumption', '飞灰原灰产生量', '环保耗材类指标', ['原灰量', '飞灰量']),
      createIndicator('fly_ash_outbound', '飞灰外运量', '环保耗材类指标', ['飞灰外运']),
      createIndicator('calcium_hydroxide_unit_inbound', '氢氧化钙单耗（入厂）', '环保耗材类指标'),
      createIndicator('urea_unit_inbound', '尿素单耗（入厂）', '环保耗材类指标'),
      createIndicator('activated_carbon_unit_inbound', '活性炭单耗（入厂）', '环保耗材类指标'),
      createIndicator('ammonia_water_unit_inbound', '氨水单耗（入厂）', '环保耗材类指标'),
      createIndicator('sodium_bicarbonate_unit_inbound', '碳酸氢钠单耗（入厂）', '环保耗材类指标'),
    ],
  },
]

export const DATA_QUERY_INDICATORS = DATA_QUERY_INDICATOR_GROUPS.flatMap((group) => group.indicators)
export const DATA_QUERY_CAPABILITIES = Object.fromEntries(
  DATA_QUERY_INDICATORS.map((indicator) => [indicator.id, indicator.capabilities]),
)

export const DATA_QUERY_REGIONS = [
  '雄安大区',
  '华北大区',
  '山东大区',
  '西南大区',
  '中西部大区',
  '鲁北地区',
  '北方地区',
  '南方地区',
]

// 当前仓库没有公司查询接口，先集中维护可检索的业务名称，后续可替换为真实接口数据。
export const DATA_QUERY_COMPANIES = [
  '承德', '行唐', '怀来', '蔚县', '东光', '开封', '保定', '曲周', '合肥', '石家庄',
  '西安', '肥西', '临沂', '天津', '保南', '莱西', '黄骅', '沧州', '涞水', '天水',
  '鹤岗', '资阳', '萍乡',
  '象山', '红河', '润达', '毕节', '福州', '牟平', '丽江', '龙南', '衡水', '抚州',
  '潮南', '杭州', '平顶山', '即墨', '南部', '定州', '昌乐', '大城', '汉中', '商河',
  '通化', '金堂', '郯城', '兰山', '安平', '烟台环能', '成都',
]

export function getDataQueryCompanyShortName(value = '') {
  const companyName = String(value || '').trim()
  if (!companyName) return ''

  const parenthesizedMatch = companyName.match(/[（(]([^）)]+)[）)]/)
  if (parenthesizedMatch) {
    const location = parenthesizedMatch[1].trim()
    const brand = companyName.startsWith('中节能') ? '中节能' : ''
    const baseName = brand ? `${brand}（${location}）` : location
    const branch = companyName.match(/有限公司(.+?)(?:分公司|子公司)/)?.[1]?.trim()
    return branch ? `${baseName}${branch}` : baseName
  }

  const knownName = [...DATA_QUERY_COMPANIES]
    .sort((left, right) => right.length - left.length)
    .find((name) => companyName.includes(name))
  if (knownName && companyName.startsWith('成都中节能')) return '成都中节能'
  if (knownName && companyName === knownName) return knownName

  const simplifiedName = companyName
    .replace(/(?:有限责任公司|股份有限公司|有限公司).*$/, '')
    .replace(/环保能源|可再生能源|再生能源|再生资源利用|生物质热电/g, '')
    .trim()

  return simplifiedName || knownName || companyName
}

export const DATA_QUERY_ALIASES = {
  入厂垃圾量: '生活垃圾入厂量',
  垃圾入厂量: '生活垃圾入厂量',
  厂用电率: '综合厂用电率',
  吨发电量: '吨入厂发电量',
  吨上网电量: '吨入厂上网电量',
  用水单耗: '生产用水单耗',
}

export const DATA_QUERY_WELCOME_POOL = [
  '您好，我是环宝智能问数助手。当前支持生产指标数据查询与分析，包括产量、效率、燃料动力和环保耗材等指标。您可以直接询问某项指标的年度情况、同比变化，也可以继续查看不同区域、项目公司的表现以及月度变化趋势。',
  '您好，我是环宝智能问数助手。想了解生产运行情况，可以直接问我。无论是某项指标今年完成了多少、和往年相比有什么变化，还是哪个区域表现突出、哪些项目公司变化较大，我都可以基于生产指标数据帮您查询和整理。',
  '您好，我是环宝智能问数助手。查询生产指标不用再逐层寻找报表，直接告诉我您想看什么即可。例如生活垃圾入厂量、发电量、综合厂用电率或环保耗材单耗，都可以通过自然语言直接查询。',
  '您好，我是环宝智能问数助手。我可以帮助您查询生产指标数值，并进一步查看同比变化、区域差异、项目公司排名和月度走势。您可以先从一个指标开始，再逐步追问到具体区域或项目公司。',
  '您好，我是环宝智能问数助手。当前支持生产指标智能查询，覆盖产量、效率、燃料动力和环保耗材等指标，可查询年度数据、同比情况、区域表现、项目公司情况及月度变化。',
  '您好，我是环宝智能问数助手。今天想看哪项生产指标？您可以问我今年完成情况怎么样、同比变化多少、哪个区域最高、哪些项目公司变化明显，或者某家公司的月度走势。',
  '您好，我是环宝智能问数助手。我可以基于生产指标数据，快速查询集团、区域及项目公司的生产运行情况。您既可以从指标总体情况开始，也可以继续关注同比变化、区域差异、项目公司排名和月度趋势。',
  '您好，我是环宝智能问数助手。不需要记住报表位置，也不需要记住所有指标名称。直接告诉我您大概想看什么，或者从指标库、大区、项目公司中选择，我会帮助您逐步形成可查询的问题。',
  '您好，我是环宝智能问数助手。当前支持生产指标数据查询。您可以先查看某项指标的年度总体情况，再继续追问区域对比、项目公司排名或具体公司的月度数据，让查询从总体逐步下钻到明细。',
  '您好，我是环宝智能问数助手。当前可查询产量指标、效率指标、燃料动力类指标以及环保耗材类指标。直接输入指标名称和时间范围，或者从下方快速选择，我会帮助您找到对应的生产数据。',
  '您好，我是环宝智能问数助手。除了查询单个生产指标，我还可以帮助您查看年度同比、区域之间的差异以及项目公司的排名和变化情况。您可以直接说出想看的指标、年份、区域或项目公司。',
  '您好，我是环宝智能问数助手。想查生产数据，直接问就可以。如果一时想不起指标名称，也可以先从指标库、区域或项目公司开始选择，我会帮助您把想法整理成可以直接查询的问题。',
]

export const DATA_QUERY_SUGGESTION_POOLS = {
  overview: [
    '查询今年生活垃圾入厂量',
    '查询今年发电量',
    '查询今年上网电量',
    '查询今年污泥入厂量',
    '查询今年餐厨厨余入厂量',
    '查询今年综合厂用电率',
    '查询今年吨入厂发电量',
    '查询今年发电负荷率',
    '查询今年天然气消耗量',
    '查询今年生产用水单耗',
    '查询今年尿素单耗',
    '查询今年活性炭单耗',
  ],
  comparison: [
    '生活垃圾入厂量近三年变化怎么样？',
    '今年生活垃圾入厂量同比变化多少？',
    '今年发电量同比增加还是减少？',
    '各区域今年生活垃圾入厂量分别是多少？',
    '哪个区域生活垃圾入厂量最高？',
    '哪个区域发电量最高？',
    '各区域发电量同比情况怎么样？',
    '哪些区域生活垃圾入厂量同比下降？',
    '哪个区域吨入厂发电量最高？',
    '各区域生产用水单耗情况怎么样？',
  ],
  company: [
    '今年生活垃圾入厂量最高的10家公司是哪些？',
    '哪些项目公司生活垃圾入厂量同比下降最多？',
    '按生活垃圾入厂量同比增幅给项目公司排名。',
    '今年发电量最高的项目公司有哪些？',
    '哪些公司的发电量同比下降？',
    '按上网电量给项目公司排序。',
    '哪些公司的综合厂用电率比较高？',
    '哪些公司的生产用水单耗比较高？',
    '哪些公司的尿素单耗最高？',
    '哪些公司的活性炭单耗最高？',
  ],
  monthly: [
    '查询承德今年生活垃圾入厂量',
    '承德今年生活垃圾入厂量同比变化多少？',
    '查询行唐近三年生活垃圾入厂量',
    '查询石家庄今年生活垃圾入厂量',
    '查询承德今年各月生活垃圾入厂量',
    '承德今年生活垃圾入厂量月度走势怎么样？',
    '查询石家庄今年各月生活垃圾入厂量',
    '查询承德今年各月发电量',
  ],
}

export const DATA_QUERY_HOT_INDICATOR_IDS = [
  'waste_inbound',
  'power_generation',
  'grid_power',
  'auxiliary_power_rate',
  'power_per_ton_inbound',
]

export const DATA_QUERY_ANALYSIS_OPTIONS = [
  { key: 'value', label: '查当前值' },
  { key: 'yoy', label: '同比' },
  { key: 'regionCompare', label: '区域对比' },
  { key: 'companyRank', label: '公司排名' },
  { key: 'monthly', label: '月度趋势' },
]

export const DATA_QUERY_DEFAULT_PERIOD = runtimeEnv.VITE_DATA_QUERY_DEFAULT_PERIOD || '今年'
export const DATA_QUERY_UNSUPPORTED_DOMAINS = ['合同', '预算', '科研', '财务', '人力资源', '采购全域', '全域经营']

export function flattenDataQueryIndicators() {
  return DATA_QUERY_INDICATORS
}

export function findDataQueryIndicators(keyword = '') {
  const normalized = String(keyword || '').trim().toLowerCase()
  if (!normalized) return DATA_QUERY_INDICATORS

  return DATA_QUERY_INDICATORS.filter((indicator) =>
    [indicator.name, ...indicator.aliases].some((value) => value.toLowerCase().includes(normalized)),
  )
}

export function findDataQueryIndicator(text = '') {
  const content = String(text || '')
  return [...DATA_QUERY_INDICATORS]
    .sort((a, b) => b.name.length - a.name.length)
    .find((indicator) =>
      [indicator.name, ...indicator.aliases].some((value) => content.includes(value)),
    )
}

export function getDataQueryExploration(text = '') {
  const content = String(text || '').trim()
  if (!content) return null

  if (/我能查哪些|能查哪些指标|有哪些指标|指标库|查询指标/.test(content)) {
    return { type: 'indicator', title: '当前可查询的生产指标', items: DATA_QUERY_INDICATORS.slice(0, 12) }
  }

  if (/发电相关|看看发电|发电数据/.test(content)) {
    return { type: 'indicator', title: '发电相关可查询指标', items: findDataQueryIndicators('发电') }
  }

  if (/环保耗材/.test(content)) {
    const group = DATA_QUERY_INDICATOR_GROUPS.find((item) => item.key === 'environmental')
    return { type: 'indicator', title: '环保耗材类可查询指标', items: group?.indicators || [] }
  }

  if (/有哪些大区|大区有哪些|有哪些地区/.test(content)) {
    return { type: 'region', title: '当前可选择的区域', items: DATA_QUERY_REGIONS }
  }

  if (/有哪些项目公司|项目公司有哪些/.test(content)) {
    const hasIndicator = Boolean(findDataQueryIndicator(content))
    const hasQueryContext = /查询|查看|排名|最高|最低|同比|趋势|变化|多少|分别/.test(content)

    if (!hasIndicator && !hasQueryContext) {
      return { type: 'company', title: '项目公司较多，可以搜索或浏览选择', items: [] }
    }
  }

  return null
}

export function isUnsupportedDataQuery(text = '') {
  return DATA_QUERY_UNSUPPORTED_DOMAINS.some((domain) => String(text || '').includes(domain))
}

export function buildDataQueryFollowUps(text = '') {
  const content = String(text || '')
  const indicator = [...DATA_QUERY_INDICATORS]
    .sort((a, b) => b.name.length - a.name.length)
    .find((item) => content.includes(item.name) || item.aliases.some((alias) => content.includes(alias)))
  const indicatorName = indicator?.name || '生产指标'
  const company = DATA_QUERY_COMPANIES.find((item) => content.includes(item))
  const region = DATA_QUERY_REGIONS.find((item) => content.includes(item))

  if (company) {
    return [
      `查看${company}各月${indicatorName}`,
      `对比${company}近三年${indicatorName}`,
      `查询${company}发电量`,
    ]
  }

  if (region) {
    return [
      `查看${region}各项目公司${indicatorName}`,
      `对比各区域${indicatorName}同比`,
      `查询某项目公司${indicatorName}`,
    ]
  }

  return [
    `对比近三年${indicatorName}`,
    `查看各区域${indicatorName}`,
    `查看项目公司${indicatorName}排名`,
  ]
}
