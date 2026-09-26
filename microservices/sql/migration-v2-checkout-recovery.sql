-- R03: durable Commerce checkout intent and bounded recovery. MySQL 8; repeatable.
-- Stop old Commerce writers before upgrading. Existing status values stay unchanged:
-- 0 processing, 1 submitted, 2 rejected. Existing unique(user_id,idempotency_key) stays unchanged.
-- recovery_state: 0 automatic, 1 manual review. DEFAULT 1 deliberately fails closed for
-- old binaries/rows that have no full immutable payload or reliable order association.
-- New code explicitly inserts 0 together with payload, allocated order_id and DB-clock deadlines.
-- No historical payload/order_id is inferred; no existing result or business row is rewritten.
SET @r03_schema = 'fulfillment_commerce';

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'request_payload');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN request_payload JSON NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'recovery_deadline');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN recovery_deadline DATETIME NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'recovery_state');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN recovery_state TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'lease_owner');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN lease_owner VARCHAR(64) NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'lease_until');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN lease_until DATETIME NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'attempt_count');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN attempt_count INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'next_retry_time');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN next_retry_time DATETIME NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'result_state');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN result_state VARCHAR(32) NULL', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND column_name = 'cart_cleanup_required');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD COLUMN cart_cleanup_required TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;

SET @r03_exists = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema = @r03_schema AND table_name = 'checkout_request' AND index_name = 'idx_checkout_recovery');
SET @r03_sql = IF(@r03_exists = 0,
 'ALTER TABLE fulfillment_commerce.checkout_request ADD KEY idx_checkout_recovery (status, recovery_state, next_retry_time, lease_until)',
 'SELECT 1');
PREPARE r03_stmt FROM @r03_sql;
EXECUTE r03_stmt;
DEALLOCATE PREPARE r03_stmt;
