package com.openclaw.zalosound;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationClassifier {
    private static final String TAG = "ZaloClassifier";

    public enum MessageType {
        DIRECT_1_1,    // Tin nhắn cá nhân 1-1 (bất kỳ ai)
        GROUP,         // Tin nhắn nhóm chat
        CALL,          // Cuộc gọi đến (bỏ qua)
        SYSTEM_IGNORE  // Thông báo hệ thống, đồng bộ, đang chạy ngầm (bỏ qua)
    }

    public static class ClassificationResult {
        private final MessageType type;
        private final String senderName;
        private final String messageText;

        public ClassificationResult(MessageType type, String senderName, String messageText) {
            this.type = type;
            this.senderName = senderName != null ? senderName.trim() : "";
            this.messageText = messageText != null ? messageText.trim() : "";
        }

        public MessageType getType() {
            return type;
        }

        public String getSenderName() {
            return senderName;
        }

        public String getMessageText() {
            return messageText;
        }
    }

    /**
     * Phân tích StatusBarNotification từ Zalo để xác định loại tin nhắn và thông tin người gửi
     */
    public static ClassificationResult classify(StatusBarNotification sbn) {
        if (sbn == null) return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");

        String packageName = sbn.getPackageName();
        if (packageName == null || (!packageName.contains("zalo") && !packageName.equals("com.zing.zalo"))) {
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        Notification notification = sbn.getNotification();
        if (notification == null) return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");

        // 1. Bỏ qua các thông báo Ongoing (chạy ngầm, đồng bộ, upload file)
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "Ignore: Ongoing event");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // 2. Bỏ qua thông báo Group Summary (Android tự tạo thông báo tổng hợp khi có nhiều tin nhắn)
        // Việc này ngăn chặn báo chuông 1-1 ảo khi có nhiều tin nhắn dồn về
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Ignore: Group summary notification bundle");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // 3. Bỏ qua cuộc gọi Zalo
        if (Notification.CATEGORY_CALL.equals(notification.category)) {
            Log.d(TAG, "Ignore: Call category");
            return new ClassificationResult(MessageType.CALL, "", "");
        }

        Bundle extras = notification.extras;
        if (extras == null) return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");

        CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subTextCS = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence summaryCS = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        CharSequence infoCS = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);
        CharSequence convoTitleCS = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);

        String title = titleCS != null ? titleCS.toString().trim() : "";
        String text = textCS != null ? textCS.toString().trim() : "";
        String subText = subTextCS != null ? subTextCS.toString().trim() : "";
        String summary = summaryCS != null ? summaryCS.toString().trim() : "";
        String info = infoCS != null ? infoCS.toString().trim() : "";
        String convoTitle = convoTitleCS != null ? convoTitleCS.toString().trim() : "";

        // Kiểm tra cuộc gọi qua text
        String fullCombined = (title + " " + text + " " + subText).toLowerCase();
        if (fullCombined.contains("cuộc gọi đến") || fullCombined.contains("cuộc gọi nhỡ") ||
            fullCombined.contains("cuộc gọi video") || fullCombined.contains("incoming call")) {
            Log.d(TAG, "Ignore: Call text detected");
            return new ClassificationResult(MessageType.CALL, title, text);
        }

        // Bỏ qua các thông báo hệ thống, khuyến mãi, nhật ký của Zalo
        if (fullCombined.contains("đang chạy ngầm") || fullCombined.contains("sao lưu") ||
            fullCombined.contains("đang đồng bộ") || fullCombined.contains("bảo mật tài khoản") ||
            fullCombined.contains("zalo đang chạy") || fullCombined.contains("thời tiết") ||
            fullCombined.contains("khoảnh khắc") || fullCombined.contains("nhật ký") ||
            fullCombined.contains("gợi ý kết bạn") || fullCombined.contains("đăng nhập trên") ||
            fullCombined.contains("zalopay") || fullCombined.contains("ví qr") ||
            fullCombined.contains("zalo video") || fullCombined.contains("sinh nhật")) {
            Log.d(TAG, "Ignore: System/Promotional notice: " + fullCombined);
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text);
        }

        // Bỏ qua nếu tiêu đề là "Zalo" và không có nội dung người gửi cụ thể
        if (title.equalsIgnoreCase("Zalo") && (text.isEmpty() || text.toLowerCase().contains("tin nhắn mới"))) {
            Log.d(TAG, "Ignore: Generic Zalo header notification");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text);
        }

        // 4. Kiểm tra các cờ hệ thống xác định Group Conversation
        boolean isGroupFlag = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        if (isGroupFlag) {
            Log.d(TAG, "Classified as GROUP via EXTRA_IS_GROUP_CONVERSATION");
            return new ClassificationResult(MessageType.GROUP, title, text);
        }

        if (!convoTitle.isEmpty()) {
            Log.d(TAG, "Classified as GROUP via EXTRA_CONVERSATION_TITLE: " + convoTitle);
            return new ClassificationResult(MessageType.GROUP, convoTitle, text);
        }

        // 5. Phân tích qua SubText, InfoText, SummaryText (Zalo thường để tên nhóm ở đây)
        if (!subText.isEmpty() && !subText.equalsIgnoreCase(title)) {
            Log.d(TAG, "Classified as GROUP via SubText: " + subText);
            return new ClassificationResult(MessageType.GROUP, subText, text);
        }

        if (!summary.isEmpty() && !summary.equalsIgnoreCase(title)) {
            Log.d(TAG, "Classified as GROUP via SummaryText: " + summary);
            return new ClassificationResult(MessageType.GROUP, summary, text);
        }

        if (!info.isEmpty() && !info.equalsIgnoreCase(title)) {
            Log.d(TAG, "Classified as GROUP via InfoText: " + info);
            return new ClassificationResult(MessageType.GROUP, info, text);
        }

        // 6. Phân tích định dạng Tiêu đề / Nội dung đặc trưng của nhóm chat Zalo
        // Nhóm Zalo thường có tiêu đề chứa ngoặc vuông "[Tên Nhóm]" hoặc text có dạng "Tên Người: Tin nhắn"
        if (title.startsWith("[") && title.contains("]")) {
            Log.d(TAG, "Classified as GROUP via Title bracket: " + title);
            return new ClassificationResult(MessageType.GROUP, title, text);
        }

        // Nếu nội dung có cấu trúc "Thành viên: Tin nhắn" trong khi tiêu đề là Tên Nhóm
        if (text.matches("^[^:]{1,30}:\\s+.+")) {
            // Có format người nói trong nhóm gửi tin
            Log.d(TAG, "Classified as GROUP via member prefix in text: " + text);
            return new ClassificationResult(MessageType.GROUP, title, text);
        }

        // 7. Mặc định nếu không có dấu hiệu của nhóm: ĐÂY LÀ TIN NHẮN 1-1 (CÁ NHÂN)
        if (!title.isEmpty() || !text.isEmpty()) {
            Log.d(TAG, "Classified as DIRECT_1_1. Sender: " + title + " | Text: " + text);
            return new ClassificationResult(MessageType.DIRECT_1_1, title, text);
        }

        return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
    }
}
