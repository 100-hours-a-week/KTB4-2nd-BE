package com.yeodam.yeodambe.story.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class StorySchemaMigrationTest {
    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");

    @Test
    void 기존_여행과_이력을_보존하고_길이_FK_첨부중복을_검증한다() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target("11").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("INSERT INTO trips (trip_id,user_id,trip_name,start_date,end_date) VALUES (901,1,'기존여행','2026-10-07','2026-10-07')");
        jdbc.update("INSERT INTO files (file_id,user_id,original_file_name,object_key,mime_type) VALUES (901,1,'사진','원본','image/jpeg')");
        jdbc.update("INSERT INTO trip_attachments (trip_attachment_id,trip_id,file_id,region_origin,issue,analyze_storage_key,preview_storage_key) VALUES (901,901,901,'UNKNOWN','NONE','분석','미리보기')");
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
        assertThat(jdbc.queryForObject("SELECT current_story_id FROM trips WHERE trip_id=901", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id=901", String.class)).isEqualTo("기존여행");
        jdbc.update("INSERT INTO stories(story_id,trip_id,mood,story_summary) VALUES (901,901,'PLAIN',?),(902,901,'CALM','이력')", "가".repeat(40));
        jdbc.update("UPDATE trips SET current_story_id=901 WHERE trip_id=901");
        jdbc.update("UPDATE trips SET current_story_id=902 WHERE trip_id=901");
        assertThat(jdbc.queryForObject("SELECT current_story_id FROM trips WHERE trip_id=901", Long.class)).isEqualTo(902L);
        jdbc.update("INSERT INTO story_blocks(story_id,trip_attachment_id,order_number,day_label,detail_summary,memo) VALUES (901,901,1,?,?,?)", "가".repeat(40), "나".repeat(40), "다".repeat(125));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO story_blocks(story_id,trip_attachment_id,order_number,day_label,detail_summary) VALUES (901,901,2,'날','문구')")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO story_blocks(story_id,trip_attachment_id,order_number,day_label,detail_summary) VALUES (902,901,1,'날','문구')");
        assertThatThrownBy(() -> jdbc.update("UPDATE trips SET current_story_id=999 WHERE trip_id=901")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stories SET story_summary=? WHERE story_id=901", "가".repeat(41))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE story_blocks SET day_label=? WHERE story_id=901", "가".repeat(41))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE story_blocks SET detail_summary=? WHERE story_id=901", "가".repeat(41))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE story_blocks SET memo=? WHERE story_id=901", "가".repeat(126))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO story_selected_places(story_id,trip_place_id) VALUES (901,999)")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM stories WHERE story_id=902")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
