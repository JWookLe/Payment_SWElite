-- ShedLock table for distributed task scheduling
-- This table manages distributed locks for scheduled tasks across multiple instances (VMs)

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_at DATETIME(3) NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_shedlock_lock_at ON shedlock(lock_at);
