CREATE TABLE IF NOT EXISTS payment (
  payment_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  merchant_id       VARCHAR(32)  NOT NULL,
  amount            BIGINT       NOT NULL CHECK (amount > 0),
  currency          CHAR(3)      NOT NULL DEFAULT 'KRW',
  status            ENUM('REQUESTED','COMPLETED','REFUNDED','CANCELLED') NOT NULL,
  idempotency_key   VARCHAR(64)  NOT NULL,
  requested_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idem (merchant_id, idempotency_key),
  KEY ix_status_time (status, requested_at),
  KEY ix_merchant_time (merchant_id, requested_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ledger_entry (
  entry_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id     BIGINT       NOT NULL,
  debit_account  VARCHAR(64)  NOT NULL,
  credit_account VARCHAR(64)  NOT NULL,
  amount         BIGINT       NOT NULL CHECK (amount > 0),
  occurred_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_ledger_payment
    FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
    ON DELETE CASCADE,
  KEY ix_payment_time (payment_id, occurred_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_event (
  event_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  aggregate_type  VARCHAR(32) NOT NULL,
  aggregate_id    BIGINT      NOT NULL,
  event_type      VARCHAR(32) NOT NULL,
  payload         JSON        NOT NULL,
  published       TINYINT(1)  NOT NULL DEFAULT 0,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY ix_pub_created (published, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS idem_response_cache (
  merchant_id      VARCHAR(32) NOT NULL,
  idempotency_key  VARCHAR(64) NOT NULL,
  http_status      INT         NOT NULL,
  response_body    JSON        NOT NULL,
  created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (merchant_id, idempotency_key)
) ENGINE=InnoDB;
