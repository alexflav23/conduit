-- The Hypervolt warranty floor is 36 months (3 years) from activation; the 5-year option adds a 24-month
-- extension (warranty_extension → +24 = 60mo total). The original 24mo seed was the generic EU consumer-law
-- minimum, NOT Hypervolt's product warranty. Correct the term so the LIVE activation path (ActivationService →
-- legalMonths) and the historical backfill both use 36mo. Historical windows were already backfilled at 36mo.
UPDATE legal_warranty SET statutory_months = 36 WHERE statutory_months < 36;
