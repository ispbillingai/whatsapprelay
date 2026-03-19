# WA Relay - Setup Guide

## Architecture

```
[Your Web App] --POST--> [PHP Server] --poll--> [Android APK] --WhatsApp Intent--> [WhatsApp]
                            (MySQL)                (Accessibility Service auto-taps send)
```

## Part 1: PHP Server Setup

### Requirements
- PHP 7.4+ with PDO MySQL extension
- MySQL 5.7+ or MariaDB
- Apache with mod_rewrite (or Nginx)

### Steps

1. **Create the database:**
   ```sql
   mysql -u root -p < server/db.sql
   ```

2. **Configure the server:**
   Edit `server/config.php`:
   - Set your database credentials (`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`)
   - Set a strong `API_KEY` (generate one: `openssl rand -hex 32`)

3. **Deploy files:**
   Upload the `server/` folder contents to your web server.
   Make sure `.htaccess` rewrites are enabled.

4. **Test:**
   ```bash
   # Health check
   curl http://yourserver.com/health

   # Send a test message
   curl -X POST http://yourserver.com/send \
     -H "Content-Type: application/json" \
     -H "X-API-Key: YOUR_API_KEY" \
     -d '{"phone": "27812345678", "message": "Hello from relay!", "whatsapp_type": "whatsapp"}'
   ```

### API Endpoints

| Method | Endpoint     | Description                          |
|--------|-------------|--------------------------------------|
| GET    | /health     | Server health check (no auth)        |
| POST   | /send       | Queue a single message               |
| POST   | /send-bulk  | Queue multiple messages              |
| GET    | /pending    | Get pending messages (APK polls this)|
| POST   | /status     | Update message delivery status       |
| GET    | /messages   | List messages with filters           |

### Send Message Parameters
```json
{
    "phone": "27812345678",
    "message": "Your message text here",
    "whatsapp_type": "whatsapp",
    "priority": 0
}
```
- `phone`: Phone number with country code, no + or spaces
- `message`: The message text
- `whatsapp_type`: `"whatsapp"` or `"whatsapp_business"`
- `priority`: Higher number = sent first (optional, default 0)

---

## Part 2: Android APK Setup

### Building the APK

#### Option A: Android Studio (Recommended)
1. Open Android Studio
2. File → Open → select the project root folder
3. Wait for Gradle sync to complete
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK will be at `app/build/outputs/apk/debug/app-debug.apk`

#### Option B: Command Line
```bash
# Make sure ANDROID_HOME is set
./gradlew assembleDebug
```

### Installing on the Phone

1. Transfer the APK to your Android phone
2. Enable "Install from unknown sources" in Settings
3. Install the APK

### Configuring the APK

1. **Open WA Relay** app on the phone

2. **Server Settings:**
   - Server URL: `https://yourserver.com` (your PHP server base URL)
   - API Key: Same key you set in `config.php`
   - Poll interval: 5 seconds (default, adjust as needed)
   - WhatsApp type: Choose WhatsApp or WhatsApp Business

3. **Click "Save Settings"** then **"Test Connection"** to verify

4. **Enable Accessibility Service:**
   - Click "Enable Accessibility Service"
   - Find "WA Relay" in the list
   - Toggle it ON
   - Accept the permission dialog
   - **This is required** — it's how the app auto-taps WhatsApp's send button

5. **Disable Battery Optimization:**
   - Click "Disable Battery Optimization"
   - This prevents Android from killing the service

6. **Start the Service:**
   - Click "Start Relay Service"
   - A persistent notification will appear
   - The app is now polling your server for messages

### How It Works

1. Your web app POSTs a message to the PHP server
2. The APK polls `/pending` every N seconds
3. When a message is found, the APK:
   - Opens WhatsApp with the target number and message pre-filled (via deep link)
   - The Accessibility Service detects WhatsApp opened
   - It finds and auto-taps the send button
   - Reports delivery status back to the server
   - Presses back to return and process the next message

### Choosing WhatsApp vs WhatsApp Business

You can set the default in the app, but each message can override it:
```json
{
    "phone": "27812345678",
    "message": "Hello!",
    "whatsapp_type": "whatsapp_business"
}
```
The APK will open the correct WhatsApp app based on this field.

---

## Part 3: Sending from Your Web App

### PHP Example
```php
function sendWhatsApp($phone, $message, $type = 'whatsapp') {
    $ch = curl_init('https://yourserver.com/send');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode([
            'phone' => $phone,
            'message' => $message,
            'whatsapp_type' => $type
        ]),
        CURLOPT_HTTPHEADER => [
            'Content-Type: application/json',
            'X-API-Key: YOUR_API_KEY'
        ]
    ]);
    $response = json_decode(curl_exec($ch), true);
    curl_close($ch);
    return $response;
}

// Usage
sendWhatsApp('27812345678', 'Order #123 confirmed!');
sendWhatsApp('27812345678', 'Invoice attached', 'whatsapp_business');
```

### Python Example
```python
import requests

def send_whatsapp(phone, message, wa_type='whatsapp'):
    response = requests.post(
        'https://yourserver.com/send',
        json={'phone': phone, 'message': message, 'whatsapp_type': wa_type},
        headers={'X-API-Key': 'YOUR_API_KEY'}
    )
    return response.json()

send_whatsapp('27812345678', 'Hello from Python!')
```

### JavaScript / Node.js Example
```javascript
async function sendWhatsApp(phone, message, type = 'whatsapp') {
    const res = await fetch('https://yourserver.com/send', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': 'YOUR_API_KEY'
        },
        body: JSON.stringify({ phone, message, whatsapp_type: type })
    });
    return res.json();
}

sendWhatsApp('27812345678', 'Hello from Node!');
```

### cURL
```bash
curl -X POST https://yourserver.com/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{"phone":"27812345678","message":"Test message","whatsapp_type":"whatsapp"}'
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Messages not sending | Check Accessibility Service is enabled |
| Service stops after a while | Disable battery optimization, lock app in recents |
| "Connection failed" | Verify server URL and API key match |
| WhatsApp not opening | Ensure WhatsApp is installed and logged in |
| Send button not found | WhatsApp may have updated its UI - check logs |
| Messages stuck as "pending" | Check APK is running and polling (view logs) |

## Tips for Reliability

1. **Lock the app in recent apps** (long press → lock icon on most phones)
2. **Disable battery optimization** for both WA Relay AND WhatsApp
3. **Keep the phone plugged in** and screen on (or use a developer option to stay awake while charging)
4. **Set poll interval to 5-10 seconds** for near-real-time delivery
5. **Monitor logs** via the app's log viewer or the server's `/messages` endpoint
