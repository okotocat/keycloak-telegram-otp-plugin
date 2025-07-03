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

## Configuration
1. In Keycloak Admin Console:
   - Navigate to Authentication → Flows.
   - Create a new flow or copy an existing one.
   - Add "Telegram OTP Authenticator" execution.
2. Configure the authenticator:
   - Telegram Bot Token.
   - OTP expiration time.
   - OTP length.
   - Custom message template.

## Usage
1. Users must register their Telegram ID in their account settings.
2. During login, they will receive an OTP via Telegram.
3. User enters the OTP to complete authentication.

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
