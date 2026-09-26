-- Synchronous checkout cleanup safety: retain same-SKU edits and delete/re-add operations.
-- MySQL 8, repeatable. Stop all old Commerce writers first: old binaries do not increment revision.
-- Existing rows start at revision 0; new code snapshots row id + revision from MySQL and
-- increments revision on every add/quantity/selection update, even when visible values are unchanged.
-- No cart content is deleted, replayed or guessed by this migration.
SET @cart_revision_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = 'fulfillment_commerce' AND table_name = 'cart_item' AND column_name = 'revision');
SET @cart_revision_sql = IF(@cart_revision_exists = 0,
 'ALTER TABLE fulfillment_commerce.cart_item ADD COLUMN revision BIGINT NOT NULL DEFAULT 0',
 'SELECT 1');
PREPARE cart_revision_stmt FROM @cart_revision_sql;
EXECUTE cart_revision_stmt;
DEALLOCATE PREPARE cart_revision_stmt;
