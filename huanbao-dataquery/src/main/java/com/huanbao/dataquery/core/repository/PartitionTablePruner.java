package com.huanbao.dataquery.core.repository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按查询日期裁剪人大金仓半年快照表。
 *
 * <p>默认清单来自 {@code docs/DATABASE_RECONCILIATION_REPORT.md}：只包含当前
 * 对账确认有数据的六张表，2026 下半年表的有效数据截止到 2026-08-31。
 * 后续实库清单变化时，应通过构造器传入新的清单和 cutoff，而不是放宽表名规则。</p>
 */
public final class PartitionTablePruner {

    public static final String SCHEMA = "MSOKFPT";
    public static final LocalDate DEFAULT_MAX_DATA_DATE = LocalDate.of(2026, 8, 31);
    public static final List<String> DEFAULT_AVAILABLE_TABLES = List.of(
            "CGXTAPPMISDate_2024_06",
            "CGXTAPPMISDate_2024_12",
            "CGXTAPPMISDate_2025_06",
            "CGXTAPPMISDate_2025_12",
            "CGXTAPPMISDate_2026_06",
            "CGXTAPPMISDate_2026_12");

    private static final Pattern PARTITION_TABLE_PATTERN =
            Pattern.compile("^CGXTAPPMISDate_(\\d{4})_(06|12)$");
    private static final String DATA_COLUMNS =
            "\"newIndicator\", \"ZBRQ\", \"ZBZ\", \"orgcode\"";

    private final NavigableMap<PartitionKey, String> availableTables;
    private final LocalDate maxDataDate;

    public PartitionTablePruner() {
        this(DEFAULT_AVAILABLE_TABLES, DEFAULT_MAX_DATA_DATE);
    }

    public PartitionTablePruner(LocalDate maxDataDate) {
        this(DEFAULT_AVAILABLE_TABLES, maxDataDate);
    }

    /**
     * 创建一个使用实库物理清单的裁剪器。
     *
     * @param physicalTableNames 只允许传入裸表名，例如 CGXTAPPMISDate_2026_06
     * @param maxDataDate         实库当前已核验的最大有效 ZBRQ 日期
     */
    public PartitionTablePruner(Collection<String> physicalTableNames, LocalDate maxDataDate) {
        Objects.requireNonNull(physicalTableNames, "physicalTableNames");
        this.availableTables = loadAvailableTables(physicalTableNames);
        this.maxDataDate = Objects.requireNonNull(maxDataDate, "maxDataDate");
        if (this.maxDataDate.isBefore(this.availableTables.firstKey().periodStart())) {
            throw new IllegalArgumentException(
                    "maxDataDate precedes the first available partition: " + maxDataDate);
        }
    }

    public LocalDate maxDataDate() {
        return maxDataDate;
    }

    public List<String> availableTableNames() {
        return List.copyOf(availableTables.values());
    }

    /**
     * 生成受控源表子查询，并返回实际生效日期、选中的物理表和边界提示。
     */
    public PruningResult prune(LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        List<String> notices = new ArrayList<>();
        LocalDate earliestAvailableDate = availableTables.firstKey().periodStart();
        LocalDate effectiveStartDate = startDate;
        if (effectiveStartDate.isBefore(earliestAvailableDate)) {
            notices.add("Requested start date " + startDate
                    + " precedes the earliest available partition; clipped to "
                    + earliestAvailableDate + ".");
            effectiveStartDate = earliestAvailableDate;
        }

        LocalDate effectiveEndDate = endDate;
        if (effectiveEndDate.isAfter(maxDataDate)) {
            notices.add("Requested end date " + endDate
                    + " exceeds the latest effective data date; clipped to "
                    + maxDataDate + ".");
            effectiveEndDate = maxDataDate;
        }

        if (effectiveStartDate.isAfter(effectiveEndDate)) {
            throw new IllegalArgumentException(
                    "Requested range has no data within the available partition boundary: "
                            + maxDataDate);
        }

        PartitionKey firstPartition = PartitionKey.forDate(effectiveStartDate);
        PartitionKey lastPartition = PartitionKey.forDate(effectiveEndDate);
        List<String> selectedTables = new ArrayList<>(availableTables
                .subMap(firstPartition, true, lastPartition, true)
                .values());
        if (selectedTables.isEmpty()) {
            throw new IllegalArgumentException(
                    "No reconciled physical partition covers " + effectiveStartDate
                            + " to " + effectiveEndDate);
        }

        String controlledSourceSql = selectedTables.stream()
                .map(PartitionTablePruner::buildPartitionSelect)
                .reduce((left, right) -> left + "\nUNION ALL\n" + right)
                .orElseThrow();

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("startDate", effectiveStartDate);
        parameters.put("endDate", effectiveEndDate);
        return new PruningResult(
                controlledSourceSql,
                selectedTables,
                startDate,
                endDate,
                effectiveStartDate,
                effectiveEndDate,
                !notices.isEmpty(),
                String.join(" ", notices),
                parameters);
    }

    public PruningResult prune(String startDate, String endDate) {
        return prune(parseDate(startDate, "startDate"), parseDate(endDate, "endDate"));
    }

    public String buildControlledSourceSql(LocalDate startDate, LocalDate endDate) {
        return prune(startDate, endDate).controlledSourceSql();
    }

    public String buildControlledSourceSql(String startDate, String endDate) {
        return prune(startDate, endDate).controlledSourceSql();
    }

    private static NavigableMap<PartitionKey, String> loadAvailableTables(
            Collection<String> physicalTableNames) {
        NavigableMap<PartitionKey, String> tables = new TreeMap<>();
        for (String tableName : physicalTableNames) {
            Objects.requireNonNull(tableName, "physicalTableNames must not contain null");
            Matcher matcher = PARTITION_TABLE_PATTERN.matcher(tableName.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Invalid or unqualified partition table name: " + tableName);
            }
            PartitionKey key = new PartitionKey(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
            String previous = tables.putIfAbsent(key, tableName.trim());
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate partition table: " + tableName);
            }
        }
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("physicalTableNames must not be empty");
        }
        return Collections.unmodifiableNavigableMap(tables);
    }

    private static String buildPartitionSelect(String tableName) {
        return "(SELECT " + DATA_COLUMNS
                + " FROM " + SCHEMA + ".\"" + tableName + "\""
                + " WHERE \"ZBRQ\" >= :startDate AND \"ZBRQ\" <= :endDate)";
    }

    private static LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM-dd", exception);
        }
    }

    private record PartitionKey(int year, int halfMonth) implements Comparable<PartitionKey> {

        private PartitionKey {
            if (year < 1 || (halfMonth != 6 && halfMonth != 12)) {
                throw new IllegalArgumentException("Invalid half-year partition: " + year + "_" + halfMonth);
            }
        }

        private static PartitionKey forDate(LocalDate date) {
            return new PartitionKey(date.getYear(), date.getMonthValue() <= 6 ? 6 : 12);
        }

        private LocalDate periodStart() {
            return LocalDate.of(year, halfMonth == 6 ? 1 : 7, 1);
        }

        @Override
        public int compareTo(PartitionKey other) {
            int yearComparison = Integer.compare(year, other.year);
            return yearComparison != 0 ? yearComparison : Integer.compare(halfMonth, other.halfMonth);
        }
    }

    public record PruningResult(
            String controlledSourceSql,
            List<String> selectedTableNames,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            boolean boundaryClipped,
            String boundaryNotice,
            Map<String, Object> parameters) {

        public PruningResult {
            controlledSourceSql = Objects.requireNonNull(controlledSourceSql, "controlledSourceSql");
            selectedTableNames = List.copyOf(Objects.requireNonNull(selectedTableNames, "selectedTableNames"));
            requestedStartDate = Objects.requireNonNull(requestedStartDate, "requestedStartDate");
            requestedEndDate = Objects.requireNonNull(requestedEndDate, "requestedEndDate");
            effectiveStartDate = Objects.requireNonNull(effectiveStartDate, "effectiveStartDate");
            effectiveEndDate = Objects.requireNonNull(effectiveEndDate, "effectiveEndDate");
            boundaryNotice = Objects.requireNonNull(boundaryNotice, "boundaryNotice");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
    }
}
