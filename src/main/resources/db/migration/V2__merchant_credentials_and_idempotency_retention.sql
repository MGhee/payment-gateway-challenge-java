CREATE TABLE merchant_api_keys (
  id UUID PRIMARY KEY,
  merchant_id VARCHAR(128) NOT NULL,
  key_prefix VARCHAR(16) NOT NULL,
  key_hash CHAR(64) NOT NULL UNIQUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX merchant_api_keys_active_idx
  ON merchant_api_keys (merchant_id, expires_at);

CREATE TABLE payment_idempotency_keys (
  merchant_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  payment_id UUID NOT NULL UNIQUE REFERENCES payments(id) ON DELETE CASCADE,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (merchant_id, idempotency_key)
);

INSERT INTO payment_idempotency_keys (merchant_id, idempotency_key, payment_id, expires_at)
SELECT merchant_id, idempotency_key, id, created_at + INTERVAL '72' HOUR
FROM payments
WHERE idempotency_key IS NOT NULL;

ALTER TABLE payments DROP CONSTRAINT payments_merchant_idempotency_unique;

CREATE INDEX payment_idempotency_keys_expiry_idx ON payment_idempotency_keys (expires_at);