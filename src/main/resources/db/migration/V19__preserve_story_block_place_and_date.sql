ALTER TABLE story_blocks
    ADD COLUMN source_trip_place_id BIGINT NOT NULL,
    ADD COLUMN source_date DATE NOT NULL,
    ADD CONSTRAINT fk_story_blocks_source_place
        FOREIGN KEY (source_trip_place_id)
            REFERENCES trip_detail_places (trip_place_id);
