-- Baseline extensions used across the schema (doc 00 conventions):
--   pgcrypto -> gen_random_uuid() for UUID primary keys
--   citext   -> case-insensitive text for emails/codes
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
