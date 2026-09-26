-- R05: compatible payment Outbox ownership and audited manual dead-letter redrive.
-- MySQL 8, repeatable. Stop ALL old order-service publishers before migration/deployment:
-- old binaries write only by status and cannot obey the new ownership fence.
-- Do not change statuses (0 pending, 1 processing, 2 sent, 3 dead) or reset existing rows.
SET @r05_schema = 'fulfillment_order';

SET @r05_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r05_schema AND table_name = 'order_outbox_event' AND column_name = 'lease_owner');
SET @r05_sql = IF(@r05_exists = 0,
 'ALTER TABLE fulfillment_order.order_outbox_event ADD COLUMN lease_owner VARCHAR(128) NULL', 'SELECT 1');
PREPARE r05_stmt FROM @r05_sql;
EXECUTE r05_stmt;
DEALLOCATE PREPARE r05_stmt;

SET @r05_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r05_schema AND table_name = 'order_outbox_event' AND column_name = 'lease_until');
SET @r05_sql = IF(@r05_exists = 0,
 'ALTER TABLE fulfillment_order.order_outbox_event ADD COLUMN lease_until DATETIME(6) NULL', 'SELECT 1');
PREPARE r05_stmt FROM @r05_sql;
EXECUTE r05_stmt;
DEALLOCATE PREPARE r05_stmt;

SET @r05_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r05_schema AND table_name = 'order_outbox_event' AND column_name = 'redrive_count');
SET @r05_sql = IF(@r05_exists = 0,
 'ALTER TABLE fulfillment_order.order_outbox_event ADD COLUMN redrive_count INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE r05_stmt FROM @r05_sql;
EXECUTE r05_stmt;
DEALLOCATE PREPARE r05_stmt;

SET @r05_exists = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema = @r05_schema AND table_name = 'order_outbox_event' AND index_name = 'idx_outbox_lease');
SET @r05_sql = IF(@r05_exists = 0,
 'ALTER TABLE fulfillment_order.order_outbox_event ADD KEY idx_outbox_lease (status, lease_until)', 'SELECT 1');
PREPARE r05_stmt FROM @r05_sql;
EXECUTE r05_stmt;
DEALLOCATE PREPARE r05_stmt;

CREATE TABLE IF NOT EXISTS fulfillment_order.order_outbox_redrive_audit (
  audit_id BIGINT NOT NULL AUTO_INCREMENT,
  request_id CHAR(36) NOT NULL,
  event_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  actor VARCHAR(128) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  previous_status TINYINT NOT NULL,
  previous_retry_count INT NOT NULL,
  previous_last_error VARCHAR(500) NULL,
  previous_next_retry_time DATETIME NOT NULL,
  previous_update_time DATETIME NOT NULL,
  previous_redrive_count INT NOT NULL,
  redrive_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (audit_id),
  UNIQUE KEY uk_redrive_request (request_id),
  UNIQUE KEY uk_redrive_event_generation (event_id, previous_redrive_count),
  KEY idx_redrive_event_time (event_id, redrive_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
