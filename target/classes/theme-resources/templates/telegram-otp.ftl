<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("telegramOtpTitle")}
    <#elseif section = "form">
        <form id="kc-telegram-otp-form" action="${url.loginAction}" method="post">
            <#-- Удаляем блок с alert, так как Keycloak сам выводит сообщения -->
            
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="otp" class="${properties.kcLabelClass!}">${msg("telegramOtpLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="otp" name="otp" class="${properties.kcInputClass!}" 
                           autofocus autocomplete="off" pattern="\d{6}" 
                           title="${msg("otpDigitsOnly")}" maxlength="6"/>
                </div>
            </div>
            
            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit" value="${msg("doSubmit")}"/>
                </div>
                <div class="${properties.kcFormOptionsWrapperClass!}">
                    <button type="submit" name="resend" value="true" 
                            class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}">
                        ${msg("resendCode")}
                    </button>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>