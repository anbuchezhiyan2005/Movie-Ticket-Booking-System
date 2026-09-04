package service;

import config.EnvironmentConfig;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.activation.DataHandler;
import jakarta.inject.Singleton;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.ByteArrayOutputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class SmtpConfirmationEmailService implements ConfirmationEmailService {

    private static final Logger LOGGER = Logger.getLogger(SmtpConfirmationEmailService.class.getName());
    private static final int MAX_ATTEMPTS = 3;

    private final MailSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "booking-confirmation-mail");
        thread.setDaemon(true);
        return thread;
    });

    public SmtpConfirmationEmailService() {
        this(MailSettings.fromEnvironment());
    }

    SmtpConfirmationEmailService(MailSettings settings) {
        this.settings = settings;
    }

    @Override
    public void queueConfirmation(ConfirmationEmail confirmation) {
        executor.submit(() -> sendWithRetry(confirmation));
    }

    private void sendWithRetry(ConfirmationEmail confirmation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                send(confirmation);
                return;
            } catch (RuntimeException error) {
                if (attempt == MAX_ATTEMPTS) {
                    LOGGER.log(Level.SEVERE, "Unable to send booking confirmation for booking "
                            + confirmation.bookingId(), error);
                }
            }
        }
    }

    private void send(ConfirmationEmail confirmation) {
        if (!settings.isConfigured()) {
            LOGGER.warning("Booking confirmation email is not configured; skipping delivery for booking "
                    + confirmation.bookingId());
            return;
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", settings.host());
        properties.put("mail.smtp.port", Integer.toString(settings.port()));
        properties.put("mail.smtp.auth", Boolean.toString(settings.username() != null));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(settings.startTls()));

        Session session = Session.getInstance(properties, settings.username() == null ? null
                : new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(settings.username(), settings.password());
                    }
                });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.from()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(confirmation.recipient()));
            message.setSubject("Movie booking confirmation #" + confirmation.bookingId());

            String confirmationText = formatBody(confirmation);
            String contentId = "booking-confirmation-qr-" + confirmation.bookingId();
            byte[] qrImage = generateQrCodeImage(confirmationText, 250, 250);

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent("<html><body><p>" + escapeHtml(confirmationText).replace("\n", "<br>")
                    + "</p><p>Scan this QR code to view your booking confirmation details:</p>"
                    + "<img src=\"cid:" + contentId + "\" alt=\"Booking confirmation QR code\""
                    + " width=\"250\" height=\"250\"></body></html>", "text/html; charset=UTF-8");

            MimeBodyPart imagePart = new MimeBodyPart();
            imagePart.setDataHandler(new DataHandler(new ByteArrayDataSource(qrImage, "image/png")));
            imagePart.setHeader("Content-ID", "<" + contentId + ">");
            imagePart.setDisposition(MimeBodyPart.INLINE);

            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(htmlPart);
            related.addBodyPart(imagePart);

            MimeBodyPart relatedPart = new MimeBodyPart();
            relatedPart.setContent(related);

            MimeBodyPart plainPart = new MimeBodyPart();
            plainPart.setText(confirmationText, "UTF-8");

            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(plainPart);
            alternative.addBodyPart(relatedPart);
            message.setContent(alternative);
            Transport.send(message);
        } catch (Exception error) {
            throw new IllegalStateException("SMTP delivery failed", error);
        }
    }

    private byte[] generateQrCodeImage(String text, int width, int height) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        return outputStream.toByteArray();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String formatBody(ConfirmationEmail confirmation) {
        return "Hello " + confirmation.customerName() + ",\n\n"
                + "Your movie booking is confirmed.\n\n"
                + "Booking ID: " + confirmation.bookingId() + "\n"
                + "Movie: " + confirmation.movieName() + "\n"
                + "Theatre: " + confirmation.theatreName() + "\n"
                + "Location: " + confirmation.theatreLocation() + "\n"
                + "Show time: " + confirmation.showTime() + "\n"
                + "Seats: " + String.join(", ", confirmation.seats()) + "\n"
                + "Total amount: " + confirmation.totalAmount() + "\n\n"
                + "Thank you for booking with us.";
    }

    record MailSettings(String host, int port, String username, String password,
                        String from, boolean startTls) {
        static MailSettings fromEnvironment() {
            String host = setting("MAIL_SMTP_HOST", "mail.smtp.host", null);
            int port = Integer.parseInt(setting("MAIL_SMTP_PORT", "mail.smtp.port", "587"));
            String username = setting("MAIL_SMTP_USERNAME", "mail.smtp.username", null);
            String password = setting("MAIL_SMTP_PASSWORD", "mail.smtp.password", null);
            String from = setting("MAIL_FROM", "mail.from", username);
            boolean startTls = Boolean.parseBoolean(setting("MAIL_SMTP_STARTTLS", "mail.smtp.starttls", "true"));
            return new MailSettings(host, port, username, password, from, startTls);
        }

        boolean isConfigured() {
            return host != null && !host.isBlank() && from != null && !from.isBlank();
        }

        private static String setting(String environmentName, String propertyName, String defaultValue) {
            String configuredValue = EnvironmentConfig.get(environmentName);
            return configuredValue == null ? EnvironmentConfig.get(propertyName, defaultValue) : configuredValue;
        }
    }
}
