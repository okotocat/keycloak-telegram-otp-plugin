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
import de.taimos.totp.TOTP;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.DecoderException;

import java.security.SecureRandom;
import java.io.IOException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;

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
            String secret = user.getFirstAttribute(TOTP_SECRET_ATTR);  // Используем СУЩЕСТВУЮЩИЙ секрет
            if (secret == null || secret.isEmpty()) {
                secret = generateRandomSecret();
                user.setSingleAttribute(TOTP_SECRET_ATTR, secret);
            }
            try {
                sendTelegramMessage(
                    user.getFirstAttribute(TELEGRAM_CHAT_ID_ATTR),
                    "Your new OTP code: " + generateOTP(secret)
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
        return TOTP.getOTP(hexKey); // Стандартный 30-секундный интервал
    }

    // Валидация OTP с временным окном толерантности
    private boolean validateOTP(String secret, String otp) {
        if (secret == null || otp == null || otp.length() != 6 || !otp.matches("\\d+")) {
            return false;
        }
        
        Base32 base32 = new Base32();
        byte[] bytes = base32.decode(secret);
        String hexKey = Hex.encodeHexString(bytes);
        
        // Получаем текущее время в секундах
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long timeStep = 30; // TOTP использует 30-секундные интервалы
        
        // Проверяем код для текущего и соседних временных окон (-1, 0, +1)
        // Это дает окно толерантности в 90 секунд (±30 сек)
        for (int i = -1; i <= 1; i++) {
            long timeWindow = (currentTimeSeconds / timeStep + i) * timeStep;
            String expectedOtp = generateOTPForTime(hexKey, timeWindow);
            
            logger.debugf("Проверяем OTP для времени %d: ожидаем %s, получили %s", 
                         timeWindow, expectedOtp, otp);
            
            if (expectedOtp.equals(otp)) {
                logger.infof("OTP валиден для временного окна %d (смещение: %d)", timeWindow, i);
                return true;
            }
        }
        
        logger.warnf("Не найден подходящий OTP код для входящего %s", otp);
        return false;
    }
    
    // Генерация OTP для конкретного времени
    private String generateOTPForTime(String hexKey, long timeSeconds) {
        // Конвертируем время в временные шаги (каждые 30 секунд)
        long timeSteps = timeSeconds / 30;
        
        try {
            // Используем рефлексию для доступа к методу с временным параметром
            // или создаем собственную реализацию TOTP
            return generateTOTPCode(hexKey, timeSteps);
        } catch (Exception e) {
            logger.error("Ошибка генерации TOTP для времени " + timeSeconds, e);
            return TOTP.getOTP(hexKey); // Fallback к текущему времени
        }
    }
    
    // Простая реализация TOTP алгоритма
    private String generateTOTPCode(String hexKey, long timeSteps) {
        try {
            byte[] keyBytes = Hex.decodeHex(hexKey.toCharArray());
            byte[] timeBytes = new byte[8];
            
            // Конвертируем время в big-endian формат
            for (int i = 7; i >= 0; i--) {
                timeBytes[i] = (byte) (timeSteps & 0xff);
                timeSteps >>= 8;
            }
            
            // Вычисляем HMAC-SHA1
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA1");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA1");
            hmac.init(keySpec);
            byte[] hash = hmac.doFinal(timeBytes);
            
            // Извлекаем 4 байта из хеша
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) |
                        ((hash[offset + 1] & 0xff) << 16) |
                        ((hash[offset + 2] & 0xff) << 8) |
                        (hash[offset + 3] & 0xff);
            
            // Получаем 6-значный код
            int otp = binary % 1000000;
            return String.format("%06d", otp);
            
        } catch (Exception e) {
            logger.error("Ошибка в generateTOTPCode", e);
            return TOTP.getOTP(hexKey); // Fallback
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