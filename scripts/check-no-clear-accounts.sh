#!/usr/bin/env bash
# SEC-004 on the running stack: no 12-digit 999… value (a clear account number) in any Postgres text,
# character, JSON or array column after a demo run (hex document IDs and hashes excluded). Prints each offending column; exits 1 if any.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
"${COMPOSE[@]}" exec -T postgres psql -At -v ON_ERROR_STOP=1 -U "${DB_USERNAME:-ielop}" -d "${DB_NAME:-ielop}" <<'SQL'

DO $$
DECLARE r record; n bigint; bad int := 0;
BEGIN
  FOR r IN SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
              AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json', 'ARRAY')
              -- SHA-256 document IDs and hashes are hex and may contain 12 digits in a row; they are not accounts.
              AND column_name NOT IN ('es_doc_id', 'evidence_doc_ids', 'first_failure_doc_id', 'context_hash', 'config_hash', 'counterparty_hash')
  LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE CAST(%I AS text) ~ ''(^|[^0-9])999[0-9]{9}([^0-9]|$)''', r.table_name, r.column_name) INTO n;
    IF n > 0 THEN
      RAISE NOTICE 'clear account number in %.%: % rows', r.table_name, r.column_name, n;
      bad := bad + 1;
    END IF;
  END LOOP;
  IF bad > 0 THEN
    RAISE EXCEPTION 'SEC-004 failed: % columns hold a clear account number', bad;
  END IF;
  RAISE NOTICE 'SEC-004: no clear account number in any text, JSON or array column';
END $$;
SQL
