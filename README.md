# Keycloak Telegram OTP Authenticator

![License](https://img.shields.io/badge/License-MIT-yellow.svg)
![Maven Central](https://img.shields.io/maven-central/v/com.mycompany/keycloak-telegram-auth)

Keycloak authenticator that delivers One-Time Passwords (OTP) via Telegram when users attempt to log in.

## Features
- Sends OTP codes to users via Telegram during authentication.
- Integrates seamlessly with Keycloak's authentication flow.
- Configurable Telegram bot settings.
- Works with Keycloak 23.0.7.

## Prerequisites
- Java 17+
- Maven 3.8+
- Keycloak 23.0.7
- Telegram bot token (get it from [BotFather](https://core.telegram.org/bots#6-botfather)).

## Installation
1. Build the plugin:
   ```bash
   mvn clean package
   ```
2. Copy the generated JAR file from `target/keycloak-telegram-auth-1.0.0.jar` to your Keycloak's `providers` directory:
   ```bash
   cp target/keycloak-telegram-auth-1.0.0.jar $KEYCLOAK_HOME/providers/
   ```
3. Restart Keycloak.

## Environment Variables

The plugin requires the following environment variables to be set:

### Required
- `TELEGRAM_PROXY_URL` - URL of your proxy server (e.g., `http://localhost:8000/webhook`)

### Docker/Docker Compose Example
```yaml
services:
  keycloak:
    environment:
      - TELEGRAM_PROXY_URL=http://telegram-proxy:8000/webhook
```

### System Environment
```bash
export TELEGRAM_PROXY_URL="http://localhost:8000/webhook"
```

## Proxy Server Setup

This plugin uses a custom proxy server to send Telegram messages. The proxy server should:

1. **Accept GET requests** with the following parameters:
   - `phone` - Telegram chat ID of the user
   - `code` - The complete message including client ID and OTP code

2. **Example Flask proxy server**:
```python
import telebot
from flask import Flask, request
from creds import telegrambotapi

bot = telebot.TeleBot(telegrambotapi)
app = Flask(__name__)

@app.route('/webhook', methods=['GET','POST'])
def webhook():
    try:
        args_dict = request.args
        chat_id = args_dict.get('phone')
        code = args_dict.get('code')
        
        bot.send_message(chat_id, text=code)
        return f'done, code was sent to: {chat_id}\nCode: {code}'
    except Exception as e:
        return str(e) + ' user might not be found'

app.run(host='0.0.0.0', port=8000)
```

3. **Example request to proxy**:
```
GET http://localhost:8000/webhook?phone=123456789&code=Your%20OTP%20code%20for%20grafana%20is%3A%20123456
```

## Keycloak Configuration
1. In Keycloak Admin Console:
   - Navigate to Authentication → Flows.
   - Create a new flow or copy an existing one.
   - Add "Telegram OTP Authenticator" execution.

2. **User Setup**:
   - Users must have `telegram_chat_id` attribute set in their profile
   - This attribute should contain their Telegram chat ID
   - Can be configured via LDAP or directly in Keycloak user attributes

## Usage

### User Setup
1. Users must have their Telegram chat ID configured in the `telegram_chat_id` attribute.
2. To get Telegram chat ID, users can:
   - Send a message to your bot
   - Use [@userinfobot](https://t.me/userinfobot) to get their chat ID

### Authentication Flow
1. User attempts to log in to a Keycloak-protected application.
2. After primary authentication, they receive an OTP via Telegram.
3. The message format: "Your OTP code for {client_id} is: {6-digit-code}"
4. User enters the 6-digit OTP code to complete authentication.
5. OTP codes expire after 2 minutes.
6. Users can request a new code using the "Resend Code" button.

### Message Format
The OTP message includes the client application name:
- For Grafana: "Your OTP code for grafana is: 123456"
- For Portainer: "Your OTP code for portainer is: 123456"
- Default: "Your OTP code for Keycloak is: 123456"

## Development
```bash
mvn clean install
```

## License
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
