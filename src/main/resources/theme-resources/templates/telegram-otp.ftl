<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("telegramOtpTitle")}
    <#elseif section = "form">
        <form id="kc-telegram-otp-form" action="${url.loginAction}" method="post" autocomplete="off">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="otp" class="${properties.kcLabelClass!}">${msg("telegramOtpLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="otp" name="otp" 
                           class="${properties.kcInputClass!}"
                           autocomplete="one-time-code"
                           inputmode="numeric"
                           pattern="[0-9]{6}"
                           maxlength="6"
                           required
                           autofocus />
                </div>
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit" 
                           value="${msg("doSubmit")}" />
                </div>
                
                <div class="${properties.kcFormOptionsWrapperClass!}" style="text-align: center; margin-top: 20px;">
                    <div style="margin-bottom: 10px;">
                        <span class="${properties.kcFormOptionsLabelClass!}">${msg("notReceivedCode")}</span>
                    </div>
                    <button type="button" 
                            id="resend-code"
                            class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                            style="margin-top: 10px;">
                        ${msg("resendCode")}
                    </button>
                </div>
            </div>
        </form>

        <script>
            document.addEventListener('DOMContentLoaded', function() {
                console.log('Telegram OTP form loaded');
                const resendButton = document.getElementById('resend-code');
                let countdown = 32;
                
                // Логирование для отладки
                const form = document.getElementById('kc-telegram-otp-form');
                form.addEventListener('submit', function(e) {
                    console.log('Form submitted with OTP:', document.getElementById('otp').value);
                });
                
                // Функция для отправки запроса на повторную отправку кода
                function resendOtp() {
                    // Отправляем запрос через скрытую форму для правильной обработки Keycloak
                    const form = document.createElement('form');
                    form.method = 'POST';
                    form.action = '${url.loginAction}';
                    
                    const resendInput = document.createElement('input');
                    resendInput.type = 'hidden';
                    resendInput.name = 'resend';
                    resendInput.value = 'true';
                    
                    form.appendChild(resendInput);
                    document.body.appendChild(form);
                    
                    console.log('Отправка resend запроса');
                    form.submit(); // Это обновит страницу с новой формой
                }
                
                // Таймер обратного отсчета
                function updateResendButton() {
                    if (countdown > 0) {
                        resendButton.disabled = true;
                        resendButton.textContent = '${msg("resendCode")} (' + countdown + ')';
                        countdown--;
                        setTimeout(updateResendButton, 1000);
                    } else {
                        resendButton.disabled = false;
                        resendButton.textContent = '${msg("resendCode")}';
                    }
                }
                
                // Назначаем обработчик клика
                resendButton.addEventListener('click', resendOtp);
                
                // Запускаем таймер при загрузке
                updateResendButton();
            });
        </script>
    </#if>
</@layout.registrationLayout>
