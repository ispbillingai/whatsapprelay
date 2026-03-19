<?php
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/layout.php';
requireLogin();

$db = getDB();
$userId = $_SESSION['user_id'];
$success = false;
$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!verifyCsrf($_POST['csrf'] ?? '')) {
        $error = 'Invalid session. Please try again.';
    } else {
        $phoneRaw = trim($_POST['phone_raw'] ?? '');
        $message = trim($_POST['message'] ?? '');
        $whatsappType = $_POST['whatsapp_type'] ?? 'whatsapp';
        $priority = intval($_POST['priority'] ?? 0);

        if (empty($phoneRaw)) {
            $error = 'Phone number is required';
        } elseif (empty($message)) {
            $error = 'Message is required';
        } else {
            // Support bulk: one number per line
            $phones = array_filter(array_map('trim', explode("\n", $phoneRaw)));

            if (empty($phones)) {
                $phones = [$phone];
            }

            $stmt = $db->prepare('INSERT INTO messages (user_id, phone, message, whatsapp_type, priority) VALUES (?, ?, ?, ?, ?)');
            $count = 0;

            foreach ($phones as $p) {
                $p = preg_replace('/[^0-9]/', '', $p);
                if (!empty($p) && !empty($message)) {
                    $stmt->execute([$userId, $p, $message, $whatsappType, $priority]);
                    $count++;
                }
            }

            flash("$count message(s) queued for delivery!");
            header('Location: messages.php');
            exit;
        }
    }
}

renderHeader('Send Message', 'send');
?>

<div class="row justify-content-center">
    <div class="col-lg-8">
        <div class="card">
            <div class="card-header py-3">
                <i class="bi bi-send"></i> Compose Message
            </div>
            <div class="card-body">
                <?php if ($error): ?>
                <div class="alert alert-danger"><?= htmlspecialchars($error) ?></div>
                <?php endif; ?>

                <form method="POST">
                    <input type="hidden" name="csrf" value="<?= csrfToken() ?>">

                    <div class="mb-3">
                        <label class="form-label">Phone Number(s) <span class="text-danger">*</span></label>
                        <textarea name="phone_raw" class="form-control" rows="3"
                                  placeholder="Enter phone numbers with country code, one per line:&#10;27812345678&#10;44771234567&#10;1555123456"
                                  required><?= htmlspecialchars($_POST['phone_raw'] ?? '') ?></textarea>
                        <div class="form-text">Include country code, no + or spaces. One number per line for bulk.</div>
                    </div>

                    <div class="mb-3">
                        <label class="form-label">Message <span class="text-danger">*</span></label>
                        <textarea name="message" class="form-control" rows="5"
                                  placeholder="Type your message here..."
                                  required><?= htmlspecialchars($_POST['message'] ?? '') ?></textarea>
                        <div class="form-text"><span id="charCount">0</span> characters</div>
                    </div>

                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <label class="form-label">WhatsApp App</label>
                            <select name="whatsapp_type" class="form-select">
                                <option value="whatsapp">WhatsApp (Personal)</option>
                                <option value="whatsapp_business">WhatsApp Business</option>
                            </select>
                        </div>
                        <div class="col-md-6 mb-3">
                            <label class="form-label">Priority</label>
                            <select name="priority" class="form-select">
                                <option value="0">Normal</option>
                                <option value="1">High</option>
                                <option value="2">Urgent</option>
                            </select>
                        </div>
                    </div>

                    <div class="d-flex gap-2">
                        <button type="submit" class="btn btn-wa btn-lg">
                            <i class="bi bi-send"></i> Send Message
                        </button>
                        <a href="messages.php" class="btn btn-outline-secondary btn-lg">Cancel</a>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>

<script>
document.querySelector('textarea[name="message"]').addEventListener('input', function() {
    document.getElementById('charCount').textContent = this.value.length;
});
</script>

<?php renderFooter(); ?>
