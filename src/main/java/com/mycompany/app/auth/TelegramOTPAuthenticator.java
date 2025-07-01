package com.mycompany.app.auth;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import java.security.SecureRandom;
import java.io.IOException;

public class TelegramOTPAuthenticator implements Authenticator {
    private static final Logger logger = Logger.getLogger(TelegramOTPAuthenticator.class);
    
    // Атрибуты (хранятся в Keycloak DB, а не в LDAP)
    private static final String TELEGRAM_CHAT_ID_ATTR = "telegram_chat_id";  // Берется из LDAP
    private static final String OTP_CODE_ATTR = "kc_telegram_otp_code";        // Текущий OTP код
    private static final String OTP_TIMESTAMP_ATTR = "kc_telegram_otp_timestamp"; // Время отправки кода
    
    // Время жизни OTP кода в секундах (2 минуты)
    private static final long OTP_VALIDITY_SECONDS = 120;

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String chatId = user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR);

        // Если у пользователя нет chat_id, пропускаем OTP
        if (chatId == null || chatId.isEmpty()) {
            context.success();
            return;
        }

        // Генерируем новый OTP код и сохраняем время отправки
        String otp = generateNewOTP();
        long currentTime = System.currentTimeMillis();
        
        // Сохраняем код и время отправки в атрибутах пользователя
        user.setSingleAttribute(OTP_CODE_ATTR, otp);
        user.setSingleAttribute(OTP_TIMESTAMP_ATTR, String.valueOf(currentTime));

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
        // Обработка повторной отправки кода - генерируем новый код с новым интервалом
        if (context.getHttpRequest().getDecodedFormParameters().containsKey("resend")) {
            UserModel user = context.getUser();
            
            // Генерируем новый OTP код и обновляем время
            String newOtp = generateNewOTP();
            long currentTime = System.currentTimeMillis();
            
            user.setSingleAttribute(OTP_CODE_ATTR, newOtp);
            user.setSingleAttribute(OTP_TIMESTAMP_ATTR, String.valueOf(currentTime));
            
            try {
                sendTelegramMessage(
                    user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR),
                    "Your new OTP code: " + newOtp
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
        UserModel user = context.getUser();
        
        if (!validateOTP(user, enteredOtp)) {
            Response challenge = context.form()
                .setError("Неверный код")
                .createForm("telegram-otp.ftl");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }
        context.success();  // ✅ Успешная аутентификация
    }

    // Генерация нового 6-значного OTP кода
    private String generateNewOTP() {
        SecureRandom random = new SecureRandom();
        int code = random.nextInt(900000) + 100000; // Генерируем число от 100000 до 999999
        return String.valueOf(code);
    }

    // Валидация OTP с проверкой временного интервала
    private boolean validateOTP(UserModel user, String enteredOtp) {
        if (enteredOtp == null || enteredOtp.length() != 6 || !enteredOtp.matches("\\d+")) {
            logger.warnf("Неверный формат OTP: %s", enteredOtp);
            return false;
        }
        
        // Получаем сохраненный код и время отправки
        String savedOtp = user.getFirstAttribute(OTP_CODE_ATTR);
        String timestampStr = user.getFirstAttribute(OTP_TIMESTAMP_ATTR);
        
        if (savedOtp == null || timestampStr == null) {
            logger.warn("Отсутствует сохраненный OTP код или время отправки");
            return false;
        }
        
        // Проверяем совпадение кода
        if (!savedOtp.equals(enteredOtp)) {
            logger.warnf("OTP код не совпадает: ожидали %s, получили %s", savedOtp, enteredOtp);
            return false;
        }
        
        // Проверяем не истек ли срок действия кода
        try {
            long otpTimestamp = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            long timeElapsed = (currentTime - otpTimestamp) / 1000; // в секундах
            
            if (timeElapsed > OTP_VALIDITY_SECONDS) {
                logger.warnf("OTP код истек: прошло %d секунд, максимум %d", timeElapsed, OTP_VALIDITY_SECONDS);
                return false;
            }
            
            logger.infof("OTP код успешно валидирован: %s (возраст: %d сек)", enteredOtp, timeElapsed);
            
            // Очищаем использованный код
            user.removeAttribute(OTP_CODE_ATTR);
            user.removeAttribute(OTP_TIMESTAMP_ATTR);
            
            return true;
            
        } catch (NumberFormatException e) {
            logger.error("Ошибка парсинга времени отправки OTP: " + timestampStr, e);
            return false;
        }
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