CREATE INDEX IDX_TRIPS_LIST
    ON trips (user_id, deleted_at, created_at, trip_id);

CREATE INDEX IDX_TRIPS_FAVORITE_LIST
    ON trips (user_id, deleted_at, is_favorite, created_at, trip_id);
