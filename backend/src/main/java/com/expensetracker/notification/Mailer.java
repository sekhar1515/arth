package com.expensetracker.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends e-mail only when SMTP is configured (SPRING_MAIL_HOST). Otherwise e-mail notifications are reported as unavailable. */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String appUrl;
    private final String host;

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from:}") String from, @Value("${app.public-url:http://localhost:3000}") String appUrl,
                  @Value("${spring.mail.host:}") String host) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.appUrl = appUrl;
    }

    public boolean available() {
        return host != null && !host.isBlank() && sender.getIfAvailable() != null && from != null && !from.isBlank();
    }

    public boolean send(String to, String subject, String body, String link) {
        if (!available()) return false;
        JavaMailSender s = sender.getIfAvailable();
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject("Arth: " + subject);
            m.setText(body + (link != null ? "\n\nOpen in Arth: " + appUrl + link : "") + "\n\nYou can turn these e-mails off in Settings > Notifications.");
            s.send(m);
            return true;
        } catch (RuntimeException e) {
            log.warn("e-mail notification failed: {}", e.getMessage());
            return false;
        }
    }
}
