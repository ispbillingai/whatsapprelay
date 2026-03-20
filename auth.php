<?php
/**
 * Authentication helpers
 */
require_once __DIR__ . '/database.php';

session_start();

function isLoggedIn() {
    return isset($_SESSION['user_id']);
}

function requireLogin() {
    if (!isLoggedIn()) {
        header('Location: login.php');
        exit;
    }
    // Force password change if flagged
    if (!empty($_SESSION['must_change_password']) && basename($_SERVER['SCRIPT_NAME']) !== 'change-password.php') {
        header('Location: change-password.php');
        exit;
    }
}

function getCurrentUser() {
    if (!isLoggedIn()) return null;
    $db = getDB();
    $stmt = $db->prepare('SELECT id, name, email, role, created_at, last_login, IFNULL(timezone, "Africa/Nairobi") as timezone FROM users WHERE id = ? AND is_active = 1');
    $stmt->execute([$_SESSION['user_id']]);
    $user = $stmt->fetch();
    // Apply user's timezone globally
    if ($user && !empty($user['timezone'])) {
        date_default_timezone_set($user['timezone']);
    }
    return $user;
}

if (!function_exists('fmtTime')) {
    /**
     * Format a datetime from the database for display.
     * MySQL NOW() stores in server timezone, just format it directly.
     */
    function fmtTime($dbDatetime, $format = 'M d H:i') {
        if (empty($dbDatetime)) return '-';
        try {
            return date($format, strtotime($dbDatetime));
        } catch (Exception $e) {
            return $dbDatetime;
        }
    }
}

function getUserTimezone() {
    if (!isLoggedIn()) return 'Africa/Nairobi';
    $db = getDB();
    $stmt = $db->prepare('SELECT IFNULL(timezone, "Africa/Nairobi") as timezone FROM users WHERE id = ?');
    $stmt->execute([$_SESSION['user_id']]);
    $tz = $stmt->fetchColumn();
    return $tz ?: 'Africa/Nairobi';
}

function login($email, $password) {
    $db = getDB();
    $stmt = $db->prepare('SELECT *, IFNULL(must_change_password, 0) as must_change_password FROM users WHERE email = ? AND is_active = 1');
    $stmt->execute([$email]);
    $user = $stmt->fetch();

    if ($user && password_verify($password, $user['password'])) {
        $_SESSION['user_id'] = $user['id'];
        $_SESSION['user_name'] = $user['name'];
        $_SESSION['user_role'] = $user['role'];
        $_SESSION['must_change_password'] = !empty($user['must_change_password']);
        $_SESSION['is_first_login'] = empty($user['last_login']);

        // Update last login
        $db->prepare('UPDATE users SET last_login = NOW() WHERE id = ?')->execute([$user['id']]);
        return true;
    }
    return false;
}

function logout() {
    session_destroy();
    header('Location: login.php');
    exit;
}

function isAdmin() {
    return isset($_SESSION['user_role']) && $_SESSION['user_role'] === 'admin';
}

function requireAdmin() {
    requireLogin();
    if (!isAdmin()) {
        header('Location: dashboard.php');
        exit;
    }
}

function generateApiKey() {
    return bin2hex(random_bytes(32));
}

function csrfToken() {
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

function verifyCsrf($token) {
    return isset($_SESSION['csrf_token']) && hash_equals($_SESSION['csrf_token'], $token);
}

function flash($message, $type = 'success') {
    $_SESSION['flash'] = ['message' => $message, 'type' => $type];
}

function getFlash() {
    if (isset($_SESSION['flash'])) {
        $flash = $_SESSION['flash'];
        unset($_SESSION['flash']);
        return $flash;
    }
    return null;
}
