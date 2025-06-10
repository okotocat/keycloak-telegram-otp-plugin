<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("telegramOtpTitle")}
    <#elseif section = "form">
        <form id="kc-telegram-otp-form" action="${url.loginAction}" method="post">
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
                
                <div class="${properties.kcFormOptionsWrapperClass!}">
                    <span class="${properties.kcFormOptionsLabelClass!}">${msg("notReceivedCode")}</span>
                    <button type="button" 
                            id="resend-code"
                            class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonSmallClass!}">
                        ${msg("resendCode")}
                    </button>
                </div>
            </div>
        </form>

        <script>
            document.addEventListener('DOMContentLoaded', function() {
                const resendButton = document.getElementById('resend-code');
                let countdown = 30;
                
                // Функция для отправки запроса на повторную отправку кода
                function resendOtp() {
                    fetch('${url.loginAction}', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded',
                        },
                        body: 'resend=true'
                    }).then(response => {
                        if (response.ok) {
                            // Запускаем таймер после успешной отправки
                            countdown = 30;
                            updateResendButton();
                        }
                    });
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
