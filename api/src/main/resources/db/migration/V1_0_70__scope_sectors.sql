-- M-Assurance B / permission-builder (doc 05 §2): the SECTOR scope axis. Geography is already the market
-- and channel already distinguishes wholesale/retail/installer, so "UK Wholesale" is expressible today
-- (scope_markets={UK} ∧ scope_channels={wholesale}). Sector (party.sector: energy/installers/…) was the
-- missing dimension — this adds it, so a grant can be narrowed to "UK Wholesale, energy sector" and no other.
-- Empty array = unconstrained on sector (the existing axes' convention). Codes reference the sector table.
ALTER TABLE role_assignment ADD COLUMN scope_sectors TEXT[] NOT NULL DEFAULT '{}';
