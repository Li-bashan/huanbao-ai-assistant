-- 环宝 AI 多用户隔离增量迁移
-- 适用于已经执行过旧版 ai_data_query_user / ai_data_query_audit 的环境。
-- 旧名单只补结构并标记 UNRESOLVED，不根据姓名猜测 userId。

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

UPDATE ai_data_query_user
SET migration_status = 'UNRESOLVED'
WHERE user_id IS NULL OR tenant_id IS NULL;

ALTER TABLE ai_data_query_user DROP CONSTRAINT IF EXISTS uk_ai_data_query_user_name;
CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_identity
  ON ai_data_query_user (tenant_id, user_id, enabled);
CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_migration_status
  ON ai_data_query_user (migration_status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_data_query_user_tenant_user
  ON ai_data_query_user (tenant_id, user_id)
  WHERE user_id IS NOT NULL AND migration_status = 'RESOLVED';

CREATE TABLE IF NOT EXISTS ai_conversation_mapping (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(120) NOT NULL,
  user_id VARCHAR(120) NOT NULL,
  assistant_mode VARCHAR(40) NOT NULL,
  client_conversation_id VARCHAR(120) NOT NULL,
  dify_user_id VARCHAR(120) NOT NULL,
  dify_conversation_id VARCHAR(120),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  active_request_id VARCHAR(120),
  active_until TIMESTAMP,
  last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_ai_conversation_owner_key
    UNIQUE (tenant_id, user_id, assistant_mode, client_conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_mapping_dify
  ON ai_conversation_mapping (dify_user_id, dify_conversation_id);
CREATE INDEX IF NOT EXISTS idx_ai_conversation_mapping_active
  ON ai_conversation_mapping (tenant_id, user_id, active_until);

ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS dify_conversation_id VARCHAR(120);
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS dify_user_id_hash VARCHAR(160);
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS client_ip VARCHAR(80);
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS user_agent VARCHAR(500);
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS data_scope_type VARCHAR(40);
ALTER TABLE ai_data_query_audit ADD COLUMN IF NOT EXISTS error_code VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_audit_dify_conversation
  ON ai_data_query_audit (dify_conversation_id);
