<?php
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/layout.php';
requireLogin();

$db = getDB();
$userId = $_SESSION['user_id'];
$isAdminUser = isAdmin();

// Save WhatsApp type preference (AJAX)
if (isset($_GET['set_wa_type']) && in_array($_GET['set_wa_type'], ['whatsapp', 'whatsapp_business'])) {
    $db->prepare('UPDATE users SET whatsapp_type = ? WHERE id = ?')->execute([$_GET['set_wa_type'], $userId]);
    exit;
}

// Handle retry failed/expired messages (AJAX)
if (isset($_POST['retry_messages'])) {
    header('Content-Type: application/json');
    $retryType = $_POST['retry_type'] ?? 'all'; // 'all', 'failed', 'expired', or a specific message ID

    if (is_numeric($retryType)) {
        // Retry single message
        $stmt = $db->prepare('UPDATE messages SET status = "pending", error_message = NULL, retry_count = 0, created_at = NOW() WHERE id = ? AND user_id = ? AND status IN ("failed", "expired")');
        $stmt->execute([$retryType, $userId]);
        echo json_encode(['success' => true, 'retried' => $stmt->rowCount()]);
    } else {
        $statusFilter = '';
        if ($retryType === 'failed') $statusFilter = 'AND status = "failed"';
        elseif ($retryType === 'expired') $statusFilter = 'AND status = "expired"';
        else $statusFilter = 'AND status IN ("failed", "expired")';

        $stmt = $db->prepare("UPDATE messages SET status = 'pending', error_message = NULL, retry_count = 0, created_at = NOW() WHERE user_id = ? $statusFilter");
        $stmt->execute([$userId]);
        echo json_encode(['success' => true, 'retried' => $stmt->rowCount()]);
    }
    exit;
}

// Admin view toggle: ?view=admin or ?view=personal
$adminView = false;
if ($isAdminUser) {
    $view = $_GET['view'] ?? ($_SESSION['dashboard_view'] ?? 'admin');
    if ($view === 'admin' || $view === 'personal') {
        $_SESSION['dashboard_view'] = $view;
    }
    $adminView = ($_SESSION['dashboard_view'] ?? 'admin') === 'admin';
}

// Get user's WhatsApp type preference
$stmt = $db->prepare('SELECT whatsapp_type FROM users WHERE id = ?');
$stmt->execute([$userId]);
$userWaType = $stmt->fetchColumn() ?: 'whatsapp';

// Stats queries - admin overview sees all, personal view sees own
$showAll = $isAdminUser && $adminView;
$whereUser = $showAll ? '' : 'WHERE user_id = ?';
$params = $showAll ? [] : [$userId];

// Total messages
$stmt = $db->prepare("SELECT COUNT(*) as total FROM messages $whereUser");
$stmt->execute($params);
$totalMessages = $stmt->fetch()['total'];

// By status (including expired)
$statuses = ['pending', 'sent', 'delivered', 'failed', 'expired'];
$statusCounts = [];
foreach ($statuses as $s) {
    $where = $showAll ? "WHERE status = ?" : "WHERE status = ? AND user_id = ?";
    $p = $showAll ? [$s] : [$s, $userId];
    $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM messages $where");
    $stmt->execute($p);
    $statusCounts[$s] = $stmt->fetch()['cnt'];
}

// Today's messages
$where = $showAll
    ? "WHERE DATE(created_at) = CURDATE()"
    : "WHERE DATE(created_at) = CURDATE() AND user_id = ?";
$p = $showAll ? [] : [$userId];
$stmt = $db->prepare("SELECT COUNT(*) as cnt FROM messages $where");
$stmt->execute($p);
$todayCount = $stmt->fetch()['cnt'];

// Success rate
$totalAttempted = $statusCounts['delivered'] + $statusCounts['failed'];
$successRate = $totalAttempted > 0 ? round(($statusCounts['delivered'] / $totalAttempted) * 100, 1) : 0;

// Admin-only stats
$totalUsers = 0;
$activeUsers = 0;
if ($isAdminUser && $adminView) {
    $stmt = $db->query('SELECT COUNT(*) as cnt FROM users');
    $totalUsers = $stmt->fetch()['cnt'];
    $stmt = $db->query('SELECT COUNT(*) as cnt FROM users WHERE is_active = 1');
    $activeUsers = $stmt->fetch()['cnt'];
}

// Recent messages
$where = $showAll ? '' : 'WHERE m.user_id = ?';
$stmt = $db->prepare("SELECT m.*, u.name as user_name FROM messages m LEFT JOIN users u ON m.user_id = u.id $where ORDER BY m.created_at DESC LIMIT 10");
$stmt->execute($params);
$recentMessages = $stmt->fetchAll();

// Active API keys count
$where = $showAll ? "WHERE is_active = 1" : "WHERE is_active = 1 AND user_id = ?";
$stmt = $db->prepare("SELECT COUNT(*) as cnt FROM api_keys $where");
$stmt->execute($params);
$activeKeys = $stmt->fetch()['cnt'];

// Get or auto-create the user's API key (one key per account)
$stmt = $db->prepare('SELECT api_key FROM api_keys WHERE user_id = ? AND is_active = 1 LIMIT 1');
$stmt->execute([$userId]);
$userKey = $stmt->fetchColumn();

if (!$userKey) {
    $userKey = bin2hex(random_bytes(24));
    $keyName = 'Default Key';
    $db->prepare('INSERT INTO api_keys (user_id, api_key, label, is_active) VALUES (?, ?, ?, 1)')
       ->execute([$userId, $userKey, $keyName]);
    $activeKeys = 1;
}

// Build the server base URL for API examples
$serverBase = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http')
    . '://' . $_SERVER['HTTP_HOST'];

renderHeader('Dashboard', 'dashboard');
?>

<?php if ($isAdminUser): ?>
<!-- Admin View Toggle -->
<div class="d-flex justify-content-between align-items-center mb-4">
    <div>
        <?php if ($adminView): ?>
            <h5 class="mb-0"><i class="bi bi-speedometer2 text-primary"></i> Admin Overview</h5>
            <small class="text-muted">Viewing all users' data</small>
        <?php else: ?>
            <h5 class="mb-0"><i class="bi bi-person-circle text-success"></i> My Dashboard</h5>
            <small class="text-muted">Viewing your personal data</small>
        <?php endif; ?>
    </div>
    <div class="btn-group">
        <a href="?view=admin" class="btn btn-sm <?= $adminView ? 'btn-primary' : 'btn-outline-primary' ?>">
            <i class="bi bi-speedometer2"></i> Admin Overview
        </a>
        <a href="?view=personal" class="btn btn-sm <?= !$adminView ? 'btn-success' : 'btn-outline-success' ?>">
            <i class="bi bi-person"></i> My Dashboard
        </a>
    </div>
</div>
<?php endif; ?>

<?php if (!$isAdminUser || !$adminView): ?>
<!-- API Integration Box (personal view only) -->
<div class="card mb-4 border-0" style="background: linear-gradient(135deg, #075E54, #128C7E); border-radius: 16px;">
    <div class="card-body p-4 text-white">
        <div class="d-flex justify-content-between align-items-start mb-3">
            <div>
                <h5 class="mb-1"><i class="bi bi-lightning-charge-fill"></i> Quick API Integration</h5>
                <p class="mb-0 small opacity-75">Copy this URL into your billing system to send WhatsApp messages</p>
            </div>
            <span class="badge bg-light text-dark"><i class="bi bi-check-circle-fill text-success"></i> Active</span>
        </div>

        <div class="bg-dark bg-opacity-50 rounded-3 p-3 mb-3" style="font-family: monospace; font-size: 13px; word-break: break-all;">
            <span class="text-warning"><?= htmlspecialchars($serverBase) ?>/api.php?to=</span><span class="text-info">[number]</span><span class="text-warning">&msg=</span><span class="text-info">[text]</span><span class="text-warning">&apikey=</span><span class="text-success"><?= htmlspecialchars($userKey) ?></span>
        </div>

        <div class="row g-2 mb-3">
            <div class="col-md-8">
                <div class="input-group input-group-sm">
                    <span class="input-group-text bg-dark border-0 text-muted" style="font-size:11px;">Your API Key</span>
                    <input type="text" class="form-control bg-dark border-0 text-white" id="apiKeyDisplay" value="<?= htmlspecialchars($userKey) ?>" readonly style="font-family:monospace; font-size:13px;">
                    <button class="btn btn-light btn-sm" onclick="copyKey()" title="Copy API Key">
                        <i class="bi bi-clipboard" id="copyIcon"></i>
                    </button>
                </div>
            </div>
            <div class="col-md-4 text-end">
                <a href="api-keys.php" class="btn btn-outline-light btn-sm"><i class="bi bi-gear"></i> Manage Keys</a>
            </div>
        </div>

        <!-- WhatsApp type selector -->
        <div class="bg-dark bg-opacity-25 rounded-3 p-3 mb-3">
            <div class="d-flex align-items-center gap-3">
                <span class="small opacity-75">Default WhatsApp:</span>
                <div class="form-check form-check-inline mb-0">
                    <input class="form-check-input" type="radio" name="wa_type_dash" id="waPersonal" value="whatsapp" <?= ($userWaType ?? 'whatsapp') === 'whatsapp' ? 'checked' : '' ?> onchange="saveWaType(this.value)">
                    <label class="form-check-label small text-white" for="waPersonal"><i class="bi bi-whatsapp"></i> WhatsApp</label>
                </div>
                <div class="form-check form-check-inline mb-0">
                    <input class="form-check-input" type="radio" name="wa_type_dash" id="waBusiness" value="whatsapp_business" <?= ($userWaType ?? 'whatsapp') === 'whatsapp_business' ? 'checked' : '' ?> onchange="saveWaType(this.value)">
                    <label class="form-check-label small text-white" for="waBusiness"><i class="bi bi-briefcase"></i> WhatsApp Business</label>
                </div>
            </div>
        </div>

        <div class="small opacity-75">
            <strong>Example:</strong>
            <code class="text-light"><?= htmlspecialchars($serverBase) ?>/api.php?to=254712345678&msg=Hello+World&apikey=<?= htmlspecialchars($userKey) ?></code>
        </div>
    </div>
</div>

<!-- Important Notes -->
<div class="card mb-4 border-0">
    <div class="card-body">
        <h6 class="mb-3"><i class="bi bi-info-circle-fill text-primary"></i> Important Notes</h6>
        <div class="row g-3">
            <div class="col-md-6">
                <div class="d-flex gap-2">
                    <i class="bi bi-wifi text-warning mt-1"></i>
                    <div>
                        <strong class="small">Phone Must Be Online</strong>
                        <p class="text-muted small mb-0">Your phone needs an active internet connection at all times. Messages queue on the server and are delivered when the phone is reachable.</p>
                    </div>
                </div>
            </div>
            <div class="col-md-6">
                <div class="d-flex gap-2">
                    <i class="bi bi-phone text-info mt-1"></i>
                    <div>
                        <strong class="small">WhatsApp Opens Briefly</strong>
                        <p class="text-muted small mb-0">For new contacts, WhatsApp opens on your phone for ~1-2 seconds to send each message. We recommend using a dedicated phone.</p>
                    </div>
                </div>
            </div>
            <div class="col-md-6">
                <div class="d-flex gap-2">
                    <i class="bi bi-shield-check text-success mt-1"></i>
                    <div>
                        <strong class="small">Low Ban Risk</strong>
                        <p class="text-muted small mb-0">Messages are sent from your real WhatsApp account, so ban risk is low with moderate volumes.</p>
                    </div>
                </div>
            </div>
            <div class="col-md-6">
                <div class="d-flex gap-2">
                    <i class="bi bi-clock-history text-purple mt-1"></i>
                    <div>
                        <strong class="small">Auto-Expire After 10 Minutes</strong>
                        <p class="text-muted small mb-0">Messages pending for more than 10 minutes are automatically expired to prevent pile-up. You can retry them from below.</p>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>
<?php endif; ?>

<script>
function copyKey() {
    var input = document.getElementById('apiKeyDisplay');
    input.select();
    document.execCommand('copy');
    var icon = document.getElementById('copyIcon');
    icon.className = 'bi bi-check-lg text-success';
    setTimeout(function() { icon.className = 'bi bi-clipboard'; }, 2000);
}
function saveWaType(type) {
    fetch('dashboard.php?set_wa_type=' + type).then(function() {});
}
function retryMessages(type) {
    if (!confirm('Are you sure you want to retry ' + (type === 'all' ? 'all failed & expired' : type) + ' messages?')) return;
    var btn = event.target.closest('button');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Retrying...';

    fetch('dashboard.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'retry_messages=1&retry_type=' + type
    })
    .then(r => r.json())
    .then(data => {
        alert(data.retried + ' message(s) queued for retry.');
        location.reload();
    })
    .catch(() => { btn.disabled = false; btn.innerHTML = 'Retry'; });
}
</script>

<!-- Stats Cards -->
<div class="row g-4 mb-4">
    <?php if ($isAdminUser && $adminView): ?>
    <!-- Admin-only: Total Users -->
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-primary"><?= number_format($totalUsers) ?></div>
                        <div class="stat-label">Total Users</div>
                    </div>
                    <div class="stat-icon bg-primary bg-opacity-10 text-primary">
                        <i class="bi bi-people"></i>
                    </div>
                </div>
                <div class="small text-muted mt-1"><?= $activeUsers ?> active</div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value"><?= number_format($totalMessages) ?></div>
                        <div class="stat-label">Total Messages</div>
                    </div>
                    <div class="stat-icon bg-info bg-opacity-10 text-info">
                        <i class="bi bi-chat-dots"></i>
                    </div>
                </div>
                <div class="small text-muted mt-1"><?= $todayCount ?> today</div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-success"><?= number_format($statusCounts['delivered']) ?></div>
                        <div class="stat-label">Delivered</div>
                    </div>
                    <div class="stat-icon bg-success bg-opacity-10 text-success">
                        <i class="bi bi-check-circle"></i>
                    </div>
                </div>
                <div class="small text-muted mt-1"><?= $successRate ?>% success rate</div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-danger"><?= number_format($statusCounts['failed'] + $statusCounts['expired']) ?></div>
                        <div class="stat-label">Failed / Expired</div>
                    </div>
                    <div class="stat-icon bg-danger bg-opacity-10 text-danger">
                        <i class="bi bi-x-circle"></i>
                    </div>
                </div>
                <div class="small text-muted mt-1"><?= $statusCounts['failed'] ?> failed, <?= $statusCounts['expired'] ?> expired</div>
            </div>
        </div>
    </div>
    <?php else: ?>
    <!-- User view stats -->
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value"><?= number_format($totalMessages) ?></div>
                        <div class="stat-label">Total Messages</div>
                    </div>
                    <div class="stat-icon bg-primary bg-opacity-10 text-primary">
                        <i class="bi bi-chat-dots"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-success"><?= number_format($statusCounts['delivered']) ?></div>
                        <div class="stat-label">Delivered</div>
                    </div>
                    <div class="stat-icon bg-success bg-opacity-10 text-success">
                        <i class="bi bi-check-circle"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-warning"><?= number_format($statusCounts['pending']) ?></div>
                        <div class="stat-label">Pending</div>
                    </div>
                    <div class="stat-icon bg-warning bg-opacity-10 text-warning">
                        <i class="bi bi-clock"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-3">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value text-danger"><?= number_format($statusCounts['failed'] + $statusCounts['expired']) ?></div>
                        <div class="stat-label">Failed / Expired</div>
                    </div>
                    <div class="stat-icon bg-danger bg-opacity-10 text-danger">
                        <i class="bi bi-x-circle"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <?php endif; ?>
</div>

<!-- Second row stats -->
<div class="row g-4 mb-4">
    <div class="col-sm-6 col-xl-4">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value"><?= $todayCount ?></div>
                        <div class="stat-label">Messages Today</div>
                    </div>
                    <div class="stat-icon bg-info bg-opacity-10 text-info">
                        <i class="bi bi-calendar-day"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-4">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value"><?= $successRate ?>%</div>
                        <div class="stat-label">Success Rate</div>
                    </div>
                    <div class="stat-icon bg-success bg-opacity-10 text-success">
                        <i class="bi bi-graph-up"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <div class="col-sm-6 col-xl-4">
        <div class="card stat-card">
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start">
                    <div>
                        <div class="stat-value"><?= $activeKeys ?></div>
                        <div class="stat-label">Active API Keys</div>
                    </div>
                    <div class="stat-icon bg-secondary bg-opacity-10 text-secondary">
                        <i class="bi bi-key"></i>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>

<?php
// Retry section - show if there are failed or expired messages (personal view only, or admin personal)
$retryableCount = 0;
if (!$showAll) {
    $stmt = $db->prepare('SELECT COUNT(*) as cnt FROM messages WHERE user_id = ? AND status IN ("failed", "expired")');
    $stmt->execute([$userId]);
    $retryableCount = $stmt->fetch()['cnt'];
}
?>

<?php if ($retryableCount > 0): ?>
<!-- Retry Failed/Expired Messages -->
<div class="card mb-4 border-warning">
    <div class="card-header bg-warning bg-opacity-10 d-flex justify-content-between align-items-center">
        <span><i class="bi bi-exclamation-triangle text-warning"></i> <strong><?= $retryableCount ?></strong> message(s) need attention</span>
    </div>
    <div class="card-body">
        <p class="small text-muted mb-3">These messages were not delivered because your phone was offline or unreachable. You can retry sending them now.</p>
        <div class="d-flex gap-2 flex-wrap">
            <?php if ($statusCounts['failed'] > 0): ?>
            <button class="btn btn-outline-danger btn-sm" onclick="retryMessages('failed')">
                <i class="bi bi-arrow-repeat"></i> Retry <?= $statusCounts['failed'] ?> Failed
            </button>
            <?php endif; ?>
            <?php if ($statusCounts['expired'] > 0): ?>
            <button class="btn btn-outline-secondary btn-sm" onclick="retryMessages('expired')">
                <i class="bi bi-arrow-repeat"></i> Retry <?= $statusCounts['expired'] ?> Expired
            </button>
            <?php endif; ?>
            <button class="btn btn-warning btn-sm" onclick="retryMessages('all')">
                <i class="bi bi-arrow-repeat"></i> Retry All (<?= $retryableCount ?>)
            </button>
        </div>
    </div>
</div>
<?php endif; ?>

<?php if ($isAdminUser && $adminView): ?>
<!-- Admin: Users Overview -->
<?php
$stmt = $db->query('SELECT u.id, u.name, u.email, u.role, u.is_active, u.last_login, u.created_at,
    (SELECT COUNT(*) FROM messages WHERE user_id = u.id) as total_messages,
    (SELECT COUNT(*) FROM messages WHERE user_id = u.id AND status = "delivered") as delivered,
    (SELECT COUNT(*) FROM messages WHERE user_id = u.id AND status = "pending") as pending_msgs
    FROM users u ORDER BY u.created_at DESC LIMIT 10');
$usersList = $stmt->fetchAll();
?>
<div class="card mb-4">
    <div class="card-header d-flex justify-content-between align-items-center py-3">
        <span><i class="bi bi-people-fill"></i> Users Overview</span>
        <a href="users.php" class="btn btn-sm btn-outline-secondary">Manage Users</a>
    </div>
    <div class="card-body p-0">
        <div class="table-responsive">
            <table class="table table-hover mb-0">
                <thead>
                    <tr>
                        <th>User</th>
                        <th>Email</th>
                        <th>Role</th>
                        <th>Messages</th>
                        <th>Delivered</th>
                        <th>Last Login</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <?php foreach ($usersList as $u): ?>
                    <tr>
                        <td><strong><?= htmlspecialchars($u['name']) ?></strong></td>
                        <td class="small"><?= htmlspecialchars($u['email']) ?></td>
                        <td><span class="badge bg-<?= $u['role'] === 'admin' ? 'primary' : 'secondary' ?>"><?= ucfirst($u['role']) ?></span></td>
                        <td><?= number_format($u['total_messages']) ?></td>
                        <td class="text-success"><?= number_format($u['delivered']) ?></td>
                        <td class="small text-muted"><?= $u['last_login'] ? date('M d H:i', strtotime($u['last_login'])) : 'Never' ?></td>
                        <td>
                            <?php if ($u['is_active']): ?>
                                <span class="badge bg-success">Active</span>
                            <?php else: ?>
                                <span class="badge bg-danger">Disabled</span>
                            <?php endif; ?>
                        </td>
                    </tr>
                    <?php endforeach; ?>
                </tbody>
            </table>
        </div>
    </div>
</div>
<?php endif; ?>

<!-- Recent Messages -->
<div class="card">
    <div class="card-header d-flex justify-content-between align-items-center py-3">
        <span><i class="bi bi-clock-history"></i> Recent Messages</span>
        <a href="messages.php" class="btn btn-sm btn-outline-secondary">View All</a>
    </div>
    <div class="card-body p-0">
        <?php if (empty($recentMessages)): ?>
        <div class="empty-state">
            <i class="bi bi-inbox"></i>
            <h5>No messages yet</h5>
            <p>Send your first message via the API or the Send page.</p>
            <a href="send.php" class="btn btn-wa btn-sm">Send a Message</a>
        </div>
        <?php else: ?>
        <div class="table-responsive">
            <table class="table table-hover mb-0">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Phone</th>
                        <th>Message</th>
                        <th>Type</th>
                        <th>Status</th>
                        <th>Time</th>
                        <?php if ($showAll): ?><th>User</th><?php endif; ?>
                    </tr>
                </thead>
                <tbody>
                    <?php foreach ($recentMessages as $msg): ?>
                    <tr>
                        <td><strong>#<?= $msg['id'] ?></strong></td>
                        <td><i class="bi bi-telephone"></i> <?= htmlspecialchars($msg['phone']) ?></td>
                        <td class="msg-preview"><?= htmlspecialchars($msg['message']) ?></td>
                        <td>
                            <span class="badge bg-<?= $msg['whatsapp_type'] === 'whatsapp_business' ? 'info' : 'success' ?>">
                                <?= $msg['whatsapp_type'] === 'whatsapp_business' ? 'Business' : 'Personal' ?>
                            </span>
                        </td>
                        <td><span class="badge-status badge-<?= $msg['status'] ?>"><?= ucfirst($msg['status']) ?></span></td>
                        <td class="text-muted small"><?= date('M d H:i', strtotime($msg['created_at'])) ?></td>
                        <?php if ($showAll): ?>
                        <td class="small"><?= htmlspecialchars($msg['user_name'] ?? 'N/A') ?></td>
                        <?php endif; ?>
                    </tr>
                    <?php endforeach; ?>
                </tbody>
            </table>
        </div>
        <?php endif; ?>
    </div>
</div>

<?php renderFooter(); ?>
