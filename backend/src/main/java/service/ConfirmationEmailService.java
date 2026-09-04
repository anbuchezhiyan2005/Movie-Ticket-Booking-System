package service;

public interface ConfirmationEmailService {
    void queueConfirmation(ConfirmationEmail confirmation);
}
