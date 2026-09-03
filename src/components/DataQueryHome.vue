<script setup>
import { Building2, ChevronRight, Map, Search, X } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import {
  DATA_QUERY_ANALYSIS_OPTIONS,
  DATA_QUERY_COMPANIES,
  DATA_QUERY_DEFAULT_PERIOD,
  DATA_QUERY_INDICATOR_GROUPS,
  DATA_QUERY_INDICATORS,
  DATA_QUERY_REGIONS,
  DATA_QUERY_WELCOME_POOL,
  findDataQueryIndicators,
} from '../config/dataQueryCatalog'
import PromptStarters from './PromptStarters.vue'

const props = defineProps({
  inputValue: { type: String, default: '' },
  sessionKey: { type: String, default: '' },
  orgName: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  showHome: { type: Boolean, default: true },
})

const emit = defineEmits(['update:inputValue', 'submit-query'])

const activeExplorer = ref(null)
const indicatorSearchKeyword = ref('')
const companySearchKeyword = ref('')
const homeWelcome = ref('')
const previewQuery = ref('')
const queryComposer = reactive({
  timeRange: DATA_QUERY_DEFAULT_PERIOD,
  region: '',
  company: '',
  indicator: '',
  analysisType: 'value',
})

const hotIndicators = computed(() =>
  ['生活垃圾入厂量', '发电量', '上网电量', '综合厂用电率', '吨入厂发电量']
    .map((name) => DATA_QUERY_INDICATORS.find((indicator) => indicator.name === name))
    .filter(Boolean),
)

const selectedIndicator = computed(() =>
  DATA_QUERY_INDICATORS.find((indicator) => indicator.name === queryComposer.indicator),
)

const filteredIndicatorGroups = computed(() => {
  const keyword = indicatorSearchKeyword.value.trim()
  return DATA_QUERY_INDICATOR_GROUPS
    .map((group) => ({ ...group, indicators: findDataQueryIndicators(keyword).filter((indicator) => indicator.group === group.label) }))
    .filter((group) => group.indicators.length)
})

const filteredRegions = computed(() => {
  const keyword = indicatorSearchKeyword.value.trim()
  return DATA_QUERY_REGIONS.filter((region) => !keyword || region.includes(keyword))
})

const filteredCompanies = computed(() => {
  const keyword = companySearchKeyword.value.trim()
  return DATA_QUERY_COMPANIES.filter((company) => !keyword || company.includes(keyword))
})

const availableAnalysisOptions = computed(() =>
  DATA_QUERY_ANALYSIS_OPTIONS.filter(
    (option) => option.key === 'value' || selectedIndicator.value?.capabilities?.[option.key],
  ),
)

const autocompleteGroups = computed(() => {
  const keyword = props.inputValue.trim()
  if (!keyword) return []

  const indicators = findDataQueryIndicators(keyword).slice(0, 8)
  const regions = DATA_QUERY_REGIONS.filter((region) => region.includes(keyword)).slice(0, 5)
  const companies = DATA_QUERY_COMPANIES.filter((company) => company.includes(keyword)).slice(0, 8)

  return [
    indicators.length ? { key: 'indicator', label: '指标', items: indicators } : null,
    regions.length ? { key: 'region', label: '区域', items: regions } : null,
    companies.length ? { key: 'company', label: '项目公司', items: companies } : null,
  ].filter(Boolean)
})

const hasComposerSelection = computed(() =>
  Boolean(queryComposer.indicator || queryComposer.region || queryComposer.company),
)

const pickHomeWelcome = () => {
  homeWelcome.value = DATA_QUERY_WELCOME_POOL[Math.floor(Math.random() * DATA_QUERY_WELCOME_POOL.length)]
}

const buildQuery = () => {
  if (!queryComposer.indicator) return '请先选择一个指标，再补充区域或项目公司。'

  const scope = [queryComposer.timeRange, queryComposer.region, queryComposer.company, queryComposer.indicator]
    .filter(Boolean)
    .join('')
  const suffixMap = {
    yoy: '同比情况',
    regionCompare: '区域对比情况',
    companyRank: '项目公司排名',
    monthly: '月度变化趋势',
  }
  return `查询${scope}${suffixMap[queryComposer.analysisType] || ''}`
}

const rebuildPreview = () => {
  previewQuery.value = buildQuery()
  if (queryComposer.indicator) emit('update:inputValue', previewQuery.value)
}

const selectIndicator = (indicator) => {
  queryComposer.indicator = indicator.name
  if (!availableAnalysisOptions.value.some((option) => option.key === queryComposer.analysisType)) {
    queryComposer.analysisType = 'value'
  }
  rebuildPreview()
}

const selectRegion = (region) => {
  queryComposer.region = region
  rebuildPreview()
}

const selectCompany = (company) => {
  queryComposer.company = company
  rebuildPreview()
}

const selectAutocompleteItem = (group, item) => {
  if (group === 'indicator') selectIndicator(item)
  if (group === 'region') selectRegion(item)
  if (group === 'company') selectCompany(item)
}

const toggleExplorer = (explorer) => {
  activeExplorer.value = activeExplorer.value === explorer ? null : explorer
  if (explorer === 'indicator' || explorer === 'region') indicatorSearchKeyword.value = ''
  if (explorer === 'company') companySearchKeyword.value = ''
}

const clearComposer = () => {
  queryComposer.timeRange = DATA_QUERY_DEFAULT_PERIOD
  queryComposer.region = ''
  queryComposer.company = ''
  queryComposer.indicator = ''
  queryComposer.analysisType = 'value'
  previewQuery.value = ''
  emit('update:inputValue', '')
}

const submitPreview = () => {
  const query = previewQuery.value.trim()
  if (query && !query.startsWith('请先选择')) emit('submit-query', query)
}

const setAnalysisType = (key) => {
  queryComposer.analysisType = key
  rebuildPreview()
}

const syncPreview = (value) => {
  previewQuery.value = value
  emit('update:inputValue', value)
}

const submitStarter = (question, starter) => {
  emit('submit-query', question, starter)
}

watch(
  () => props.sessionKey,
  () => {
    activeExplorer.value = null
    indicatorSearchKeyword.value = ''
    companySearchKeyword.value = ''
    clearComposer()
    pickHomeWelcome()
  },
  { immediate: true },
)
</script>

<template>
  <section v-if="showHome" class="data-query-home" aria-label="生产指标智能问数首页">
    <div class="data-query-eyebrow">生产指标智能问数</div>
    <section class="data-query-welcome" aria-label="智能问数欢迎信息">
      <div class="data-query-welcome-copy">
        <span class="data-query-welcome-tag">生产指标数据查询</span>
        <h2>您好，我是环宝智能问数助手</h2>
        <p>{{ homeWelcome }}</p>
        <span class="data-query-scope">当前支持：生产指标</span>
      </div>
    </section>

    <div class="data-query-section-head">
      <span>快捷入口</span>
      <span class="data-query-section-note">先选方向，再用自然语言完善</span>
    </div>
    <div class="data-query-quick-list">
      <button type="button" class="data-query-quick-entry" @click="toggleExplorer('indicator')">
        <Search :size="15" :stroke-width="1.8" aria-hidden="true" />
        <span>指标库</span><ChevronRight :size="15" aria-hidden="true" />
      </button>
      <button type="button" class="data-query-quick-entry" @click="toggleExplorer('region')">
        <Map :size="15" :stroke-width="1.8" aria-hidden="true" />
        <span>大区</span><ChevronRight :size="15" aria-hidden="true" />
      </button>
      <button type="button" class="data-query-quick-entry" @click="toggleExplorer('company')">
        <Building2 :size="15" :stroke-width="1.8" aria-hidden="true" />
        <span>项目公司</span><ChevronRight :size="15" aria-hidden="true" />
      </button>
    </div>

    <section class="data-query-hot-section" aria-label="热门指标">
      <div class="data-query-section-head"><span>热门指标</span><span class="data-query-section-note">点击即可生成查询</span></div>
      <div class="data-query-chip-list">
        <button v-for="indicator in hotIndicators" :key="indicator.id" type="button" class="data-query-chip" :class="{ selected: queryComposer.indicator === indicator.name }" @click="selectIndicator(indicator)">
          {{ indicator.name }}
        </button>
      </div>
    </section>

    <PromptStarters
      mode="data-query"
      :org-name="orgName"
      :session-key="sessionKey"
      :disabled="disabled"
      @select="submitStarter"
    />
  </section>

  <div v-else class="data-query-mini-quick-list" aria-label="智能问数快捷入口">
    <span class="data-query-mini-quick-label">继续探索</span>
    <button type="button" class="data-query-quick-entry" @click="toggleExplorer('indicator')">
      <Search :size="14" :stroke-width="1.8" aria-hidden="true" />
      <span>指标库</span><ChevronRight :size="14" aria-hidden="true" />
    </button>
    <button type="button" class="data-query-quick-entry" @click="toggleExplorer('region')">
      <Map :size="14" :stroke-width="1.8" aria-hidden="true" />
      <span>大区</span><ChevronRight :size="14" aria-hidden="true" />
    </button>
    <button type="button" class="data-query-quick-entry" @click="toggleExplorer('company')">
      <Building2 :size="14" :stroke-width="1.8" aria-hidden="true" />
      <span>项目公司</span><ChevronRight :size="14" aria-hidden="true" />
    </button>
  </div>

  <section v-if="activeExplorer" class="data-query-explorer" aria-label="智能问数探索器">
    <div class="data-query-explorer-head">
      <strong>{{ activeExplorer === 'indicator' ? '指标库' : activeExplorer === 'region' ? '选择大区 / 地区' : '选择项目公司' }}</strong>
      <button type="button" class="data-query-icon-button" title="收起" aria-label="收起" @click="activeExplorer = null"><X :size="16" /></button>
    </div>

    <div v-if="activeExplorer === 'indicator'" class="data-query-explorer-body">
      <div class="data-query-search-shell">
        <Search :size="15" aria-hidden="true" />
        <input v-model="indicatorSearchKeyword" type="search" placeholder="搜索指标，例如：发电、垃圾、用水..." aria-label="搜索指标" />
      </div>
      <div v-if="filteredIndicatorGroups.length" class="data-query-indicator-groups">
        <div v-for="group in filteredIndicatorGroups" :key="group.key" class="data-query-indicator-group">
          <div class="data-query-group-title">{{ group.label }}</div>
          <div class="data-query-chip-list">
            <button v-for="indicator in group.indicators" :key="indicator.id" type="button" class="data-query-chip" :class="{ selected: queryComposer.indicator === indicator.name }" @click="selectIndicator(indicator)">
              {{ indicator.name }}
            </button>
          </div>
        </div>
      </div>
      <div v-else class="data-query-empty">没有找到匹配的生产指标，可以换个关键词试试。</div>
    </div>

    <div v-if="activeExplorer === 'region'" class="data-query-explorer-body">
      <div class="data-query-region-list">
        <button v-for="region in filteredRegions" :key="region" type="button" class="data-query-option" :class="{ selected: queryComposer.region === region }" @click="selectRegion(region)">
          <Map :size="15" aria-hidden="true" />{{ region }}
        </button>
      </div>
    </div>

    <div v-if="activeExplorer === 'company'" class="data-query-explorer-body">
      <div class="data-query-search-shell">
        <Search :size="15" aria-hidden="true" />
        <input v-model="companySearchKeyword" type="search" placeholder="搜索公司名称..." aria-label="搜索公司名称" />
      </div>
      <div v-if="filteredCompanies.length" class="data-query-company-list">
        <button v-for="company in filteredCompanies" :key="company" type="button" class="data-query-company-option" :class="{ selected: queryComposer.company === company }" @click="selectCompany(company)">
          <Building2 :size="15" aria-hidden="true" /><span>{{ company }}</span>
        </button>
      </div>
      <div v-else class="data-query-empty">没有找到匹配的项目公司。</div>
    </div>
  </section>

  <section v-if="hasComposerSelection" class="data-query-composer" aria-label="已生成查询">
    <div class="data-query-explorer-head">
      <strong>已生成查询</strong>
      <button type="button" class="data-query-clear-button" @click="clearComposer">清空</button>
    </div>
    <div class="data-query-composer-tags">
      <span v-if="queryComposer.timeRange" class="data-query-condition-tag">{{ queryComposer.timeRange }}</span>
      <span v-if="queryComposer.region" class="data-query-condition-tag">{{ queryComposer.region }}</span>
      <span v-if="queryComposer.company" class="data-query-condition-tag">{{ queryComposer.company }}</span>
      <span v-if="queryComposer.indicator" class="data-query-condition-tag active">{{ queryComposer.indicator }}</span>
    </div>
    <textarea :value="previewQuery" class="data-query-preview" rows="2" aria-label="可编辑查询" @input="syncPreview($event.target.value)"></textarea>
    <div v-if="selectedIndicator" class="data-query-analysis-list" aria-label="查询方式">
      <button v-for="option in availableAnalysisOptions" :key="option.key" type="button" class="data-query-analysis-button" :class="{ selected: queryComposer.analysisType === option.key }" @click="setAnalysisType(option.key)">
        {{ option.label }}
      </button>
    </div>
    <div class="data-query-composer-actions">
      <button type="button" class="data-query-secondary-action" @click="toggleExplorer('indicator')">选指标</button>
      <button type="button" class="data-query-secondary-action" @click="toggleExplorer('region')">选大区</button>
      <button type="button" class="data-query-secondary-action" @click="toggleExplorer('company')">选公司</button>
      <button type="button" class="data-query-submit-action" @click="submitPreview">发送查询</button>
    </div>
  </section>

  <section v-if="autocompleteGroups.length" class="data-query-autocomplete" aria-label="输入联想">
    <div v-for="group in autocompleteGroups" :key="group.key" class="data-query-autocomplete-group">
      <div class="data-query-group-title">{{ group.label }}</div>
      <button v-for="item in group.items" :key="item.id || item" type="button" class="data-query-autocomplete-item" @click="selectAutocompleteItem(group.key, item)">
        <span>{{ item.name || item }}</span><ChevronRight :size="14" aria-hidden="true" />
      </button>
    </div>
  </section>
</template>
