package com.mycompany.app.auth;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import java.security.SecureRandom;
import java.io.IOException;

public class TelegramOTPAuthenticator implements Authenticator {
    private static final Logger logger = Logger.getLogger(TelegramOTPAuthenticator.class);
    
    // Атрибуты
    private static final String TELEGRAM_CHAT_ID_ATTR = "telegram_chat_id";  // Берется из LDAP
    // Используем сессионные атрибуты вместо атрибутов пользователя для LDAP совместимости
    private static final String SESSION_OTP_CODE = "kc_telegram_otp_code";        // OTP код в сессии
    private static final String SESSION_OTP_TIMESTAMP = "kc_telegram_otp_timestamp"; // Время отправки в сессии
    
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
        
        // Сохраняем код и время отправки в сессии (для совместимости с LDAP)
        context.getAuthenticationSession().setAuthNote(SESSION_OTP_CODE, otp);
        context.getAuthenticationSession().setAuthNote(SESSION_OTP_TIMESTAMP, String.valueOf(currentTime));

        try {
            sendTelegramMessage(context, chatId, otp);
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
        logger.infof("=== ACTION вызван для пользователя: %s ===", context.getUser().getUsername());
        logger.infof("Параметры запроса: %s", context.getHttpRequest().getDecodedFormParameters().keySet());
        
        // Обработка повторной отправки кода - генерируем новый код с новым интервалом
        if (context.getHttpRequest().getDecodedFormParameters().containsKey("resend")) {
            logger.info("=== Обработка RESEND ===");
            UserModel user = context.getUser();
            
            // Генерируем новый OTP код и обновляем время
            String newOtp = generateNewOTP();
            long currentTime = System.currentTimeMillis();
            
            context.getAuthenticationSession().setAuthNote(SESSION_OTP_CODE, newOtp);
            context.getAuthenticationSession().setAuthNote(SESSION_OTP_TIMESTAMP, String.valueOf(currentTime));
            
            try {
                sendTelegramMessage(
                    context,
                    user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR),
                    newOtp
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
        
        logger.infof("=== Проверка OTP кода: %s ===", enteredOtp);
        logger.infof("Сохраненный код: %s", context.getAuthenticationSession().getAuthNote(SESSION_OTP_CODE));
        logger.infof("Время отправки: %s", context.getAuthenticationSession().getAuthNote(SESSION_OTP_TIMESTAMP));
        
        if (!validateOTP(context, enteredOtp)) {
            logger.warn("=== ВАЛИДАЦИЯ ПРОВАЛЕНА ===");
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

    // Валидация OTP с проверкой временного интервала (используем сессионные атрибуты)
    private boolean validateOTP(AuthenticationFlowContext context, String enteredOtp) {
        if (enteredOtp == null || enteredOtp.length() != 6 || !enteredOtp.matches("\\d+")) {
            logger.warnf("Неверный формат OTP: %s", enteredOtp);
            return false;
        }
        
        // Получаем сохраненный код и время отправки из сессии
        String savedOtp = context.getAuthenticationSession().getAuthNote(SESSION_OTP_CODE);
        String timestampStr = context.getAuthenticationSession().getAuthNote(SESSION_OTP_TIMESTAMP);
        
        if (savedOtp == null || timestampStr == null) {
            logger.warn("Отсутствует сохраненный OTP код или время отправки в сессии");
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
            
            // Очищаем использованный код из сессии
            context.getAuthenticationSession().removeAuthNote(SESSION_OTP_CODE);
            context.getAuthenticationSession().removeAuthNote(SESSION_OTP_TIMESTAMP);
            
            return true;
            
        } catch (NumberFormatException e) {
            logger.error("Ошибка парсинга времени отправки OTP: " + timestampStr, e);
            return false;
        }
    }

    // Отправка сообщения через прокси-сервер
    private void sendTelegramMessage(AuthenticationFlowContext context, String chatId, String otp) throws IOException {
        // Получаем client_id из параметров запроса
        String clientId = context.getUriInfo().getQueryParameters().getFirst("client_id");
        if (clientId == null || clientId.isEmpty()) {
            clientId = "Keycloak"; // fallback если client_id не найден
        }
        
        // Формируем сообщение с client_id
        String message = String.format("Your OTP code for %s is: %s", clientId, otp);
        
        // URL вашего прокси-сервера
        String proxyUrl = System.getenv("TELEGRAM_PROXY_URL"); // например: http://localhost:8000/webhook
        if (proxyUrl == null) {
            throw new IOException("TELEGRAM_PROXY_URL environment variable not set");
        }
        
        // Формируем URL с параметрами
        String fullUrl = String.format("%s?phone=%s&code=%s", 
            proxyUrl, 
            chatId, 
            java.net.URLEncoder.encode(message, "UTF-8")
        );
        
        logger.infof("Отправка OTP для client_id '%s' через прокси: %s", clientId, fullUrl);
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(fullUrl);
            client.execute(get);
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