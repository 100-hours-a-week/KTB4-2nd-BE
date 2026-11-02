-- 수신자 FK의 기존 인덱스를 대체하지 않도록 삭제 여부를 선두에 둔다.
CREATE INDEX idx_trip_share_grantee_active
    ON trip_share_users (deleted_at, grantee_user_id, expires_at);
