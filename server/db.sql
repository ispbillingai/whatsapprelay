-- WhatsApp Relay Server Database Schema

CREATE DATABASE IF NOT EXISTS whatsappbulk;
USE whatsappbulk;

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role ENUM('admin', 'user') DEFAULT 'user',
    is_active TINYINT(1) DEFAULT 1,
    whatsapp_type ENUM('whatsapp', 'whatsapp_business') DEFAULT 'whatsapp',
    last_login DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- API Keys table (each user can have multiple keys)
CREATE TABLE IF NOT EXISTS api_keys (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    api_key VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL DEFAULT 'Default',
    is_active TINYINT(1) DEFAULT 1,
    last_used_at DATETIME NULL,
    request_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_api_key (api_key),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Messages table (now linked to users)
CREATE TABLE IF NOT EXISTS messages (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    api_key_id INT NULL,
    phone VARCHAR(20) NOT NULL COMMENT 'Phone number with country code e.g. 27812345678',
    message TEXT NOT NULL,
    whatsapp_type ENUM('whatsapp', 'whatsapp_business') DEFAULT 'whatsapp',
    status ENUM('pending', 'sent', 'delivered', 'failed', 'expired') DEFAULT 'pending',
    retry_count INT DEFAULT 0,
    priority INT DEFAULT 0 COMMENT 'Higher = more urgent',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    error_message VARCHAR(500) NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_status (status),
    INDEX idx_user_status (user_id, status),
    INDEX idx_created (created_at),
    INDEX idx_priority_status (priority DESC, status, created_at ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Message log
CREATE TABLE IF NOT EXISTS message_log (
    id INT AUTO_INCREMENT PRIMARY KEY,
    message_id INT NOT NULL,
    action VARCHAR(50) NOT NULL,
    details TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Subscriptions table (for future billing integration)
CREATE TABLE IF NOT EXISTS subscriptions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    plan_name VARCHAR(50) NOT NULL DEFAULT 'free',
    messages_limit INT DEFAULT 100 COMMENT 'Max messages per month, 0 = unlimited',
    messages_used INT DEFAULT 0 COMMENT 'Messages used this billing period',
    is_active TINYINT(1) DEFAULT 1,
    starts_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL COMMENT 'NULL = never expires',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Insert default admin user (password: admin123 - CHANGE THIS!)
INSERT INTO users (name, email, password, role) VALUES
('Admin', 'admin@admin.com', '$2y$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'admin');

-- Insert default API key for admin
INSERT INTO api_keys (user_id, api_key, label) VALUES
(1, 'your-secret-api-key-change-this', 'Default Key');
