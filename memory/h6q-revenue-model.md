---
name: h6q-revenue-model
description: How H6Q forecasting relates to shipped actuals and ASC 606 revenue recognition in Conduit
metadata:
  type: project
---

The CEO's mental model for H6Q + revenue (stated 2026-06-04), guiding the build:

- **H6Q runs AHEAD of shipping** — it is forward *demand*, not actuals. Different demand types do NOT
  equate: manual rep estimates ≠ weighted pipeline ≠ committed orders ≠ dispatched. Keep them distinct.
- **The "shipped view" is the actuals side.** As demand transforms into POs/orders and then dispatches,
  there is a separate lens on what was *actually dispatched*. The H6Q coverage board shows the full chain:
  Forecast → Shipped (sell-in) → Activated (sell-through, v3 only) → Coverage.
- **ASC 606: revenue recognises on dispatch** (control transfer on delivery), and it must be *provable* via
  the TigerBeetle immutable log — "generate a clear order for revenue recognition once something has actually
  shipped." Built: `RevenueRecognitionService` + `revenue_recognition` table (V1_0_17) posting DR AR / CR
  Revenue + VAT and DR COGS / CR INV at specific batch landed cost; deterministic transfer ids; idempotent.
- **Sources differ by channel:** retail forecast is driven by **Prophet** (lands as `source='hyperview'`);
  **all other channels require manual weekly agent capture.** Precedence default = manual overrides hyperview.
- **Every H6Q update must propagate** so people learn forward visibility shifted — internal (exec, owners)
  AND external partners, explicitly **the contract manufacturer (Volex/Luxshare)** whose supply commitments +
  6-month buffer + 20%-margin penalty ride on our forward demand. Built: `forecast.coverage.updated` event +
  `notification_subscription`/`notification` fan-out with a materiality threshold. See [[h6q-build-state]].

GitHub remote: `https://github.com/alexflav23/conduit` (push `main` + `m0-m1-foundations`); GitLab origin auth expired.
