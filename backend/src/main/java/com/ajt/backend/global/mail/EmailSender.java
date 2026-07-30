package com.ajt.backend.global.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정 인증번호 등 안내 메일을 보내는 공통 발송기입니다.
 *
 * <p>SMTP 접속 계정·비밀번호는 환경변수(spring.mail.*)로만 주입되며 코드/깃에 두지 않습니다.
 * 실제 발송은 유효한 SMTP 설정이 있을 때만 동작하고, 발신 주소는 ajt.mail.from으로 정합니다.
 */
@Service
public class EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailSender(
            JavaMailSender mailSender,
            @Value("${ajt.mail.from:noreply@ajt.local}") String from
    ) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendPasswordResetCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[AJT] 비밀번호 재설정 인증번호");
        message.setText("인증번호: " + code + "\n5분 이내에 입력해주세요. 요청하지 않았다면 이 메일을 무시하세요.");
        mailSender.send(message);
    }
}
