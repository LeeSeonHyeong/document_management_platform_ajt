package com.ajt.backend.global.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정 인증번호 등 안내 메일을 보내는 공통 발송기입니다.
 *
 * <p>SMTP 접속 계정·비밀번호는 환경변수(spring.mail.*)로만 주입되며 코드/깃에 두지 않습니다.
 * 실제 발송은 유효한 SMTP 설정이 있을 때만 동작하고, 발신 주소는 ajt.mail.from으로 정합니다.
 *
 * <p><b>발송 실패를 호출자에게 전파하지 않는다(S15P11B106-101).</b> 비밀번호 재설정 요청은
 * 계정 존재 여부를 숨기기 위해 등록 여부와 무관하게 같은 200 응답을 돌려주는 계약이다. 메일 발송이
 * 예외를 던지면 그 500이 "존재하는 이메일에서만" 발생해 계정 존재가 노출되고 계약도 깨진다. 따라서
 * SMTP 미설정(로컬)에서는 발송을 건너뛰고, 설정돼 있어도 발송 실패는 로깅만 하고 삼킨다.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String smtpUsername;

    public EmailSender(
            JavaMailSender mailSender,
            @Value("${ajt.mail.from:noreply@ajt.local}") String from,
            @Value("${spring.mail.username:}") String smtpUsername
    ) {
        this.mailSender = mailSender;
        this.from = from;
        this.smtpUsername = smtpUsername;
    }

    public void sendPasswordResetCode(String to, String code) {
        // SMTP 미설정(로컬 개발/테스트): 실제 발송을 건너뛴다. 인증번호는 개발 편의를 위해 로그로만 남긴다.
        if (smtpUsername == null || smtpUsername.isBlank()) {
            log.info("SMTP 미설정 — 비밀번호 재설정 메일 발송을 건너뜁니다(로컬). 수신자={}", to);
            log.debug("로컬 비밀번호 재설정 인증번호: to={}, code={}", to, code);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject("[AJT] 비밀번호 재설정 인증번호");
            message.setText("인증번호: " + code + "\n5분 이내에 입력해주세요. 요청하지 않았다면 이 메일을 무시하세요.");
            mailSender.send(message);
        } catch (MailException exception) {
            // 발송 실패를 전파하면 계정 존재가 노출되고(500이 존재하는 이메일에서만 발생) 계약이 깨진다.
            log.warn("비밀번호 재설정 메일 발송 실패 — 무시하고 계속합니다. 수신자={}, 원인={}", to, exception.getMessage());
        }
    }
}
