CREATE INDEX IDX_TRIP_DETAIL_PLACES_FOLDER_LIST
    ON trip_detail_places (trip_id, deleted_at, place_name, trip_place_id);
