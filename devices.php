<?php
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/layout.php';
requireLogin();

$db = getDB();
$userId = $_SESSION['user_id'];
$isAdminUser = isAdmin();

// Handle device actions
if ($_SERVER['REQUEST_METHOD'] === 'POST' && verifyCsrf($_POST['csrf'] ?? '')) {
    $action = $_POST['action'] ?? '';
    $deviceId = $_POST['device_id'] ?? '';

    if ($action === 'toggle' && $deviceId) {
        $where = $isAdminUser ? '' : ' AND user_id = ?';
        $params = $isAdminUser ? [$deviceId] : [$deviceId, $userId];
        $db->prepare("UPDATE devices SET is_active = NOT is_active WHERE device_id = ? $where")->execute($params);
        flash('Device status updated');
    }

    if ($action === 'rename' && $deviceId) {
        $newName = trim($_POST['new_name'] ?? '');
        if ($newName) {
            $where = $isAdminUser ? '' : ' AND user_id = ?';
            $params = $isAdminUser ? [$newName, $deviceId] : [$newName, $deviceId, $userId];
            $db->prepare("UPDATE devices SET device_name = ? WHERE device_id = ? $where")->execute($params);
            flash('Device renamed');
        }
    }

    if ($action === 'set_wa_type' && $deviceId) {
        $waType = $_POST['wa_type'] ?? 'both';
        if (in_array($waType, ['whatsapp', 'whatsapp_business', 'both'])) {
            $where = $isAdminUser ? '' : ' AND user_id = ?';
            $params = $isAdminUser ? [$waType, $deviceId] : [$waType, $deviceId, $userId];
            $db->prepare("UPDATE devices SET whatsapp_type = ? WHERE device_id = ? $where")->execute($params);
            flash('Device WhatsApp type updated');
        }
    }

    if ($action === 'delete' && $deviceId) {
        $where = $isAdminUser ? '' : ' AND user_id = ?';
        $params = $isAdminUser ? [$deviceId] : [$deviceId, $userId];
        $db->prepare("DELETE FROM devices WHERE device_id = ? $where")->execute($params);
        flash('Device removed');
    }

    header('Location: devices.php');
    exit;
}

// Fetch devices
if ($isAdminUser) {
    $stmt = $db->query(
        'SELECT d.*, u.name as user_name, u.email,
            (SELECT COUNT(*) FROM messages WHERE device_id = d.device_id AND status = "delivered") as delivered,
            (SELECT COUNT(*) FROM messages WHERE device_id = d.device_id AND status IN ("pending", "sent")) as pending_msgs,
            (SELECT MAX(sent_at) FROM messages WHERE device_id = d.device_id AND status = "delivered") as last_delivered_at,
            (SELECT status FROM messages WHERE device_id = d.device_id ORDER BY id DESC LIMIT 1) as last_msg_status
         FROM devices d
         LEFT JOIN users u ON d.user_id = u.id
         ORDER BY d.last_seen DESC'
    );
} else {
    $stmt = $db->prepare(
        'SELECT d.*,
            (SELECT COUNT(*) FROM messages WHERE device_id = d.device_id AND status = "delivered") as delivered,
            (SELECT COUNT(*) FROM messages WHERE device_id = d.device_id AND status IN ("pending", "sent")) as pending_msgs,
            (SELECT MAX(sent_at) FROM messages WHERE device_id = d.device_id AND status = "delivered") as last_delivered_at,
            (SELECT status FROM messages WHERE device_id = d.device_id ORDER BY id DESC LIMIT 1) as last_msg_status
         FROM devices d
         WHERE d.user_id = ?
         ORDER BY d.last_seen DESC'
    );
    $stmt->execute([$userId]);
}
$devices = $stmt->fetchAll();

renderHeader('Devices', 'devices');
?>

<!-- Info banner -->
<div class="card mb-4 border-0" style="background: linear-gradient(135deg, #075E54, #128C7E); border-radius: 12px;">
    <div class="card-body p-4 text-white">
        <div class="d-flex align-items-center gap-3 mb-3">
            <i class="bi bi-phone-fill" style="font-size: 32px;"></i>
            <div>
                <h5 class="mb-1">Multi-Device Relay</h5>
                <p class="mb-0 small opacity-75">Install the FreeISP WA app on multiple phones to increase throughput and reliability. Devices register automatically when they start polling.</p>
            </div>
        </div>
        <div class="row g-3">
            <div class="col-md-4">
                <div class="bg-dark bg-opacity-25 rounded-3 p-3 h-100">
                    <h6 class="small"><i class="bi bi-phone"></i> Per-Device WhatsApp</h6>
                    <p class="small opacity-75 mb-0">Set each device to use <strong>WhatsApp Personal</strong>, <strong>WhatsApp Business</strong>, or <strong>Both</strong>. Messages are routed to the right device based on this setting.</p>
                </div>
            </div>
            <div class="col-md-4">
                <div class="bg-dark bg-opacity-25 rounded-3 p-3 h-100">
                    <h6 class="small"><i class="bi bi-shuffle"></i> Load Balancing</h6>
                    <p class="small opacity-75 mb-0">When multiple devices are active, messages are <strong>distributed evenly</strong> across them. This reduces ban risk by spreading volume across different WhatsApp accounts.</p>
                </div>
            </div>
            <div class="col-md-4">
                <div class="bg-dark bg-opacity-25 rounded-3 p-3 h-100">
                    <h6 class="small"><i class="bi bi-arrow-repeat"></i> Auto-Failover</h6>
                    <p class="small opacity-75 mb-0">If a device goes offline or fails to send within 3 minutes, messages are <strong>automatically reassigned</strong> to the next available device.</p>
                </div>
            </div>
        </div>
        <div class="mt-3 small opacity-50">
            <i class="bi bi-lightbulb"></i> <strong>Tip:</strong> For maximum protection, install both WhatsApp and WhatsApp Business on each phone with <strong>different phone numbers</strong>. Set one device to "Personal" and another to "Business" to spread messages across 2 accounts.
        </div>
    </div>
</div>

<!-- Stats -->
<div class="row g-3 mb-4">
    <div class="col-sm-4">
        <div class="card text-center">
            <div class="card-body py-3">
                <h3 class="mb-0 text-primary"><?= count($devices) ?></h3>
                <small class="text-muted">Total Devices</small>
            </div>
        </div>
    </div>
    <div class="col-sm-4">
        <div class="card text-center">
            <div class="card-body py-3">
                <?php $online = count(array_filter($devices, fn($d) => $d['is_active'] && $d['last_seen'] && ($d['last_msg_status'] ?? '') !== 'failed')); ?>
                <h3 class="mb-0 text-success"><?= $online ?></h3>
                <small class="text-muted">Online Now</small>
            </div>
        </div>
    </div>
    <div class="col-sm-4">
        <div class="card text-center">
            <div class="card-body py-3">
                <?php $totalDelivered = array_sum(array_column($devices, 'delivered')); ?>
                <h3 class="mb-0"><?= number_format($totalDelivered) ?></h3>
                <small class="text-muted">Messages Delivered</small>
            </div>
        </div>
    </div>
</div>

<!-- Devices Table -->
<div class="card">
    <div class="card-header d-flex justify-content-between align-items-center py-3">
        <span><i class="bi bi-phone-fill"></i> Registered Devices</span>
        <span class="badge bg-secondary"><?= count($devices) ?> device(s)</span>
    </div>
    <div class="card-body p-0">
        <?php if (empty($devices)): ?>
        <div class="text-center py-5 text-muted">
            <i class="bi bi-phone" style="font-size: 48px;"></i>
            <h5 class="mt-3">No Devices Registered</h5>
            <p>Install the FreeISP WA app on a phone and start the relay service. The device will appear here automatically.</p>
        </div>
        <?php else: ?>
        <div class="table-responsive">
            <table class="table table-hover mb-0">
                <thead>
                    <tr>
                        <th>Status</th>
                        <th>Device</th>
                        <th>Device ID</th>
                        <th>WhatsApp</th>
                        <?php if ($isAdminUser): ?><th>User</th><?php endif; ?>
                        <th>Delivered</th>
                        <th>Pending</th>
                        <th>Last Seen</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <?php foreach ($devices as $dev):
                        $lastStatus = $dev['last_msg_status'] ?? '';
                        $isActive = $dev['is_active'] && $dev['last_seen'];
                        $shortId = substr($dev['device_id'], 0, 8);
                    ?>
                    <tr>
                        <td>
                            <?php if (!$dev['is_active']): ?>
                                <span class="badge bg-secondary">Disabled</span>
                            <?php elseif ($lastStatus === 'failed'): ?>
                                <span class="badge bg-danger">Offline</span>
                            <?php elseif ($isActive): ?>
                                <span class="badge bg-success">Online</span>
                            <?php else: ?>
                                <span class="badge bg-warning text-dark">New</span>
                            <?php endif; ?>
                        </td>
                        <td>
                            <strong><?= htmlspecialchars($dev['device_name']) ?></strong>
                            <div class="d-flex gap-1 mt-1">
                                <span class="badge bg-<?= ($dev['svc_accessibility'] ?? 0) ? 'success' : 'danger' ?> bg-opacity-75" style="font-size:9px;" title="Accessibility Service">
                                    <i class="bi bi-hand-index"></i> <?= ($dev['svc_accessibility'] ?? 0) ? 'ON' : 'OFF' ?>
                                </span>
                                <span class="badge bg-<?= ($dev['svc_notification'] ?? 0) ? 'success' : 'danger' ?> bg-opacity-75" style="font-size:9px;" title="Notification Listener">
                                    <i class="bi bi-bell"></i> <?= ($dev['svc_notification'] ?? 0) ? 'ON' : 'OFF' ?>
                                </span>
                                <span class="badge bg-<?= ($dev['svc_battery'] ?? 0) ? 'success' : 'secondary' ?> bg-opacity-75" style="font-size:9px;" title="Battery Optimization Disabled">
                                    <i class="bi bi-battery-charging"></i> <?= ($dev['svc_battery'] ?? 0) ? 'OK' : 'OFF' ?>
                                </span>
                            </div>
                        </td>
                        <td><code class="small"><?= htmlspecialchars($shortId) ?>...</code></td>
                        <td>
                            <form method="POST" class="d-inline">
                                <input type="hidden" name="csrf" value="<?= csrfToken() ?>">
                                <input type="hidden" name="action" value="set_wa_type">
                                <input type="hidden" name="device_id" value="<?= htmlspecialchars($dev['device_id']) ?>">
                                <select name="wa_type" class="form-select form-select-sm" style="width:130px;" onchange="this.form.submit()">
                                    <option value="both" <?= ($dev['whatsapp_type'] ?? 'both') === 'both' ? 'selected' : '' ?>>Both</option>
                                    <option value="whatsapp" <?= ($dev['whatsapp_type'] ?? '') === 'whatsapp' ? 'selected' : '' ?>>Personal</option>
                                    <option value="whatsapp_business" <?= ($dev['whatsapp_type'] ?? '') === 'whatsapp_business' ? 'selected' : '' ?>>Business</option>
                                </select>
                            </form>
                        </td>
                        <?php if ($isAdminUser): ?>
                        <td class="small"><?= htmlspecialchars($dev['user_name'] ?? 'N/A') ?></td>
                        <?php endif; ?>
                        <td class="text-success"><?= number_format($dev['delivered']) ?></td>
                        <td><?= $dev['pending_msgs'] ?></td>
                        <td class="small text-muted">
                            <?= $dev['last_seen'] ? date('M d H:i:s', strtotime($dev['last_seen'])) : 'Never' ?>
                        </td>
                        <td>
                            <div class="d-flex gap-1">
                                <!-- Rename -->
                                <form method="POST" class="d-inline">
                                    <input type="hidden" name="csrf" value="<?= csrfToken() ?>">
                                    <input type="hidden" name="action" value="rename">
                                    <input type="hidden" name="device_id" value="<?= htmlspecialchars($dev['device_id']) ?>">
                                    <input type="text" name="new_name" placeholder="New name" class="form-control form-control-sm d-inline-block" style="width:110px;" value="<?= htmlspecialchars($dev['device_name']) ?>">
                                    <button type="submit" class="btn btn-sm btn-outline-primary" title="Rename"><i class="bi bi-pencil"></i></button>
                                </form>

                                <!-- Toggle -->
                                <form method="POST" class="d-inline">
                                    <input type="hidden" name="csrf" value="<?= csrfToken() ?>">
                                    <input type="hidden" name="action" value="toggle">
                                    <input type="hidden" name="device_id" value="<?= htmlspecialchars($dev['device_id']) ?>">
                                    <button type="submit" class="btn btn-sm <?= $dev['is_active'] ? 'btn-outline-warning' : 'btn-outline-success' ?>" title="<?= $dev['is_active'] ? 'Disable' : 'Enable' ?>">
                                        <i class="bi bi-<?= $dev['is_active'] ? 'pause' : 'play' ?>-fill"></i>
                                    </button>
                                </form>

                                <!-- Delete -->
                                <form method="POST" class="d-inline" onsubmit="return confirm('Remove this device?')">
                                    <input type="hidden" name="csrf" value="<?= csrfToken() ?>">
                                    <input type="hidden" name="action" value="delete">
                                    <input type="hidden" name="device_id" value="<?= htmlspecialchars($dev['device_id']) ?>">
                                    <button type="submit" class="btn btn-sm btn-outline-danger" title="Remove"><i class="bi bi-trash"></i></button>
                                </form>
                            </div>
                        </td>
                    </tr>
                    <?php endforeach; ?>
                </tbody>
            </table>
        </div>
        <?php endif; ?>
    </div>
</div>

<div class="mt-3 small text-muted">
    <i class="bi bi-info-circle"></i> <strong>Status guide:</strong>
    <span class="badge bg-success">Online</span> = last message was delivered successfully or no messages yet.
    <span class="badge bg-danger">Offline</span> = last message sent by this device failed.
    <span class="badge bg-warning text-dark">New</span> = device just registered, hasn't polled yet.
    Service badges show which permissions are enabled on the phone.
</div>

<?php renderFooter(); ?>
