package com.huanbao.dataquery.domain.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.core.spi.DomainSemanticProvider;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.core.spi.SecurityUserContext;
import com.huanbao.dataquery.core.spi.SqlBuildStrategy;
import net.sf.jsqlparser.expression.Expression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 环宝生产运营领域语义包。
 *
 * <p>指标元数据来自版本化语义字典，组织名称只解析到受控的
 * {@code dim_org_mapping}，不把用户输入当成表名或 SQL 片段。</p>
 */
@Component
public final class OpsDomainSemanticProvider implements DomainSemanticProvider {

    public static final String DOMAIN_CODE = "OPS";
    public static final String GARBAGE_INCINERATION_BUSINESS_TYPE = "垃圾焚烧发电项目";
    public static final String ORG_MAPPING_VIEW = "MSOKFPT.\"dim_org_mapping\"";
    public static final String QINHUANGDAO_CODE = "10004024";

    private static final String DICTIONARY_RESOURCE = "semantic/METRIC_SEMANTIC_DICTIONARY.json";
    private static final String ORG_MAPPING_RESOURCE = "semantic/patch_dim_org_mapping.sql";
    private static final Path DICTIONARY_SOURCE_PATH =
            Path.of("..", "docs", "METRIC_SEMANTIC_DICTIONARY.json");
    private static final Path ORG_MAPPING_SOURCE_PATH =
            Path.of("..", "docs", "sql", "patch_dim_org_mapping.sql");
    private final List<MetricDefinition> metrics;
    private final Map<String, MetricDefinition> metricLookup;
    private final Map<String, OpsOrganization> organizationLookup;
    private final Map<String, OpsOrganization> organizationByCode;
    private final SqlBuildStrategy sqlStrategy;

    public OpsDomainSemanticProvider() {
        this(loadDictionaryFromDefaultLocation(), loadOrganizationSqlFromDefaultLocation());
    }

    /** 便于离线校验或测试使用指定的版本化语义资产。 */
    public OpsDomainSemanticProvider(Path dictionaryPath, Path organizationSqlPath) {
        Objects.requireNonNull(dictionaryPath, "dictionaryPath");
        Objects.requireNonNull(organizationSqlPath, "organizationSqlPath");
        this.metrics = loadMetrics(dictionaryPath);
        this.metricLookup = indexMetrics(metrics);
        List<OpsOrganization> organizations = loadOrganizations(organizationSqlPath);
        this.organizationLookup = indexOrganizations(organizations);
        this.organizationByCode = indexOrganizationsByCode(organizations);
        this.sqlStrategy = new OpsSqlBuildStrategy(this);
    }

    private OpsDomainSemanticProvider(JsonNode dictionary, String organizationSql) {
        this.metrics = loadMetrics(dictionary);
        this.metricLookup = indexMetrics(metrics);
        List<OpsOrganization> organizations = parseOrganizations(organizationSql);
        this.organizationLookup = indexOrganizations(organizations);
        this.organizationByCode = indexOrganizationsByCode(organizations);
        this.sqlStrategy = new OpsSqlBuildStrategy(this);
    }

    @Override
    public String getDomainCode() {
        return DOMAIN_CODE;
    }

    @Override
    public List<String> getDomainKeywords() {
        return List.of(
                "生产指标",
                "垃圾焚烧发电",
                "发电量",
                "入厂量",
                "负荷率",
                "大区",
                "项目公司");
    }

    @Override
    public MetricDefinition getMetric(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            return null;
        }
        return metricLookup.get(normalize(nameOrAlias));
    }

    public Collection<MetricDefinition> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    /** 返回从 dim_org_mapping 受控加载的生产组织，供路由槽位抽取使用。 */
    public Collection<OpsOrganization> getOrganizations() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(organizationLookup.values()));
    }

    public Map<String, MetricDefinition> getMetricRegistry() {
        return Collections.unmodifiableMap(metricLookup);
    }

    @Override
    public EntityMapping resolveEntity(String rawName) {
        String normalized = normalizeRequired(rawName, "rawName");
        if (isGroup(normalized)) {
            return new EntityMapping("GROUP", "集团", ORG_MAPPING_VIEW);
        }

        String region = resolveRegion(normalized);
        if (region != null) {
            return new EntityMapping("REGION", region, ORG_MAPPING_VIEW);
        }

        OpsOrganization organization = findOrganization(normalized)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OPS organization: " + rawName));
        return new EntityMapping("PROJECT_COMPANY", organization.shortName(), ORG_MAPPING_VIEW);
    }

    @Override
    public SqlBuildStrategy getSqlStrategy() {
        return sqlStrategy;
    }

    @Override
    public void applyDataScope(Expression whereClause, SecurityUserContext userContext) {
        // 当前 SPI 的参数是不可替换的 Expression；组织范围已在 OpsSqlBuildStrategy
        // 中通过 dim_org_mapping 的生产类型条件收口，用户权限谓词由上层 AST 编排器追加。
        Objects.requireNonNull(whereClause, "whereClause");
        Objects.requireNonNull(userContext, "userContext");
    }

    public Optional<OpsOrganization> findOrganization(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(rawName);
        if (Set.of("秦皇岛", "秦皇岛公司", "秦皇岛电厂").contains(normalized)) {
            OpsOrganization qinhuangdao = organizationByCode.get(normalize(QINHUANGDAO_CODE));
            if (qinhuangdao != null) {
                return Optional.of(qinhuangdao);
            }
        }
        OpsOrganization direct = organizationLookup.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        OpsOrganization byCode = organizationByCode.get(normalized);
        if (byCode != null) {
            return Optional.of(byCode);
        }
        String withoutCompanySuffix = stripCompanySuffix(normalized);
        OpsOrganization byShortAlias = organizationLookup.get(withoutCompanySuffix);
        return Optional.ofNullable(byShortAlias);
    }

    public String resolveRegion(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String normalized = normalize(rawName);
        return organizationLookup.values().stream()
                .map(OpsOrganization::region)
                .filter(Objects::nonNull)
                .distinct()
                .filter(region -> normalize(region).equals(normalized)
                        || stripRegionSuffix(normalize(region)).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private static boolean isGroup(String normalized) {
        return Set.of("集团", "全集团", "集团汇总", "全部项目公司", "项目公司").contains(normalized);
    }

    private static List<MetricDefinition> loadMetrics(Path path) {
        try {
            JsonNode dictionary = new ObjectMapper().readTree(Files.readString(path));
            return loadMetrics(dictionary);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read metric dictionary: " + path, exception);
        }
    }

    private static List<MetricDefinition> loadMetrics(JsonNode dictionary) {
        JsonNode indicators = dictionary.path("indicators");
        int declaredCount = dictionary.path("metricCount").asInt(-1);
        if (!indicators.isArray() || declaredCount != indicators.size() || declaredCount != 46) {
            throw new IllegalStateException(
                    "OPS metric dictionary must declare exactly 46 indicators, actual=" + declaredCount);
        }
        ObjectMapper objectMapper = new ObjectMapper();
        List<MetricDefinition> definitions = new ArrayList<>(indicators.size());
        for (JsonNode indicator : indicators) {
            try {
                definitions.add(objectMapper.treeToValue(indicator, MetricDefinition.class));
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot deserialize OPS metric definition", exception);
            }
        }
        return List.copyOf(definitions);
    }

    private static Map<String, MetricDefinition> indexMetrics(List<MetricDefinition> definitions) {
        Map<String, MetricDefinition> index = new LinkedHashMap<>();
        for (MetricDefinition definition : definitions) {
            addMetricKey(index, definition.indicatorCode(), definition);
            addMetricKey(index, definition.formalName(), definition);
            definition.aliases().forEach(alias -> addMetricKey(index, alias, definition));
        }
        return index;
    }

    private static void addMetricKey(
            Map<String, MetricDefinition> index,
            String key,
            MetricDefinition definition) {
        index.putIfAbsent(normalize(key), definition);
    }

    private static List<OpsOrganization> loadOrganizations(Path path) {
        try {
            return parseOrganizations(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read organization mapping SQL: " + path, exception);
        }
    }

    private static List<OpsOrganization> parseOrganizations(String sql) {
        List<OpsOrganization> organizations = new ArrayList<>();
        for (String line : sql.split("\\R")) {
            String tuple = line.trim();
            if (!tuple.startsWith("(") || !tuple.matches("^\\(.+\\),?$")) {
                continue;
            }
            List<String> columns = splitTuple(tuple);
            if (columns.size() != 6) {
                continue;
            }
            String businessType = sqlValue(columns.get(3));
            if (!GARBAGE_INCINERATION_BUSINESS_TYPE.equals(businessType)
                    || !Boolean.parseBoolean(columns.get(5).trim())) {
                continue;
            }
            String formalName = sqlValue(columns.get(1));
            String shortName = sqlValue(columns.get(2));
            if (formalName == null || shortName == null) {
                continue;
            }
            organizations.add(new OpsOrganization(
                    sqlValue(columns.get(0)),
                    formalName,
                    shortName,
                    businessType,
                    sqlValue(columns.get(4)),
                    true));
        }
        if (organizations.isEmpty()) {
            throw new IllegalStateException("No garbage-incineration organizations in dim_org_mapping SQL");
        }
        return List.copyOf(organizations);
    }

    private static List<String> splitTuple(String tuple) {
        String content = tuple.trim();
        if (content.endsWith(",")) {
            content = content.substring(0, content.length() - 1).trim();
        }
        content = content.substring(1, content.length() - 1);
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\'' && (index == 0 || content.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private static String sqlValue(String value) {
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (normalized.length() >= 2 && normalized.startsWith("'") && normalized.endsWith("'")) {
            return normalized.substring(1, normalized.length() - 1).replace("''", "'");
        }
        return normalized;
    }

    private static Map<String, OpsOrganization> indexOrganizations(List<OpsOrganization> organizations) {
        Map<String, OpsOrganization> index = new LinkedHashMap<>();
        organizations.stream()
                .sorted(Comparator.comparing(OpsDomainSemanticProvider::organizationPreference))
                .forEach(organization -> {
                    putOrganization(index, organization.shortName(), organization);
                    putOrganization(index, organization.formalName(), organization);
                });
        return index;
    }

    private static Map<String, OpsOrganization> indexOrganizationsByCode(List<OpsOrganization> organizations) {
        Map<String, OpsOrganization> index = new LinkedHashMap<>();
        organizations.stream()
                .filter(organization -> organization.formalCode() != null)
                .forEach(organization -> index.putIfAbsent(
                        normalize(organization.formalCode()), organization));
        return index;
    }

    private static void putOrganization(
            Map<String, OpsOrganization> index,
            String key,
            OpsOrganization organization) {
        index.putIfAbsent(normalize(key), organization);
        String shortAlias = stripCompanySuffix(normalize(key));
        if (!shortAlias.equals(normalize(key))) {
            index.putIfAbsent(shortAlias, organization);
        }
    }

    private static String organizationPreference(OpsOrganization organization) {
        return (QINHUANGDAO_CODE.equals(organization.formalCode()) ? "0" : "1")
                + (organization.formalCode() == null ? "1" : "0")
                + Objects.toString(organization.formalCode(), "");
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = normalize(value);
        if (normalized.length() > 128 || normalized.contains("'") || normalized.contains("\"")
                || normalized.contains(";") || normalized.contains("--")) {
            throw new IllegalArgumentException("Invalid OPS entity name");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripCompanySuffix(String value) {
        return value.endsWith("公司") ? value.substring(0, value.length() - 2) : value;
    }

    private static String stripRegionSuffix(String value) {
        return value.endsWith("大区") ? value.substring(0, value.length() - 2) : value;
    }

    private static JsonNode loadDictionaryFromDefaultLocation() {
        try (InputStream input = openResourceOrPath(DICTIONARY_RESOURCE, DICTIONARY_SOURCE_PATH)) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read OPS metric dictionary", exception);
        }
    }

    private static String loadOrganizationSqlFromDefaultLocation() {
        try (InputStream input = openResourceOrPath(ORG_MAPPING_RESOURCE, ORG_MAPPING_SOURCE_PATH)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read OPS organization mapping SQL", exception);
        }
    }

    private static InputStream openResourceOrPath(String resourceName, Path sourcePath) throws IOException {
        InputStream resource = OpsDomainSemanticProvider.class.getClassLoader().getResourceAsStream(resourceName);
        if (resource != null) {
            return resource;
        }
        if (Files.exists(sourcePath)) {
            return Files.newInputStream(sourcePath);
        }
        Path repositoryRootPath = Path.of("docs").resolve(sourcePath.getFileName());
        if (resourceName.endsWith("patch_dim_org_mapping.sql")) {
            repositoryRootPath = Path.of("docs", "sql", sourcePath.getFileName().toString());
        }
        return Files.newInputStream(repositoryRootPath);
    }
}
