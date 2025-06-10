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
    
    private static final String TELEGRAM_CHAT_ID_ATTR = "telegram_chat_id";
    private static final String TOTP_SECRET_ATTR = "kc_telegram_totp_secret";
    private static final int TOTP_INTERVAL = 5; // 5-секундный интервал

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String chatId = user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR);

        if (chatId == null || chatId.isEmpty()) {
            context.success();
            return;
        }

        String secret = user.getFirstAttribute(TOTP_SECRET_ATTR);
        if (secret == null || secret.isEmpty()) {
            secret = generateRandomSecret();
            user.setSingleAttribute(TOTP_SECRET_ATTR, secret);
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
        if (context.getHttpRequest().getDecodedFormParameters().containsKey("resend")) {
            UserModel user = context.getUser();
            String secret = user.getFirstAttribute(TOTP_SECRET_ATTR);
            
            if (secret == null || secret.isEmpty()) {
                secret = generateRandomSecret();
                user.setSingleAttribute(TOTP_SECRET_ATTR, secret);
            }
            
            try {
                String otp = generateOTP(secret);
                sendTelegramMessage(
                    user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR),
                    "Your new OTP code: " + otp
                );
                Response challenge = context.form()
                    .setSuccess("Новый код отправлен")
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

        String enteredOtp = context.getHttpRequest().getDecodedFormParameters().getFirst("otp");
        String secret = context.getUser().getFirstAttribute(TOTP_SECRET_ATTR);

        if (!validateOTP(secret, enteredOtp)) {
            Response challenge = context.form()
                .setError("Неверный код")
                .createForm("telegram-otp.ftl");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }
        context.success();
    }

    private String generateRandomSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        Base32 base32 = new Base32();
        return base32.encodeToString(bytes);
    }

    private String generateOTP(String secret) {
        return generateOTP(secret, 0);
    }

    private String generateOTP(String secret, int timeOffsetSeconds) {
        Base32 base32 = new Base32();
        byte[] bytes = base32.decode(secret);
        String hexKey = Hex.encodeHexString(bytes);
        long time = (System.currentTimeMillis() / 1000) + timeOffsetSeconds;
        return TOTP.getOTP(hexKey, time, 6, "HmacSHA1", TOTP_INTERVAL);
    }

    private boolean validateOTP(String secret, String otp) {
        if (secret == null || otp == null || otp.length() != 6 || !otp.matches("\\d+")) {
            return false;
        }
        
        // Проверяем текущий и предыдущий интервалы
        return generateOTP(secret, 0).equals(otp) || 
               generateOTP(secret, -TOTP_INTERVAL).equals(otp);
    }

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