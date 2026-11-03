package com.example.notificationservice.service;

import com.example.notificationservice.event.OrderEvent;
import com.example.notificationservice.model.InvoiceNotification;
import com.example.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Service xử lý tạo hóa đơn điện tử và gửi email cho khách hàng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final NotificationRepository notificationRepository;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:no-reply@pharmacy.com}")
    private String fromEmail;

    /**
     * Gửi hóa đơn điện tử về email của khách hàng khi nhận OrderEvent từ Kafka.
     */
    public InvoiceNotification sendInvoiceEmail(OrderEvent event) {
        String recipientEmail = (event.getCustomerEmail() != null && !event.getCustomerEmail().isBlank())
                ? event.getCustomerEmail()
                : "khachhang." + event.getOrderId().toLowerCase() + "@gmail.com";

        String orderTime = (event.getTimestamp() != null)
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimestamp()), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        String subject = "[Hiệu Thuốc Số 1] Hóa đơn điện tử cho đơn hàng " + event.getOrderId();

        String emailContent = String.format("""
                Kính gửi Quý khách,
                
                Cảm ơn Quý khách đã mua thuốc tại Hiệu Thuốc Số 1.
                Thông tin hóa đơn điện tử của Quý khách như sau:
                --------------------------------------------------
                - Mã đơn hàng    : %s
                - Mã thuốc (ID)  : %d
                - Số lượng mua   : %d hộp/vỉ
                - Thời gian mua  : %s
                - Trạng thái     : Đã thanh toán thành công
                --------------------------------------------------
                Hóa đơn này có giá trị pháp lý tương đương hóa đơn giấy.
                Mọi thắc mắc xin liên hệ tổng đài: 1900xxxx.
                
                Trân trọng cảm ơn!
                """, event.getOrderId(), event.getMedicineId(), event.getQuantity(), orderTime);

        boolean emailSentReal = false;
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail);
                message.setTo(recipientEmail);
                message.setSubject(subject);
                message.setText(emailContent);
                mailSender.send(message);
                emailSentReal = true;
                log.info("-> [SMTP Real] Đã gửi email thực tế thành công tới: {}", recipientEmail);
            } catch (Exception ex) {
                log.warn("-> [SMTP Fallback] Không thể gửi qua mail server thật ({}), chuyển sang chế độ giả lập email.", ex.getMessage());
            }
        }

        log.info("--------------------------------------------------------------------------------");
        log.info("   [EMAIL INVOICE DISPATCHER] GỬI HÓA ĐƠN ĐIỆN TỬ");
        log.info("-> Người nhận : {}", recipientEmail);
        log.info("-> Tiêu đề    : {}", subject);
        log.info("-> Trạng thái : {}", emailSentReal ? "ĐÃ GỬI (SMTP)" : "ĐÃ GỬI (SIMULATED EMAIL DISPATCH)");
        log.info("--------------------------------------------------------------------------------");

        InvoiceNotification record = InvoiceNotification.builder()
                .orderId(event.getOrderId())
                .medicineId(event.getMedicineId())
                .quantity(event.getQuantity())
                .recipientEmail(recipientEmail)
                .emailSubject(subject)
                .emailContent(emailContent)
                .status(emailSentReal ? "SENT" : "SIMULATED")
                .sentAt(LocalDateTime.now())
                .build();

        return notificationRepository.save(record);
    }
}
