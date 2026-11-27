-- 历史租户未设置席位上限，统一补默认值，使席位在平台上可见、可校验
UPDATE org SET seat_limit = 20 WHERE seat_limit IS NULL;
