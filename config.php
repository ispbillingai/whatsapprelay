<?php
/**
 * WhatsApp Relay Server - Configuration
 */

// Database configuration
define('DB_HOST', 'localhost');
define('DB_NAME', 'whatsapp');
define('DB_USER', 'whatsapp');
define('DB_PASS', 'whatsapp');

// Message settings
define('MAX_RETRY_COUNT', 3);
define('MESSAGE_EXPIRY_HOURS', 24);

// CORS - adjust for your domain
define('ALLOWED_ORIGIN', '*');

// Session
define('SESSION_LIFETIME', 86400); // 24 hours

// App info
define('APP_NAME', 'FreeISP WA');
define('APP_VERSION', '1.3.0');

// Timezone
date_default_timezone_set('UTC');
