CREATE TABLE IF NOT EXISTS ai_data_query_user (
  id BIGSERIAL PRIMARY KEY,
  user_name VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  remark VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_ai_data_query_user_name UNIQUE (user_name)
);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_enabled
  ON ai_data_query_user (enabled);

CREATE INDEX IF NOT EXISTS idx_ai_data_query_user_updated_at
  ON ai_data_query_user (updated_at DESC);
