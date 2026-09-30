CREATE INDEX IDX_TRIPS_LIST_START_DATE
    ON trips (user_id, deleted_at, start_date, trip_id);

CREATE INDEX IDX_TRIPS_FAVORITE_LIST_START_DATE
    ON trips (user_id, deleted_at, is_favorite, start_date, trip_id);

DROP INDEX IDX_TRIPS_LIST ON trips;

DROP INDEX IDX_TRIPS_FAVORITE_LIST ON trips;
