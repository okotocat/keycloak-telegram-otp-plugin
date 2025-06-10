package com.mycompany.app.auth;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.*;
import org.keycloak.services.messages.Messages;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import de.taimos.totp.TOTP;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;

import java.security.SecureRandom;
import java.io.IOException;

public class TelegramOTPAuthenticator implements Authenticator {
    private static final Logger logger = Logger.getLogger(TelegramOTPAuthenticator.class);
    
    // Атрибуты (хранятся в Keycloak DB, а не в LDAP)
    private static final String TELEGRAM_CHAT_ID_ATTR = "telegram_chat_id";  // Берется из LDAP
    private static final String TOTP_SECRET_ATTR = "kc_telegram_totp_secret"; // Сохраняется в Keycloak DB

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String chatId = user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR);

        // Если у пользователя нет chat_id, пропускаем OTP
        if (chatId == null || chatId.isEmpty()) {
            context.success();
            return;
        }

        // Получаем или генерируем TOTP-секрет (хранится в Keycloak DB)
        String secret = user.getFirstAttribute(TOTP_SECRET_ATTR);
        if (secret == null || secret.isEmpty()) {
            secret = generateRandomSecret();
            user.setSingleAttribute(TOTP_SECRET_ATTR, secret);  // ✅ Записываем в Keycloak DB
        }

        String otp = generateOTP(secret);

        try {
            sendTelegramMessage(chatId, "Your OTP code: " + otp);
            Response challenge = context.form()
                    .setAttribute("username", user.getUsername())
                    .createForm("telegram-otp.ftl");
            context.challenge(challenge);
        } catch (IOException e) {
            logger.error("Ошибка отправки OTP в Telegram", e);
            Response challenge = context.form()
                    .setError("Не удалось отправить код")
                    .createForm("telegram-otp.ftl");
            context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // Обработка повторной отправки кода
        if (context.getHttpRequest().getDecodedFormParameters().containsKey("resend")) {
            UserModel user = context.getUser();
            String secret = user.getFirstAttribute(TOTP_SECRET_ATTR);
            
            // Если секрета нет (хотя должен быть), создаем новый
            if (secret == null || secret.isEmpty()) {
                secret = generateRandomSecret();
                user.setSingleAttribute(TOTP_SECRET_ATTR, secret);
            }
            
            try {
                sendTelegramMessage(
                    user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR),
                    "Your new OTP code: " + generateOTP(secret) // Используем текущий секрет
                );
                Response challenge = context.form()
                    .setSuccess("Код отправлен повторно")
                    .createForm("telegram-otp.ftl");
                context.challenge(challenge);
            } catch (IOException e) {
                logger.error("Ошибка повторной отправки OTP", e);
                Response challenge = context.form()
                    .setError("Ошибка при отправке кода")
                    .createForm("telegram-otp.ftl");
                context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
            }
            return;
        }

        // Проверка введенного OTP
        String enteredOtp = context.getHttpRequest().getDecodedFormParameters().getFirst("otp");
        String secret = context.getUser().getFirstAttribute(TOTP_SECRET_ATTR);  // Берем из Keycloak DB

        if (!validateOTP(secret, enteredOtp)) {
            Response challenge = context.form()
                .setError("Неверный код")
                .createForm("telegram-otp.ftl");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }
        context.success();  // ✅ Успешная аутентификация
    }

    // Генерация случайного TOTP-секрета
    private String generateRandomSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        Base32 base32 = new Base32();
        return base32.encodeToString(bytes);
    }

    // Генерация 6-значного OTP-кода
    private String generateOTP(String secret) {
        Base32 base32 = new Base32();
        byte[] bytes = base32.decode(secret);
        String hexKey = Hex.encodeHexString(bytes);
        return TOTP.getOTP(hexKey, System.currentTimeMillis() / 1000, 6, "HmacSHA1", 5);
    }

    // Валидация OTP
    private boolean validateOTP(String secret, String otp) {
        if (secret == null || otp == null || otp.length() != 6 || !otp.matches("\\d+")) {
            return false;
        }
        return generateOTP(secret).equals(otp);
    }

    // Отправка сообщения в Telegram (без изменений)
    private void sendTelegramMessage(String chatId, String text) throws IOException {
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        String json = String.format("{\"chat_id\":\"%s\",\"text\":\"%s\"}", chatId, text);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(json));
            client.execute(post);
        }
    }

    @Override
    public boolean requiresUser() { return true; }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR) != null;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
