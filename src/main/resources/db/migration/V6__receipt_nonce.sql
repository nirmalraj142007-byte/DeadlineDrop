-- A signed receipt carries a one-time nonce. Redeeming it creates at most one row: a second
-- finalize with the same receipt finds this row and replays its status.
ALTER TABLE submissions ADD COLUMN receipt_nonce uuid UNIQUE;
