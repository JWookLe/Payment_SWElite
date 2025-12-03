CREATE TABLE IF NOT EXISTS payment (
  payment_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  merchant_id       VARCHAR(32)  NOT NULL,
  amount            BIGINT       NOT NULL CHECK (amount > 0),
  currency          CHAR(3)      NOT NULL DEFAULT 'KRW',
  status            VARCHAR(50)  NOT NULL,
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
  published_at    TIMESTAMP(3),
  retry_count     INT         NOT NULL DEFAULT 0,
  last_retry_at   TIMESTAMP(3),
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

-- 정산 요청 테이블 (PG 매입 확정 관리)
CREATE TABLE IF NOT EXISTS settlement_request (
  id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id              BIGINT          NOT NULL,
  request_amount          DECIMAL(15,2)   NOT NULL CHECK (request_amount > 0),
  status                  VARCHAR(50)     NOT NULL,  -- PENDING, SUCCESS, FAILED
  pg_transaction_id       VARCHAR(255),              -- PG사 트랜잭션 ID
  pg_response_code        VARCHAR(50),               -- PG 응답 코드
  pg_response_message     TEXT,                      -- PG 응답 메시지
  retry_count             INT             NOT NULL DEFAULT 0,
  last_retry_at           TIMESTAMP(3),
  requested_at            TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at            TIMESTAMP(3),
  CONSTRAINT fk_settlement_payment
    FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
    ON DELETE CASCADE,
  KEY ix_status_requested (status, requested_at),
  KEY ix_payment (payment_id)
) ENGINE=InnoDB;

-- 환불 요청 테이블 (PG 환불 처리 관리)
CREATE TABLE IF NOT EXISTS refund_request (
  id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id                  BIGINT          NOT NULL,
  refund_amount               DECIMAL(15,2)   NOT NULL CHECK (refund_amount > 0),
  refund_reason               VARCHAR(500),
  status                      VARCHAR(50)     NOT NULL,  -- PENDING, SUCCESS, FAILED
  pg_cancel_transaction_id    VARCHAR(255),              -- PG 취소 트랜잭션 ID
  pg_response_code            VARCHAR(50),
  pg_response_message         TEXT,
  retry_count                 INT             NOT NULL DEFAULT 0,
  last_retry_at               TIMESTAMP(3),
  requested_at                TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at                TIMESTAMP(3),
  CONSTRAINT fk_refund_payment
    FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
    ON DELETE CASCADE,
  KEY ix_status_requested (status, requested_at),
  KEY ix_payment (payment_id)
) ENGINE=InnoDB;
