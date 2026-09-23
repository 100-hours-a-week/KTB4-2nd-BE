ALTER TABLE users
    ADD COLUMN profile_image_url VARCHAR(2048) NULL AFTER nickname;

ALTER TABLE login_tickets
    ADD COLUMN profile_image_url VARCHAR(2048) NULL AFTER email;

ALTER TABLE profile_tokens
    ADD COLUMN profile_image_url VARCHAR(2048) NULL AFTER email;