-- Idempotent schema. Every table for every module is created up front.

CREATE SEQUENCE IF NOT EXISTS audit_seq START 1;
CREATE SEQUENCE IF NOT EXISTS payment_seq START 1;

CREATE TABLE IF NOT EXISTS tenants (
  tenant_id text PRIMARY KEY,
  name text NOT NULL,
  logo_url text,
  primary_color text,
  secondary_color text
);

CREATE TABLE IF NOT EXISTS users (
  user_id text PRIMARY KEY,
  email text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  role text NOT NULL,
  tenant_id text,
  club_id text,
  player_id text,
  league_ids text[] NOT NULL DEFAULT '{}',
  failed_logins int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash text PRIMARY KEY,
  user_id text NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_policies (
  role text NOT NULL,
  resource text NOT NULL,
  action text NOT NULL,
  allowed boolean NOT NULL,
  PRIMARY KEY (role, resource, action)
);

CREATE TABLE IF NOT EXISTS settings (
  key text PRIMARY KEY,
  value jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS sports (
  sport_id text PRIMARY KEY,
  name text NOT NULL,
  scoring_model text NOT NULL,
  config jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seasons (
  season_id text PRIMARY KEY,
  tenant_id text,
  name text NOT NULL,
  start_date date,
  end_date date
);

CREATE TABLE IF NOT EXISTS leagues (
  league_id text PRIMARY KEY,
  tenant_id text,
  sport_id text NOT NULL,
  kind text NOT NULL DEFAULT 'league',
  name text,
  season_id text,
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS league_teams (
  league_id text NOT NULL,
  team_id text NOT NULL,
  position int NOT NULL DEFAULT 0,
  PRIMARY KEY (league_id, team_id)
);

CREATE TABLE IF NOT EXISTS venues (
  venue_id text PRIMARY KEY,
  tenant_id text,
  name text NOT NULL,
  lat double precision,
  lng double precision
);

CREATE TABLE IF NOT EXISTS venue_availability (
  venue_id text NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  PRIMARY KEY (venue_id, starts_at)
);

CREATE TABLE IF NOT EXISTS matches (
  match_id text PRIMARY KEY,
  sport_id text NOT NULL,
  league_id text,
  home_team_id text NOT NULL,
  away_team_id text NOT NULL,
  origin text NOT NULL,
  version int NOT NULL DEFAULT 0,
  venue_id text,
  scheduled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_matches_league ON matches (league_id);

CREATE TABLE IF NOT EXISTS match_events (
  event_id text PRIMARY KEY,
  match_id text NOT NULL,
  sequence int NOT NULL,
  type text NOT NULL,
  payload jsonb NOT NULL,
  payload_canonical text NOT NULL,
  signature text NOT NULL,
  request_canonical text NOT NULL,
  compensates text,
  response json,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_match_events_seq UNIQUE (match_id, sequence)
);
ALTER TABLE match_events ADD COLUMN IF NOT EXISTS response json;
CREATE UNIQUE INDEX IF NOT EXISTS uq_match_events_compensates ON match_events (compensates) WHERE compensates IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_match_events_match_seq ON match_events (match_id, sequence);

CREATE TABLE IF NOT EXISTS clubs (
  club_id text PRIMARY KEY,
  tenant_id text NOT NULL,
  name text NOT NULL,
  logo_url text,
  venue jsonb,
  socials jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS club_staff (
  club_id text NOT NULL,
  name text NOT NULL,
  role text NOT NULL,
  PRIMARY KEY (club_id, name, role)
);

CREATE TABLE IF NOT EXISTS club_season_stats (
  club_id text NOT NULL,
  season text NOT NULL,
  stats jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (club_id, season)
);

CREATE TABLE IF NOT EXISTS squads (
  squad_id text PRIMARY KEY,
  league_id text,
  club_id text,
  tenant_id text,
  name text,
  captain_player_id text,
  config jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_squads_league ON squads (league_id);

CREATE TABLE IF NOT EXISTS players (
  player_id text PRIMARY KEY,
  club_id text,
  tenant_id text,
  name text,
  position text
);
ALTER TABLE players ADD COLUMN IF NOT EXISTS club_id text;
ALTER TABLE players ADD COLUMN IF NOT EXISTS tenant_id text;

CREATE TABLE IF NOT EXISTS registrations (
  league_id text NOT NULL,
  squad_id text NOT NULL,
  player_id text NOT NULL,
  role text NOT NULL DEFAULT 'player',
  PRIMARY KEY (league_id, player_id)
);

CREATE TABLE IF NOT EXISTS roster_initial (
  league_id text NOT NULL,
  squad_id text NOT NULL,
  player_id text NOT NULL,
  role text NOT NULL DEFAULT 'player',
  PRIMARY KEY (league_id, player_id)
);

CREATE TABLE IF NOT EXISTS roster_live_matches (
  league_id text NOT NULL,
  match_id text NOT NULL,
  player_id text NOT NULL,
  PRIMARY KEY (league_id, match_id, player_id)
);

CREATE TABLE IF NOT EXISTS roster_audit (
  audit_id text PRIMARY KEY,
  audit_seq bigint NOT NULL DEFAULT nextval('audit_seq'),
  league_id text,
  player_id text NOT NULL,
  action text NOT NULL,
  from_squad_id text,
  to_squad_id text,
  effective_date date,
  loan_end_date date,
  loan_audit_id text,
  who text,
  why text,
  at timestamptz NOT NULL DEFAULT now(),
  request_canonical text,
  response json
);
CREATE INDEX IF NOT EXISTS idx_roster_audit_player ON roster_audit (player_id, audit_seq);
CREATE UNIQUE INDEX IF NOT EXISTS uq_roster_audit_loan_return ON roster_audit (loan_audit_id) WHERE action = 'loan_return';

CREATE TABLE IF NOT EXISTS roster_requests (
  request_id text PRIMARY KEY,
  request_canonical text NOT NULL,
  response json NOT NULL,
  status int NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
  product_id text PRIMARY KEY,
  tenant_id text NOT NULL,
  club_id text,
  category text,
  name text NOT NULL,
  image_url text
);

CREATE TABLE IF NOT EXISTS variants (
  variant_id text PRIMARY KEY,
  product_id text NOT NULL,
  size text,
  color text,
  price_minor bigint NOT NULL,
  currency text NOT NULL DEFAULT 'PKR',
  stock int NOT NULL DEFAULT 0 CHECK (stock >= 0),
  reserved int NOT NULL DEFAULT 0 CHECK (reserved >= 0)
);

CREATE TABLE IF NOT EXISTS price_schedules (
  variant_id text NOT NULL,
  starts_at timestamptz NOT NULL,
  price_minor bigint NOT NULL,
  PRIMARY KEY (variant_id, starts_at)
);

CREATE TABLE IF NOT EXISTS discount_codes (
  code text PRIMARY KEY,
  percent int NOT NULL,
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS orders (
  order_id text PRIMARY KEY,
  customer_id text,
  status text NOT NULL,
  subtotal_minor bigint,
  discount_minor bigint,
  total_minor bigint,
  currency text NOT NULL DEFAULT 'PKR',
  request_canonical text,
  response json,
  http_status int,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_items (
  order_id text NOT NULL,
  variant_id text NOT NULL,
  quantity int NOT NULL,
  unit_price_minor bigint NOT NULL,
  PRIMARY KEY (order_id, variant_id)
);

CREATE TABLE IF NOT EXISTS reservations (
  reservation_id text PRIMARY KEY,
  order_id text NOT NULL,
  variant_id text NOT NULL,
  quantity int NOT NULL,
  status text NOT NULL DEFAULT 'held',
  expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reservations_expiry ON reservations (expires_at) WHERE status = 'held';

CREATE TABLE IF NOT EXISTS payments (
  payment_id text PRIMARY KEY,
  order_id text NOT NULL,
  token text,
  status text NOT NULL,
  amount_minor bigint,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_status_history (
  id bigserial PRIMARY KEY,
  order_id text NOT NULL,
  status text NOT NULL,
  at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS match_pins (
  match_id text NOT NULL,
  product_id text NOT NULL,
  PRIMARY KEY (match_id, product_id)
);

CREATE TABLE IF NOT EXISTS outbox (
  id bigserial PRIMARY KEY,
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS offline_actions (
  action_id text PRIMARY KEY,
  device_id text,
  type text,
  payload jsonb,
  result jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Append-only enforcement. TRUNCATE (used by reset) is not blocked by row triggers.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_match_events_append_only ON match_events;
CREATE TRIGGER trg_match_events_append_only BEFORE UPDATE OR DELETE ON match_events
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

DROP TRIGGER IF EXISTS trg_roster_audit_append_only ON roster_audit;
CREATE TRIGGER trg_roster_audit_append_only BEFORE UPDATE OR DELETE ON roster_audit
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
