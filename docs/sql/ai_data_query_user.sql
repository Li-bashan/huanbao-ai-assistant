CREATE TABLE IF NOT EXISTS ai_data_query_user (
  id BIGSERIAL PRIMARY KEY,
  user_id VARCHAR(120),
  user_code VARCHAR(120),
  user_name VARCHAR(80) NOT NULL,
  tenant_id VARCHAR(120),
  tenant_name VARCHAR(160),
  org_id VARCHAR(120),
  org_code VARCHAR(120),
  org_name VARCHAR(160),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  migration_status VARCHAR(30) NOT NULL DEFAULT 'UNRESOLVED',
  scope_type VARCHAR(40) NOT NULL DEFAULT 'NONE',
  allowed_org_codes TEXT[] NOT NULL DEFAULT '{}',
  allowed_indicator_codes TEXT[] NOT NULL DEFAULT '{}',
  allow_group_ranking BOOLEAN NOT NULL DEFAULT FALSE,
  allow_all_organizations BOOLEAN NOT NULL DEFAULT FALSE,
  remark VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_data_query_user_migration_status CHECK (migration_status IN ('RESOLVED', 'UNRESOLVED'))
);

-- The original pilot table only contained user_name. These guarded alterations
-- keep existing rows recoverable while marking them UNRESOLVED until an
-- administrator maps them to a trusted portal userId and tenantId.
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS user_id VARCHAR(120);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS user_code VARCHAR(120);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(120);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS tenant_name VARCHAR(160);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS org_id VARCHAR(120);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS org_code VARCHAR(120);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS org_name VARCHAR(160);
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS migration_status VARCHAR(30) NOT NULL DEFAULT 'UNRESOLVED';
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'NONE';
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS allowed_org_codes TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS allowed_indicator_codes TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS allow_group_ranking BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_data_query_user ADD COLUMN IF NOT EXISTS allow_all_organizations BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_enabled
  ON ai_data_query_user (enabled);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_updated_at
  ON ai_data_query_user (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_identity
  ON ai_data_query_user (tenant_id, user_id, enabled);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_migration_status
  ON ai_data_query_user (migration_status);

ALTER TABLE ai_data_query_user DROP CONSTRAINT IF EXISTS uk_ai_data_query_user_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_data_query_user_tenant_user
  ON ai_data_query_user (tenant_id, user_id)
  WHERE user_id IS NOT NULL AND migration_status = 'RESOLVED';
