package service;

import config.EnvironmentConfig;
import jakarta.inject.Singleton;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class OtpEmailService {

    private static final Logger LOGGER = Logger.getLogger(OtpEmailService.class.getName());

    public void send(String recipient, String code, String purpose) {
        String host = EnvironmentConfig.get("MAIL_SMTP_HOST");
        String username = EnvironmentConfig.get("MAIL_SMTP_USERNAME");
        String password = EnvironmentConfig.get("MAIL_SMTP_PASSWORD");
        String from = EnvironmentConfig.get("MAIL_FROM", username);
        int port = Integer.parseInt(EnvironmentConfig.get("MAIL_SMTP_PORT", "587"));
        if (host == null || username == null || password == null || from == null) {
            throw new IllegalStateException("SMTP OTP delivery is not configured");
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", Integer.toString(port));
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", EnvironmentConfig.get("MAIL_SMTP_STARTTLS", "true"));
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            message.setSubject("Movie booking verification code");
                message.setText("Your verification code for " + purpose.toLowerCase()
                    + " is " + code + ". It expires in 5 minutes.");
            Transport.send(message);
            LOGGER.info(() -> "OTP email sent purpose=" + purpose + " destination=" + mask(recipient));
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "OTP email delivery failed purpose=" + purpose
                    + " destination=" + mask(recipient), exception);
            throw new IllegalStateException("OTP email delivery failed", exception);
        }
    }

    private String mask(String email) {
        if (email == null || email.isBlank()) return "unknown";
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}