-- Graded committable curve: when on, the flex-window change tolerance rises CONTINUOUSLY from the frozen
-- tolerance at the lead-time edge to flex_tolerance_pct at the horizon — no cliff at the boundary, the right
-- shape as forecasting goes continuous/real-time.
ALTER TABLE supply_commitment_policy ADD COLUMN graded BOOLEAN NOT NULL DEFAULT false;
