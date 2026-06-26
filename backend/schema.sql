CREATE DATABASE IF NOT EXISTS spendwise;
USE spendwise;

CREATE TABLE IF NOT EXISTS users (
    uid VARCHAR(128) PRIMARY KEY,
    name VARCHAR(100) NOT NULL DEFAULT '',
    email VARCHAR(150) NOT NULL DEFAULT '',
    gender VARCHAR(30),
    explicit_profile_image_url TEXT,
    google_photo_url TEXT,
    providers JSON,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS expenses (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uid VARCHAR(128) NOT NULL,
    local_id INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    amount DOUBLE NOT NULL,
    category VARCHAR(100) NOT NULL,
    expense_date BIGINT NOT NULL,
    updated_at BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY unique_user_expense (uid, local_id),
    INDEX idx_expenses_uid_date (uid, expense_date),
    CONSTRAINT fk_expenses_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE
);
