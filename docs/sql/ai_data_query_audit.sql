-- 2026-09-02 只读复核：本文件是 DDL 资产，本次未执行。
CREATE TABLE IF NOT EXISTS ai_data_query_audit (
  id BIGSERIAL PRIMARY KEY,
  request_id VARCHAR(100) NOT NULL,
  conversation_id VARCHAR(100),
  dify_conversation_id VARCHAR(120),
  dify_user_id_hash VARCHAR(160),
  user_id VARCHAR(120) NOT NULL,
  user_code VARCHAR(100),
  user_name VARCHAR(80),
  org_code VARCHAR(100),
  org_name VARCHAR(160),
  tenant_id VARCHAR(100),
  identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
  identity_source VARCHAR(40) NOT NULL,
  query_text VARCHAR(2000) NOT NULL,
  analysis_type VARCHAR(60),
  resolved_indicator VARCHAR(160),
  organization_scope VARCHAR(1000),
  time_range VARCHAR(1000),
  data_scope_type VARCHAR(40),
  status VARCHAR(80) NOT NULL,
  error_code VARCHAR(80),
  duration_ms BIGINT NOT NULL DEFAULT 0,
  data_cutoff_date VARCHAR(40),
  dify_request_id VARCHAR(120),
  workflow_run_id VARCHAR(120),
  client_ip VARCHAR(80),
  user_agent VARCHAR(500),
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_audit_request_id
  ON ai_data_query_audit (request_id);
CREATE INDEX IF NOT EXISTS idx_ai_data_query_audit_conversation_id
  ON ai_data_query_audit (conversation_id);
CREATE INDEX IF NOT EXISTS idx_ai_data_query_audit_user_id
  ON ai_data_query_audit (user_id);
CREATE INDEX IF NOT EXISTS idx_ai_data_query_audit_created_at
  ON ai_data_query_audit (created_at DESC);
