-- M-Forecast: the dispatch-line joins behind the dispatch-dated demand basis (the ASC-606 series).
CREATE INDEX IF NOT EXISTS dispatch_line_dispatch_idx ON dispatch_line (dispatch_id);
CREATE INDEX IF NOT EXISTS dispatch_line_order_line_idx ON dispatch_line (order_line_id);
