<?php
/**
 * Database connection singleton
 */
require_once __DIR__ . '/config.php';

function getDB() {
    static $pdo = null;
    if ($pdo === null) {
        try {
            $pdo = new PDO(
                'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
                DB_USER,
                DB_PASS,
                [
                    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                    PDO::ATTR_EMULATE_PREPARES => false,
                ]
            );
        } catch (PDOException $e) {
            die('Database connection failed: ' . $e->getMessage());
        }
    }
    return $pdo;
}

// Run pending migrations on first request
function runMigrations() {
    static $ran = false;
    if ($ran) return;
    $ran = true;

    $db = getDB();
    $migrations = [
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(20) NULL AFTER email",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password TINYINT(1) DEFAULT 0 AFTER password",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS whatsapp_type ENUM('whatsapp', 'whatsapp_business') DEFAULT 'whatsapp' AFTER is_active",
        "ALTER TABLE users MODIFY COLUMN whatsapp_type ENUM('whatsapp', 'whatsapp_business', 'load_balance') DEFAULT 'whatsapp'",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) DEFAULT 'Africa/Nairobi' AFTER whatsapp_type",
        "CREATE TABLE IF NOT EXISTS subscriptions (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_id INT NOT NULL,
            plan_name VARCHAR(50) NOT NULL DEFAULT 'free',
            messages_limit INT DEFAULT 100,
            messages_used INT DEFAULT 0,
            is_active TINYINT(1) DEFAULT 1,
            starts_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            expires_at DATETIME NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_user_id (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "ALTER TABLE messages MODIFY COLUMN status ENUM('pending', 'sent', 'delivered', 'failed', 'expired') DEFAULT 'pending'",
        "CREATE TABLE IF NOT EXISTS devices (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_id INT NOT NULL,
            device_id VARCHAR(64) NOT NULL UNIQUE,
            device_name VARCHAR(100) DEFAULT 'Phone',
            whatsapp_type ENUM('whatsapp', 'whatsapp_business', 'both') DEFAULT 'whatsapp',
            is_active TINYINT(1) DEFAULT 1,
            last_seen DATETIME NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_user_id (user_id),
            INDEX idx_device_id (device_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "ALTER TABLE messages ADD COLUMN IF NOT EXISTS device_id VARCHAR(64) NULL AFTER api_key_id",
    ];

    foreach ($migrations as $sql) {
        try {
            $db->exec($sql);
        } catch (PDOException $e) {
            // Ignore duplicate column / already exists errors
            if (strpos($e->getMessage(), 'Duplicate column') === false &&
                strpos($e->getMessage(), 'already exists') === false) {
                error_log("Migration error: " . $e->getMessage());
            }
        }
    }
}

// Auto-run migrations
runMigrations();
