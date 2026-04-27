<?php
/**
 * Database connection singleton
 */
require_once __DIR__ . '/config.php';

// Enable error logging
error_reporting(E_ALL);
ini_set('log_errors', 1);
ini_set('error_log', __DIR__ . '/error.log');

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
            // Don't set MySQL timezone - DATETIME columns don't convert
            // PHP handles all timezone display via date_default_timezone_set()
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

    // Helper: check if column exists before adding
    function columnExists($db, $table, $column) {
        $stmt = $db->prepare("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?");
        $stmt->execute([$table, $column]);
        return $stmt->fetchColumn() > 0;
    }

    // CREATE TABLE migrations (safe with IF NOT EXISTS)
    $creates = [
        "CREATE TABLE IF NOT EXISTS remember_tokens (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_id INT NOT NULL,
            token_hash VARCHAR(64) NOT NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_token (token_hash),
            INDEX idx_user (user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
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
        "CREATE TABLE IF NOT EXISTS devices (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_id INT NOT NULL,
            device_id VARCHAR(64) NOT NULL UNIQUE,
            device_name VARCHAR(100) DEFAULT 'Phone',
            whatsapp_type ENUM('whatsapp', 'whatsapp_business', 'both') DEFAULT 'whatsapp',
            is_active TINYINT(1) DEFAULT 1,
            last_seen DATETIME NULL,
            svc_accessibility TINYINT(1) DEFAULT 0,
            svc_notification TINYINT(1) DEFAULT 0,
            svc_battery TINYINT(1) DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            INDEX idx_user_id (user_id),
            INDEX idx_device_id (device_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS server_metrics (
            id INT AUTO_INCREMENT PRIMARY KEY,
            cpu_load DECIMAL(5,2) DEFAULT 0,
            ram_percent DECIMAL(5,2) DEFAULT 0,
            ram_used_mb INT DEFAULT 0,
            ram_total_mb INT DEFAULT 0,
            disk_percent DECIMAL(5,2) DEFAULT 0,
            disk_used_gb DECIMAL(8,2) DEFAULT 0,
            disk_total_gb DECIMAL(8,2) DEFAULT 0,
            mysql_connections INT DEFAULT 0,
            mysql_queries INT DEFAULT 0,
            messages_pending INT DEFAULT 0,
            messages_sent_minute INT DEFAULT 0,
            active_devices INT DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_created (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    ];

    foreach ($creates as $sql) {
        try { $db->exec($sql); } catch (PDOException $e) { /* table exists */ }
    }

    // ADD COLUMN migrations (check first, MySQL doesn't support IF NOT EXISTS)
    $columns = [
        ['users', 'phone', "ALTER TABLE users ADD COLUMN phone VARCHAR(20) NULL AFTER email"],
        ['users', 'must_change_password', "ALTER TABLE users ADD COLUMN must_change_password TINYINT(1) DEFAULT 0 AFTER password"],
        ['users', 'whatsapp_type', "ALTER TABLE users ADD COLUMN whatsapp_type ENUM('whatsapp', 'whatsapp_business', 'load_balance') DEFAULT 'whatsapp' AFTER is_active"],
        ['users', 'timezone', "ALTER TABLE users ADD COLUMN timezone VARCHAR(50) DEFAULT 'Africa/Nairobi' AFTER whatsapp_type"],
        ['messages', 'device_id', "ALTER TABLE messages ADD COLUMN device_id VARCHAR(64) NULL AFTER api_key_id"],
        ['devices', 'svc_accessibility', "ALTER TABLE devices ADD COLUMN svc_accessibility TINYINT(1) DEFAULT 0"],
        ['devices', 'svc_notification', "ALTER TABLE devices ADD COLUMN svc_notification TINYINT(1) DEFAULT 0"],
        ['devices', 'svc_battery', "ALTER TABLE devices ADD COLUMN svc_battery TINYINT(1) DEFAULT 0"],
    ];

    foreach ($columns as [$table, $column, $sql]) {
        try {
            if (!columnExists($db, $table, $column)) {
                $db->exec($sql);
            }
        } catch (PDOException $e) {
            // Ignore - column might already exist
        }
    }

    // MODIFY columns (always safe to run)
    $modifies = [
        "ALTER TABLE users MODIFY COLUMN whatsapp_type ENUM('whatsapp', 'whatsapp_business', 'load_balance') DEFAULT 'whatsapp'",
        "ALTER TABLE messages MODIFY COLUMN status ENUM('pending', 'sent', 'delivered', 'failed', 'expired') DEFAULT 'pending'",
    ];

    foreach ($modifies as $sql) {
        try { $db->exec($sql); } catch (PDOException $e) { /* ignore */ }
    }

    // INDEX migrations — speed up dashboard queries
    // MySQL doesn't support CREATE INDEX IF NOT EXISTS, so check first
    $indexExists = function($db, $table, $indexName) {
        $stmt = $db->prepare("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?");
        $stmt->execute([$table, $indexName]);
        return $stmt->fetchColumn() > 0;
    };

    $indexes = [
        // [table, index_name, CREATE statement]
        // For: SELECT * FROM messages WHERE user_id = ? ORDER BY created_at DESC LIMIT 50
        ['messages', 'idx_user_created', "CREATE INDEX idx_user_created ON messages (user_id, created_at)"],
        // For: SELECT COUNT(*) FROM messages WHERE device_id = ? AND status = ? (devices.php runs this 3x per device)
        ['messages', 'idx_device_status', "CREATE INDEX idx_device_status ON messages (device_id, status)"],
        // For: chart queries WHERE created_at >= ? AND user_id = ? GROUP BY status
        ['messages', 'idx_user_status_created', "CREATE INDEX idx_user_status_created ON messages (user_id, status, created_at)"],
        // For: SELECT * FROM messages WHERE device_id = ? ORDER BY id DESC LIMIT 1 (last_msg_status)
        ['messages', 'idx_device_id_only', "CREATE INDEX idx_device_id_only ON messages (device_id, id)"],
        // For: api_keys lookups WHERE user_id = ? AND is_active = 1
        ['api_keys', 'idx_user_active', "CREATE INDEX idx_user_active ON api_keys (user_id, is_active)"],
        // For: SELECT MAX(sent_at) FROM messages WHERE device_id = ? AND status = "delivered"
        ['messages', 'idx_device_status_sent', "CREATE INDEX idx_device_status_sent ON messages (device_id, status, sent_at)"],
        // For: devices listing ORDER BY last_seen DESC and active filter
        ['devices', 'idx_user_lastseen', "CREATE INDEX idx_user_lastseen ON devices (user_id, last_seen)"],
        ['devices', 'idx_active_lastseen', "CREATE INDEX idx_active_lastseen ON devices (is_active, last_seen)"],
    ];

    foreach ($indexes as [$table, $idxName, $sql]) {
        try {
            if (!$indexExists($db, $table, $idxName)) {
                $db->exec($sql);
            }
        } catch (PDOException $e) {
            // Ignore — index may exist or table missing
        }
    }
}

// Auto-run migrations
runMigrations();
