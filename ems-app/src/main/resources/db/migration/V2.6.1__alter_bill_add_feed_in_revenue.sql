-- V2.6.1__alter_bill_add_feed_in_revenue.sql
-- v1.2.0 PV：bill 加上网收入与净额两列。
-- 旧 bill 行 net_amount=NULL 表示尚未计算（v1.2.0 升级前的历史账单）。

ALTER TABLE bill
    ADD COLUMN feed_in_revenue NUMERIC(20,4) NOT NULL DEFAULT 0,
    ADD COLUMN net_amount      NUMERIC(20,4);

CREATE INDEX idx_bill_net_amount ON bill(net_amount) WHERE net_amount IS NOT NULL;

COMMENT ON COLUMN bill.feed_in_revenue IS 'v1.2.0 上网卖电收入抵扣（¥）';
COMMENT ON COLUMN bill.net_amount      IS 'v1.2.0 净额 = amount − feed_in_revenue（¥）';
