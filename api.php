<?php
/**
 * WhatsApp Relay Server API
 *
 * Endpoints:
 *   POST   /send         - Queue a new message
 *   POST   /send-bulk    - Queue multiple messages
 *   GET    /pending       - Get pending messages (APK polls this)
 *   POST   /status        - Update message status (APK reports back)
 *   GET    /messages      - List all messages with filters
 *   GET    /health        - Server health check
 */

require_once __DIR__ . '/config.php';
require_once __DIR__ . '/database.php';

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: ' . ALLOWED_ORIGIN);
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, X-API-Key');

// Handle preflight
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// JSON response helper
function respond($code, $data) {
    http_response_code($code);
    echo json_encode($data, JSON_PRETTY_PRINT);
    exit;
}

// Authenticate request via API key in database
// Returns ['user_id' => int, 'key_id' => int]
function authenticate() {
    $apiKey = $_SERVER['HTTP_X_API_KEY'] ?? ($_GET['api_key'] ?? ($_GET['apikey'] ?? null));
    if (!$apiKey) {
        respond(401, ['error' => 'Missing API key. Include X-API-Key header.']);
    }

    $db = getDB();
    $stmt = $db->prepare(
        'SELECT ak.id as key_id, ak.user_id, u.is_active as user_active
         FROM api_keys ak
         JOIN users u ON u.id = ak.user_id
         WHERE ak.api_key = ? AND ak.is_active = 1'
    );
    $stmt->execute([$apiKey]);
    $result = $stmt->fetch();

    if (!$result) {
        respond(401, ['error' => 'Invalid or disabled API key']);
    }

    if (!$result['user_active']) {
        respond(403, ['error' => 'User account is disabled']);
    }

    // Update last used timestamp and request count
    $db->prepare('UPDATE api_keys SET last_used_at = NOW(), request_count = request_count + 1 WHERE id = ?')
       ->execute([$result['key_id']]);

    return ['user_id' => (int)$result['user_id'], 'key_id' => (int)$result['key_id']];
}

// Check user subscription (placeholder for future billing)
// Returns ['active' => bool, 'plan' => string, 'remaining' => int]
function checkSubscription($userId) {
    $db = getDB();
    $stmt = $db->prepare(
        'SELECT * FROM subscriptions WHERE user_id = ? AND is_active = 1 ORDER BY id DESC LIMIT 1'
    );
    $stmt->execute([$userId]);
    $sub = $stmt->fetch();

    if (!$sub) {
        // No subscription record = unlimited (for now)
        // TODO: Change this to enforce subscription when billing is live
        return ['active' => true, 'plan' => 'unlimited', 'remaining' => -1];
    }

    // Check expiry
    if ($sub['expires_at'] && strtotime($sub['expires_at']) < time()) {
        return ['active' => false, 'plan' => $sub['plan_name'], 'remaining' => 0, 'reason' => 'Subscription expired'];
    }

    // Check message limit
    if ($sub['messages_limit'] > 0 && $sub['messages_used'] >= $sub['messages_limit']) {
        return ['active' => false, 'plan' => $sub['plan_name'], 'remaining' => 0, 'reason' => 'Monthly message limit reached'];
    }

    $remaining = $sub['messages_limit'] > 0 ? ($sub['messages_limit'] - $sub['messages_used']) : -1;
    return ['active' => true, 'plan' => $sub['plan_name'], 'remaining' => $remaining];
}

// Increment subscription usage counter
function incrementUsage($userId) {
    $db = getDB();
    $db->prepare(
        'UPDATE subscriptions SET messages_used = messages_used + 1 WHERE user_id = ? AND is_active = 1'
    )->execute([$userId]);
}

// Pick the next active device for round-robin assignment
// $waType: the whatsapp_type of the message being queued
// Returns device_id or null if no active devices
function assignDevice($userId, $waType = null) {
    $db = getDB();
    // Get active devices that were seen in the last 5 minutes
    $stmt = $db->prepare(
        'SELECT device_id, whatsapp_type FROM devices
         WHERE user_id = ? AND is_active = 1 AND last_seen > DATE_SUB(NOW(), INTERVAL 5 MINUTE)
         ORDER BY last_seen ASC'
    );
    $stmt->execute([$userId]);
    $allDevices = $stmt->fetchAll();

    if (empty($allDevices)) return null;

    // Filter devices that support the message's whatsapp_type
    $compatibleDevices = [];
    foreach ($allDevices as $dev) {
        $devType = $dev['whatsapp_type'] ?? 'both';
        if ($devType === 'both' || $devType === $waType || $waType === null) {
            $compatibleDevices[] = $dev['device_id'];
        }
    }

    // Fallback to all devices if none match the type
    if (empty($compatibleDevices)) {
        $compatibleDevices = array_column($allDevices, 'device_id');
    }

    if (count($compatibleDevices) === 1) return $compatibleDevices[0];

    // Round-robin: pick the device with the fewest recent pending/sent messages
    $placeholders = implode(',', array_fill(0, count($compatibleDevices), '?'));
    $stmt = $db->prepare(
        "SELECT d.device_id, COUNT(m.id) as msg_count
         FROM devices d
         LEFT JOIN messages m ON m.device_id = d.device_id AND m.status IN ('pending', 'sent') AND m.user_id = ?
         WHERE d.device_id IN ($placeholders)
         GROUP BY d.device_id
         ORDER BY msg_count ASC, d.last_seen DESC
         LIMIT 1"
    );
    $stmt->execute(array_merge([$userId], $compatibleDevices));
    $result = $stmt->fetch();
    return $result ? $result['device_id'] : $compatibleDevices[0];
}

// Log message action
function logAction($messageId, $action, $details = null) {
    $db = getDB();
    $stmt = $db->prepare('INSERT INTO message_log (message_id, action, details) VALUES (?, ?, ?)');
    $stmt->execute([$messageId, $action, $details]);
}

// Parse request path
// Support both /api.php/send and ?action=send styles
$requestUri = $_SERVER['REQUEST_URI'];
$path = parse_url($requestUri, PHP_URL_PATH);
$path = rtrim($path, '/');

// Remove script name from path (e.g. /api.php/send -> /send)
$scriptName = $_SERVER['SCRIPT_NAME'];
if (strpos($path, $scriptName) === 0) {
    $path = substr($path, strlen($scriptName));
}

// Also support ?action= query parameter as fallback
if (empty($path) || $path === '/' || $path === false) {
    $action = $_GET['action'] ?? '';
    $path = '/' . ltrim($action, '/');
}

$path = '/' . ltrim($path, '/');

$method = $_SERVER['REQUEST_METHOD'];

// =============================================
// GET /health - Server health check (no auth)
// =============================================
if ($path === '/health' && $method === 'GET') {
    try {
        $db = getDB();
        $db->query('SELECT 1');
        respond(200, [
            'status' => 'ok',
            'server_time' => date('Y-m-d H:i:s'),
            'version' => APP_VERSION
        ]);
    } catch (Exception $e) {
        respond(500, ['status' => 'error', 'message' => 'Database unavailable']);
    }
}

// =============================================
// GET /send - Simple URL-based send (for billing systems)
// Usage: api.php?action=send&to=254712345678&msg=Hello&apikey=YOURKEY
// =============================================
if ($method === 'GET' && ($path === '/send' || isset($_GET['to']))) {
    $phone = $_GET['to'] ?? null;
    $message = $_GET['msg'] ?? null;

    if (!$phone || !$message) {
        respond(400, ['error' => 'to and msg parameters are required', 'usage' => 'api.php?to=NUMBER&msg=TEXT&apikey=YOURKEY']);
    }

    $auth = authenticate();
    $phone = preg_replace('/[^0-9]/', '', $phone);

    // Use the type param if given, otherwise fall back to the user's default preference
    $whatsappType = $_GET['type'] ?? null;
    if (!$whatsappType) {
        $db = getDB();
        $waStmt = $db->prepare('SELECT whatsapp_type FROM users WHERE id = ?');
        $waStmt->execute([$auth['user_id']]);
        $whatsappType = $waStmt->fetchColumn() ?: 'whatsapp';
    }

    // Load balance: alternate between whatsapp and whatsapp_business
    if ($whatsappType === 'load_balance') {
        $db = getDB();
        $lastStmt = $db->prepare('SELECT whatsapp_type FROM messages WHERE user_id = ? ORDER BY id DESC LIMIT 1');
        $lastStmt->execute([$auth['user_id']]);
        $lastType = $lastStmt->fetchColumn();
        $whatsappType = ($lastType === 'whatsapp') ? 'whatsapp_business' : 'whatsapp';
    }

    if (!in_array($whatsappType, ['whatsapp', 'whatsapp_business'])) {
        $whatsappType = 'whatsapp';
    }

    // Check subscription
    $subscription = checkSubscription($auth['user_id']);
    if (!$subscription['active']) {
        respond(403, ['error' => $subscription['reason'] ?? 'Subscription inactive', 'plan' => $subscription['plan']]);
    }

    $db = getDB();
    $assignedDevice = null;
    try { $assignedDevice = assignDevice($auth['user_id'], $whatsappType); } catch (Exception $e) {}

    try {
        // Try with device_id column
        $stmt = $db->prepare(
            'INSERT INTO messages (user_id, api_key_id, device_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?, ?, 0)'
        );
        $stmt->execute([$auth['user_id'], $auth['key_id'], $assignedDevice, $phone, $message, $whatsappType]);
    } catch (Exception $e) {
        // Fallback: insert without device_id if column doesn't exist
        $stmt = $db->prepare(
            'INSERT INTO messages (user_id, api_key_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?, 0)'
        );
        $stmt->execute([$auth['user_id'], $auth['key_id'], $phone, $message, $whatsappType]);
    }
    $messageId = $db->lastInsertId();

    incrementUsage($auth['user_id']);
    $deviceLabel = $assignedDevice ? substr($assignedDevice, 0, 8) : 'any';
    logAction($messageId, 'created', "Queued via GET API for $whatsappType to $phone (device: $deviceLabel)");

    respond(201, [
        'success' => true,
        'message_id' => (int)$messageId,
        'status' => 'pending'
    ]);
}

// All other routes require auth
$auth = authenticate();
$authUserId = $auth['user_id'];
$authKeyId = $auth['key_id'];

// =============================================
// POST /send - Queue a new message (JSON body)
// =============================================
if ($path === '/send' && $method === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);

    if (!$input) {
        respond(400, ['error' => 'Invalid JSON body']);
    }

    $phone = $input['phone'] ?? null;
    $message = $input['message'] ?? null;
    $whatsappType = $input['whatsapp_type'] ?? 'whatsapp';
    $priority = intval($input['priority'] ?? 0);

    if (!$phone || !$message) {
        respond(400, ['error' => 'phone and message are required']);
    }

    $phone = preg_replace('/[^0-9]/', '', $phone);

    // Load balance: alternate between whatsapp and whatsapp_business
    if ($whatsappType === 'load_balance') {
        $db = getDB();
        $lastStmt = $db->prepare('SELECT whatsapp_type FROM messages WHERE user_id = ? ORDER BY id DESC LIMIT 1');
        $lastStmt->execute([$authUserId]);
        $lastType = $lastStmt->fetchColumn();
        $whatsappType = ($lastType === 'whatsapp') ? 'whatsapp_business' : 'whatsapp';
    }

    if (!in_array($whatsappType, ['whatsapp', 'whatsapp_business'])) {
        $whatsappType = 'whatsapp';
    }

    // Check subscription
    $subscription = checkSubscription($authUserId);
    if (!$subscription['active']) {
        respond(403, ['error' => $subscription['reason'] ?? 'Subscription inactive', 'plan' => $subscription['plan']]);
    }

    $db = getDB();
    $assignedDevice = null;
    try { $assignedDevice = assignDevice($authUserId, $whatsappType); } catch (Exception $e) {}

    try {
        $stmt = $db->prepare(
            'INSERT INTO messages (user_id, api_key_id, device_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?, ?, ?)'
        );
        $stmt->execute([$authUserId, $authKeyId, $assignedDevice, $phone, $message, $whatsappType, $priority]);
    } catch (Exception $e) {
        $stmt = $db->prepare(
            'INSERT INTO messages (user_id, api_key_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?, ?)'
        );
        $stmt->execute([$authUserId, $authKeyId, $phone, $message, $whatsappType, $priority]);
    }
    $messageId = $db->lastInsertId();

    incrementUsage($authUserId);
    $deviceLabel = $assignedDevice ? substr($assignedDevice, 0, 8) : 'any';
    logAction($messageId, 'created', "Queued for $whatsappType to $phone (device: $deviceLabel)");

    respond(201, [
        'success' => true,
        'message_id' => (int)$messageId,
        'status' => 'pending',
        'info' => "Message queued for delivery via $whatsappType"
    ]);
}

// =============================================
// POST /send-bulk - Queue multiple messages
// =============================================
if ($path === '/send-bulk' && $method === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);

    if (!$input || !isset($input['messages']) || !is_array($input['messages'])) {
        respond(400, ['error' => 'messages array is required']);
    }

    $db = getDB();
    $results = [];

    $db->beginTransaction();
    try {
        $stmt = $db->prepare(
            'INSERT INTO messages (user_id, api_key_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?, ?)'
        );

        foreach ($input['messages'] as $msg) {
            $phone = preg_replace('/[^0-9]/', '', $msg['phone'] ?? '');
            $message = $msg['message'] ?? '';
            $whatsappType = $msg['whatsapp_type'] ?? 'whatsapp';
            $priority = intval($msg['priority'] ?? 0);

            if (!$phone || !$message) {
                $results[] = ['error' => 'phone and message required', 'phone' => $msg['phone'] ?? ''];
                continue;
            }

            if (!in_array($whatsappType, ['whatsapp', 'whatsapp_business'])) {
                $whatsappType = 'whatsapp';
            }

            $stmt->execute([$authUserId, $authKeyId, $phone, $message, $whatsappType, $priority]);
            $id = $db->lastInsertId();
            logAction($id, 'created', "Bulk queued for $whatsappType to $phone");
            $results[] = ['message_id' => (int)$id, 'phone' => $phone, 'status' => 'pending'];
        }
        $db->commit();
    } catch (Exception $e) {
        $db->rollBack();
        respond(500, ['error' => 'Bulk insert failed']);
    }

    respond(201, ['success' => true, 'results' => $results]);
}

// =============================================
// GET /pending - Get pending messages (APK polls this)
// =============================================
if ($path === '/pending' && $method === 'GET') {
    $limit = min(intval($_GET['limit'] ?? 10), 50);
    $deviceId = $_GET['device_id'] ?? null;
    $deviceName = $_GET['device_name'] ?? 'Unknown';

    $db = getDB();

    // Auto-register/update device if device_id is provided
    if ($deviceId) {
        $svcAccessibility = intval($_GET['svc_accessibility'] ?? 0);
        $svcNotification = intval($_GET['svc_notification'] ?? 0);
        $svcBattery = intval($_GET['svc_battery'] ?? 0);

        $devCheck = $db->prepare('SELECT id FROM devices WHERE device_id = ? AND user_id = ?');
        $devCheck->execute([$deviceId, $authUserId]);
        if ($devCheck->fetch()) {
            // Don't overwrite device_name — user may have renamed it manually
            $db->prepare('UPDATE devices SET last_seen = NOW(), svc_accessibility = ?, svc_notification = ?, svc_battery = ? WHERE device_id = ? AND user_id = ?')
               ->execute([$svcAccessibility, $svcNotification, $svcBattery, $deviceId, $authUserId]);
        } else {
            $db->prepare('INSERT INTO devices (user_id, device_id, device_name, svc_accessibility, svc_notification, svc_battery, last_seen) VALUES (?, ?, ?, ?, ?, ?, NOW())')
               ->execute([$authUserId, $deviceId, $deviceName, $svcAccessibility, $svcNotification, $svcBattery]);
        }
    }

    // Auto-expire messages pending for more than 5 minutes to avoid pile-up
    $expired = $db->prepare(
        'UPDATE messages SET status = "expired", error_message = "Expired - phone was unreachable for too long"
         WHERE status = "pending" AND user_id = ? AND created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)'
    );
    $expired->execute([$authUserId]);
    $expiredCount = $expired->rowCount();
    if ($expiredCount > 0) {
        error_log("Auto-expired $expiredCount messages for user $authUserId");
    }

    // Reassign messages from offline devices (no poll in 2 minutes) back to pending pool
    if ($deviceId) {
        $db->prepare(
            'UPDATE messages SET device_id = NULL
             WHERE status = "pending" AND user_id = ? AND device_id IS NOT NULL AND device_id != ?
             AND device_id IN (SELECT device_id FROM devices WHERE user_id = ? AND last_seen < DATE_SUB(NOW(), INTERVAL 2 MINUTE))'
        )->execute([$authUserId, $deviceId, $authUserId]);

        // Reassign stuck "sent" messages (dispatched but not delivered in 3 minutes) back to pending
        $reassigned = $db->prepare(
            'UPDATE messages SET status = "pending", device_id = NULL
             WHERE status = "sent" AND user_id = ? AND device_id != ?
             AND created_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)'
        );
        $reassigned->execute([$authUserId, $deviceId]);
        if ($reassigned->rowCount() > 0) {
            error_log("Reassigned " . $reassigned->rowCount() . " stuck messages from other devices for user $authUserId");
        }
    }

    // Fetch pending messages: either assigned to this device or unassigned
    if ($deviceId) {
        $stmt = $db->prepare(
            'SELECT id, phone, message, whatsapp_type, priority, retry_count, created_at
             FROM messages
             WHERE status = "pending" AND retry_count < ? AND user_id = ?
             AND (device_id = ? OR device_id IS NULL)
             ORDER BY priority DESC, created_at ASC
             LIMIT ?'
        );
        $stmt->execute([MAX_RETRY_COUNT, $authUserId, $deviceId, $limit]);
    } else {
        $stmt = $db->prepare(
            'SELECT id, phone, message, whatsapp_type, priority, retry_count, created_at
             FROM messages
             WHERE status = "pending" AND retry_count < ? AND user_id = ?
             ORDER BY priority DESC, created_at ASC
             LIMIT ?'
        );
        $stmt->execute([MAX_RETRY_COUNT, $authUserId, $limit]);
    }
    $messages = $stmt->fetchAll();

    // Mark as sent (processing) and assign to this device
    if (!empty($messages)) {
        $ids = array_column($messages, 'id');
        $placeholders = implode(',', array_fill(0, count($ids), '?'));
        $db->prepare("UPDATE messages SET status = 'sent', device_id = ? WHERE id IN ($placeholders)")
           ->execute(array_merge([$deviceId], $ids));
        foreach ($ids as $id) {
            logAction($id, 'dispatched', "Sent to device " . ($deviceId ? substr($deviceId, 0, 8) : 'unknown'));
        }
    }

    respond(200, [
        'count' => count($messages),
        'messages' => $messages
    ]);
}

// =============================================
// POST /status - APK reports message status
// =============================================
if ($path === '/status' && $method === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);

    $messageId = $input['message_id'] ?? null;
    $status = $input['status'] ?? null;
    $errorMessage = $input['error_message'] ?? null;

    if (!$messageId || !$status) {
        respond(400, ['error' => 'message_id and status are required']);
    }

    if (!in_array($status, ['delivered', 'failed'])) {
        respond(400, ['error' => 'status must be delivered or failed']);
    }

    $db = getDB();

    // Verify the message belongs to this user
    $check = $db->prepare('SELECT id, retry_count FROM messages WHERE id = ? AND user_id = ?');
    $check->execute([$messageId, $authUserId]);
    $msg = $check->fetch();

    if (!$msg) {
        respond(404, ['error' => 'Message not found']);
    }

    if ($status === 'delivered') {
        $stmt = $db->prepare(
            'UPDATE messages SET status = "delivered", sent_at = NOW() WHERE id = ?'
        );
        $stmt->execute([$messageId]);
        logAction($messageId, 'delivered', 'Message sent via WhatsApp');
    } else {
        // Failed — auto-retry ONCE on a different device if retry_count is 0
        if (($msg['retry_count'] ?? 0) == 0) {
            // Get the current device that failed
            $failedDevice = $db->prepare('SELECT device_id FROM messages WHERE id = ?');
            $failedDevice->execute([$messageId]);
            $failedDeviceId = $failedDevice->fetchColumn();

            // Find another active device
            $otherDevice = $db->prepare(
                'SELECT device_id FROM devices WHERE user_id = ? AND is_active = 1 AND device_id != ? ORDER BY last_seen DESC LIMIT 1'
            );
            $otherDevice->execute([$authUserId, $failedDeviceId ?: '']);
            $newDeviceId = $otherDevice->fetchColumn();

            if ($newDeviceId) {
                // Retry once on another device
                $db->prepare('UPDATE messages SET status = "pending", device_id = ?, retry_count = 1, error_message = NULL WHERE id = ?')
                   ->execute([$newDeviceId, $messageId]);
                logAction($messageId, 'auto-retry', "Failed on device " . substr($failedDeviceId ?? '', 0, 8) . ", retrying on " . substr($newDeviceId, 0, 8));
            } else {
                // No other device available — mark as failed
                $db->prepare('UPDATE messages SET status = "failed", error_message = ?, retry_count = 1 WHERE id = ?')
                   ->execute([$errorMessage, $messageId]);
                logAction($messageId, 'failed', "Failed: $errorMessage (no other device available)");
            }
        } else {
            // Already retried once — mark as final failed
            $db->prepare('UPDATE messages SET status = "failed", error_message = ? WHERE id = ?')
               ->execute([$errorMessage, $messageId]);
            logAction($messageId, 'failed', "Failed after retry: $errorMessage");
        }
    }

    respond(200, ['success' => true]);
}

// =============================================
// GET /messages - List user's messages
// =============================================
if ($path === '/messages' && $method === 'GET') {
    $status = $_GET['status'] ?? null;
    $phone = $_GET['phone'] ?? null;
    $limit = min(intval($_GET['limit'] ?? 50), 200);
    $offset = intval($_GET['offset'] ?? 0);

    $where = ['user_id = ?'];
    $params = [$authUserId];

    if ($status) {
        $where[] = 'status = ?';
        $params[] = $status;
    }
    if ($phone) {
        $where[] = 'phone = ?';
        $params[] = preg_replace('/[^0-9]/', '', $phone);
    }

    $whereClause = 'WHERE ' . implode(' AND ', $where);

    $db = getDB();

    $countStmt = $db->prepare("SELECT COUNT(*) as total FROM messages $whereClause");
    $countStmt->execute($params);
    $total = $countStmt->fetch()['total'];

    $params[] = $limit;
    $params[] = $offset;
    $stmt = $db->prepare(
        "SELECT id, phone, message, whatsapp_type, status, retry_count, priority, created_at, updated_at, sent_at, error_message
         FROM messages $whereClause ORDER BY created_at DESC LIMIT ? OFFSET ?"
    );
    $stmt->execute($params);
    $messages = $stmt->fetchAll();

    respond(200, [
        'total' => (int)$total,
        'limit' => $limit,
        'offset' => $offset,
        'messages' => $messages
    ]);
}

// No route matched
respond(404, ['error' => 'Endpoint not found. Available: /send, /send-bulk, /pending, /status, /messages, /health']);
