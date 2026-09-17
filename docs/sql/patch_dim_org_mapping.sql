-- 组织语义层标准映射视图补丁
--
-- 依据：
--   1. docs/数据源与指标基线.md 的组织对账冲突清单；
--   2. docs/业务Excel/公司简称（数据）.xlsx：90 条非空记录，其中 85 家公司、5 个大区占位。
--
-- 本补丁只创建/替换视图，不修改 BFAdminOrganization 原始物理表结构，也不写入任何业务数据。
-- Excel 中的 63 条“垃圾焚烧发电项目”全部标记为生产电厂；“生物质”同属生产型能源项目。
-- Excel 中 business_type / region 为空的非生产实体按原始口径保留为 NULL，不臆造分类或大区。
--
-- 秦皇岛特别规则：
--   10004024 / 中节能（秦皇岛）环保能源有限公司：纳入“秦皇岛公司”标准映射；
--   10004011：历史重码，排除；
--   10004791、10004793：水务公司，排除。
-- 由于视图只从本映射种子中产出，按 short_name 做“秦皇岛”前缀/去后缀匹配时不会再返回上述排除项。

CREATE OR REPLACE VIEW MSOKFPT."dim_org_mapping" (
  formal_code,
  formal_name,
  short_name,
  business_type,
  region,
  is_production_plant
) AS
WITH source_mapping (
  formal_code_hint,
  formal_name,
  short_name,
  business_type,
  region,
  is_production_plant
) AS (
  VALUES
    (NULL,       '开封中节能再生能源有限公司',                         '开封公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    (NULL,       '中节能（天水）环保能源有限公司',                     '天水公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    ('10006781', '中节能（汉中）环保能源有限公司',                     '汉中公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    ('10007219', '中节能（西安）环保能源有限公司',                     '西安公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    ('10005548', '中节能（平顶山）环保能源有限公司',                   '平顶山公司', '垃圾焚烧发电项目', '中西部大区', TRUE),
    (NULL,       '中节能（安康）环保能源有限公司',                     '安康公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    (NULL,       '中节能（商洛）环保能源有限公司',                     '商洛公司',   '垃圾焚烧发电项目', '中西部大区', TRUE),
    (NULL,       '中节能保南（蠡县）环保能源有限公司',                 '保南公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    (NULL,       '中节能（涞水）环保能源有限公司',                     '涞水公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10008339', '中节能（蔚县）环保能源有限公司',                     '蔚县公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10008546', '中节能（大城）环保能源有限公司',                     '大城公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10011963', '中节能（怀来）环保能源有限公司',                     '怀来公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10004025', '中节能（保定）环保能源有限公司',                     '保定公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10004024', '中节能（秦皇岛）环保能源有限公司',                   '秦皇岛公司', '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10004026', '承德环能热电有限责任公司',                           '承德公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    (NULL,       '中节能定州环保能源有限公司',                         '定州公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    (NULL,       '中节能（平泉）环保能源有限公司',                     '平泉公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10007969', '中节能（天津）环保能源有限公司',                     '天津公司',   '垃圾焚烧发电项目', '雄安大区',   TRUE),
    ('10001041', '成都中节能再生能源有限公司',                         '成都公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10007371', '中节能（红河）环保能源有限公司',                     '红河公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    (NULL,       '中节能（金堂）环保能源有限公司',                     '金堂公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10006677', '中节能（资阳）环保能源有限公司',                     '资阳公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10008338', '中节能（丽江）环保能源有限公司',                     '丽江公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10008696', '中节能（南部县）环保能源有限公司',                   '南部公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    (NULL,       '中节能（贞丰）环保能源有限公司',                     '贞丰公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10006652', '中节能（毕节）环保能源有限公司',                     '毕节环保',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10013360', '中节能（红河）环保能源有限公司元江分公司',           '元江公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    (NULL,       '中节能（云阳县）环保科技有限公司',                   '云阳公司',   '垃圾焚烧发电项目', '西南大区',   TRUE),
    ('10001026', '中节能（临沂）环保能源有限公司',                     '临沂公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    (NULL,       '中节能（临沂）环保能源有限公司兰山分公司',           '兰山公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    ('10004020', '中节能（即墨）环保能源有限公司',                     '即墨公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    ('10004701', '中节能（郯城）环保能源有限公司',                     '郯城公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    ('10006074', '中节能（肥城）环保能源有限公司',                     '肥城环保',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    (NULL,       '中节能（昌乐）环保能源有限公司',                     '昌乐公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    ('10006782', '中节能（莱西）环保能源有限公司',                     '莱西公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    (NULL,       '中节能（商河）环保能源有限公司',                     '商河公司',   '垃圾焚烧发电项目', '山东大区',   TRUE),
    (NULL,       '中节能（安平）环保能源有限公司',                     '安平公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    (NULL,       '中节能（黄骅）环保能源有限公司',                     '黄骅公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    (NULL,       '中节能（行唐）环保能源有限公司',                     '行唐公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    ('10008680', '中节能（东光）环保能源有限公司',                     '东光公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    (NULL,       '中节能（盐山）环保能源有限公司',                     '盐山公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    ('10009681', '中节能（平山）环保能源有限公司',                     '平山公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    (NULL,       '中节能（石家庄）环保能源有限公司',                   '石家庄公司', '垃圾焚烧发电项目', '华北大区',   TRUE),
    ('10004014', '中节能（沧州）环保能源有限公司',                     '沧州公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    ('10005344', '中节能（衡水）环保能源有限公司',                     '衡水公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    (NULL,       '中节能（曲周）环保能源有限公司',                     '曲周公司',   '垃圾焚烧发电项目', '华北大区',   TRUE),
    ('10001089', '中节能（合肥）可再生能源有限公司',                   '合肥公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10004706', '中节能萍乡环保能源有限公司',                         '萍乡公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10004700', '中节能（通化）环保能源有限公司',                     '通化公司',   '垃圾焚烧发电项目', '北方地区',   TRUE),
    ('10004027', '中节能（汕头潮南）环保能源有限公司',                 '潮南公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10005817', '中节能抚州环保能源有限公司',                         '抚州公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10006179', '中节能（肥西）环保能源有限公司',                     '肥西公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10007325', '中节能（福州）环保能源有限公司',                     '福州公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    (NULL,       '中节能（鹤岗）环保能源有限公司',                     '鹤岗公司',   '垃圾焚烧发电项目', '北方地区',   TRUE),
    ('10001036', '杭州绿能环保发电有限公司',                           '杭州公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    (NULL,       '中节能（齐齐哈尔）环保能源有限公司',                 '齐齐哈尔公司','垃圾焚烧发电项目', '北方地区',   TRUE),
    (NULL,       '中节能（咸宁崇阳）环保能源有限公司',                 '咸宁公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10008224', '中节能兆盛环保有限公司',                             '兆盛环保',   NULL,               NULL,         FALSE),
    ('10008673', '中节能（唐山）环保装备有限公司',                     '唐山装备',   NULL,               NULL,         FALSE),
    ('10002831', '中节能西安启源机电装备有限公司',                     '启源装备',   NULL,               NULL,         FALSE),
    ('10002875', '中节能启源雷宇（江苏）电气科技有限公司',             '启源雷宇',   NULL,               NULL,         FALSE),
    ('10004550', '启源（西安）大荣环保科技有限公司',                   '启源大荣',   NULL,               NULL,         FALSE),
    ('10008385', '中节能（烟台）环保能源有限公司',                     '烟台公司',   '垃圾焚烧发电项目', '鲁北地区',   TRUE),
    (NULL,       '中节能（沣西）生态环保有限公司',                     '沣西公司',   '餐厨厨余',         NULL,         FALSE),
    ('10011196', '中节能（毕节）环保生态有限公司',                     '毕节生态',   '有机板块',         NULL,         FALSE),
    ('10012020', '中节能（龙南）环保能源有限公司',                     '龙南公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10007373', '中节能（西安）生态环保有限公司',                     '西安生态',   '污泥',             NULL,         FALSE),
    (NULL,       '中节能（象山）环保能源有限公司',                     '象山公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10011023', '中节能（嘉鱼）环保能源有限公司',                     '嘉鱼公司',   '餐厨厨余',         NULL,         FALSE),
    (NULL,       '中节能生态桥（北京）环保有限公司',                   '平谷公司',   '有机板块',         NULL,         FALSE),
    ('10000221', '中节能绿碳（遵义）环保有限公司',                     '遵义公司',   '有机板块',         NULL,         FALSE),
    (NULL,       '中节能（惠来）环保科技有限公司',                     '惠来公司',   '垃圾焚烧发电项目', '南方地区',   TRUE),
    ('10004028', '中节能润达（烟台）环保股份有限公司',                 '润达股份',   NULL,               NULL,         FALSE),
    (NULL,       '中节能环境管理服务有限公司',                         '环服公司',   NULL,               NULL,         FALSE),
    ('10001053', '中节能（北京）节能环保工程有限公司',                 '工程公司',   NULL,               NULL,         FALSE),
    ('10006685', '中节能（莆田）再生资源利用有限公司',                 '莆田公司',   '餐厨厨余',         NULL,         FALSE),
    ('10004019', '烟台润达垃圾处理运营有限公司',                       '润达运营',   '垃圾焚烧发电项目', '鲁北地区',   TRUE),
    (NULL,       '烟台市牟平区垃圾综合处理有限公司',                   '牟平公司',   '垃圾焚烧发电项目', '鲁北地区',   TRUE),
    ('10001127', '中节能（山东）投资发展有限公司',                     '山东公司',   NULL,               NULL,         FALSE),
    (NULL,       '瑞科际再生能源股份有限公司',                         '厦门公司',   '餐厨厨余',         NULL,         FALSE),
    (NULL,       '中节能（宿迁）生物质能发电有限公司',                 '宿迁公司',   '生物质',           NULL,         TRUE),
    (NULL,       '中节能（烟台）生物质热电有限公司',                   '栖霞公司',   '生物质',           NULL,         TRUE),
    (NULL,       '中节能（舒城）生物质能发电有限公司',                 '舒城公司',   '生物质',           NULL,         TRUE),
    (NULL,       '中节能河北生物质能发电有限公司',                     '晋州公司',   '生物质',           NULL,         TRUE),
    ('10001111', '中节能（肥城）生物质能热电有限公司',                 '肥城公司',   '生物质',           NULL,         TRUE)
),
matched AS (
  SELECT
    o."Code" AS formal_code,
    o."Name_CHS" AS formal_name,
    s.short_name,
    s.business_type,
    s.region,
    s.is_production_plant,
    ROW_NUMBER() OVER (
      PARTITION BY s.short_name
      ORDER BY
        CASE
          WHEN s.formal_code_hint IS NOT NULL AND o."Code" = s.formal_code_hint THEN 0
          ELSE 1
        END,
        CASE
          WHEN COALESCE(TRIM(o."State_IsEnabled"), '1') IN ('1', 'Y', 'y', 'T', 't', 'true', 'TRUE') THEN 0
          ELSE 1
        END,
        CASE
          WHEN COALESCE(TRIM(o."State_AsyncDeleteStatus"), '0') IN ('', '0', 'N', 'n', 'false', 'FALSE') THEN 0
          ELSE 1
        END,
        CASE
          WHEN COALESCE(TRIM(o."IsDetailCompany"), '0') IN ('1', 'Y', 'y', 'T', 't', 'true', 'TRUE') THEN 0
          ELSE 1
        END,
        o."Code"
    ) AS rn
  FROM source_mapping s
  JOIN MSOKFPT."BFAdminOrganization" o
    ON o."Name_CHS" = s.formal_name
   AND (s.formal_code_hint IS NULL OR o."Code" = s.formal_code_hint)
  WHERE NULLIF(TRIM(o."Code"), '') IS NOT NULL
)
SELECT
  formal_code,
  formal_name,
  short_name,
  business_type,
  region,
  is_production_plant
FROM matched
WHERE rn = 1;

COMMENT ON VIEW MSOKFPT."dim_org_mapping" IS
  '组织语义层标准映射：来源为公司简称（数据）Excel与实库 BFAdminOrganization；秦皇岛默认绑定 10004024，排除历史重码与水务组织。';

-- ============================================================================
-- 只读验证 SQL（不属于视图创建语句，可单独执行）
-- ============================================================================

-- 1) 63 家垃圾焚烧发电项目是否全部映射且 formal_code 唯一。
SELECT
  COUNT(*) AS mapped_garbage_plant_count,
  COUNT(DISTINCT formal_code) AS distinct_garbage_formal_code_count,
  COUNT(*) FILTER (WHERE formal_code IS NULL OR formal_name IS NULL) AS invalid_mapping_count
FROM MSOKFPT."dim_org_mapping"
WHERE business_type = '垃圾焚烧发电项目';

-- 2) 指定自然语言输入的确定性命中验收：每个输入必须恰好 1 行且 Code 完全一致。
WITH test_input (input_name, expected_formal_code) AS (
  VALUES
    ('秦皇岛公司', '10004024'),
    ('保定公司',   '10004025'),
    ('承德公司',   '10004026')
), resolved AS (
  SELECT
    t.input_name,
    t.expected_formal_code,
    COUNT(m.formal_code) AS hit_count,
    MIN(m.formal_code) AS actual_formal_code,
    MIN(m.formal_name) AS actual_formal_name
  FROM test_input t
  LEFT JOIN MSOKFPT."dim_org_mapping" m
    ON m.short_name = t.input_name
  GROUP BY t.input_name, t.expected_formal_code
)
SELECT
  input_name,
  expected_formal_code,
  actual_formal_code,
  actual_formal_name,
  hit_count,
  CASE
    WHEN hit_count = 1 AND actual_formal_code = expected_formal_code THEN 'PASS'
    ELSE 'FAIL'
  END AS validation_result
FROM resolved
ORDER BY input_name;

-- 2b) 裸输入“秦皇岛”按标准简称去掉“公司”后缀后，仍必须唯一命中 10004024。
SELECT
  '秦皇岛' AS input_name,
  COUNT(*) AS hit_count,
  MIN(formal_code) AS actual_formal_code,
  CASE
    WHEN COUNT(*) = 1 AND MIN(formal_code) = '10004024' THEN 'PASS'
    ELSE 'FAIL'
  END AS validation_result
FROM MSOKFPT."dim_org_mapping"
WHERE short_name = '秦皇岛公司';

-- 3) 秦皇岛排除项不得出现在“秦皇岛公司”标准映射中。
SELECT
  formal_code,
  formal_name,
  short_name,
  business_type,
  is_production_plant
FROM MSOKFPT."dim_org_mapping"
WHERE short_name = '秦皇岛公司'
   OR formal_code IN ('10004011', '10004791', '10004793')
ORDER BY short_name, formal_code;

-- 4) 检查一个 formal_code 是否被多个标准简称复用。
SELECT formal_code, COUNT(*) AS mapping_count
FROM MSOKFPT."dim_org_mapping"
GROUP BY formal_code
HAVING COUNT(*) > 1
ORDER BY formal_code;
