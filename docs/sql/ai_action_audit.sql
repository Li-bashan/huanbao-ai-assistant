CREATE TABLE IF NOT EXISTS ai_action_audit (
  id BIGSERIAL PRIMARY KEY,
  action_id VARCHAR(80) NOT NULL,
  user_id VARCHAR(80),
  user_name VARCHAR(80),
  query_text VARCHAR(1000),
  action VARCHAR(40) NOT NULL,
  form_code VARCHAR(100),
  func_id VARCHAR(80),
  status VARCHAR(40),
  has_fields BOOLEAN NOT NULL DEFAULT FALSE,
  sent_at TIMESTAMPTZ,
  result VARCHAR(40) NOT NULL,
  error_message VARCHAR(1000),
  client_ip VARCHAR(80),
  user_agent VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_action_audit_action_id
  ON ai_action_audit (action_id);

CREATE INDEX IF NOT EXISTS idx_ai_action_audit_user_id
  ON ai_action_audit (user_id);

CREATE INDEX IF NOT EXISTS idx_ai_action_audit_action
  ON ai_action_audit (action);

CREATE INDEX IF NOT EXISTS idx_ai_action_audit_form_code
  ON ai_action_audit (form_code);

CREATE INDEX IF NOT EXISTS idx_ai_action_audit_result
  ON ai_action_audit (result);

CREATE INDEX IF NOT EXISTS idx_ai_action_audit_created_at
  ON ai_action_audit (created_at DESC);
