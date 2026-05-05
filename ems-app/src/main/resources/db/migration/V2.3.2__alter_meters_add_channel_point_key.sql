-- F2 解耦：把 InfluxDB collector → meter 关联从 code 解耦到独立的 channel_point_key 列。
--
-- 旧约定：InfluxSampleWriter 用 (channelId, code) 反查 meter，迫使 meter.code === channel.point.key。
-- 这个隐式约束让 code 既是业务标识又是采集器键，两个职责打架，
-- 而且跨 channel 同名 point key 时会触发 code unique 约束冲突。
--
-- 新约定：service/sink 用 (channelId, channel_point_key) 反查；code 解放为纯业务标识，
-- 可自由命名（如 "name-slug + 6 位随机后缀"），后端不再要求它等于 point key。

ALTER TABLE meters
    ADD COLUMN channel_point_key VARCHAR(64);

-- 回填：旧数据满足 code === point.key 约定，直接拷贝
UPDATE meters
SET channel_point_key = code
WHERE channel_id IS NOT NULL;

-- 一致性约束：channel_id 和 channel_point_key 必须同时为空或同时非空
ALTER TABLE meters
    ADD CONSTRAINT chk_meters_channel_pair_consistent
    CHECK ((channel_id IS NULL AND channel_point_key IS NULL)
        OR (channel_id IS NOT NULL AND channel_point_key IS NOT NULL));

-- 同一 channel 下同一 point key 只能有一个 meter（部分唯一索引；channel_id 为空的 meter 不参与）
CREATE UNIQUE INDEX uk_meters_channel_point ON meters(channel_id, channel_point_key)
    WHERE channel_id IS NOT NULL;
