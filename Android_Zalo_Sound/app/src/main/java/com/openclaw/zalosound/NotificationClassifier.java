package com.openclaw.zalosound;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationClassifier {
    private static final String TAG = "ZaloClassifier";

    // Regex phát hiện định dạng người gửi trong nhóm chat Zalo: "Tên Người: Tin nhắn"
    private static final Pattern PATTERN_MEMBER_PREFIX = Pattern.compile("^([^:\\n]{1,35}):\\s*([\\s\\S]*)");
    // Regex dạng "[Tên Người]: Tin nhắn"
    private static final Pattern PATTERN_BRACKET_MEMBER = Pattern.compile("^\\[([^\\]]{1,35})\\]:\\s*([\\s\\S]*)");
    // Regex dạng hành động trong nhóm: "Nam đã gửi một ảnh", "Hoa đã chia sẻ vị trí", v.v.
    private static final Pattern PATTERN_GROUP_ACTION = Pattern.compile(
            "^([^:\\n]{1,35})\\s+(đã gửi|đã chia sẻ|đã tạo|đã ghim|đã đổi|đã thêm|đã rời|đã tham gia|gửi|thu hồi)\\s*([\\s\\S]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    // Regex tiêu đề chứa phân cách nhóm: "Nam > Team Dev", "Nam @ Team Dev", "Nam trong Team Dev"
    private static final Pattern PATTERN_TITLE_GROUP_SEP = Pattern.compile("(\\s+>\\s+|\\s+@\\s+|\\s+->\\s+|\\s+trong\\s+)");

    // Regex bóc tách số đếm tin nhắn chưa đọc trong tiêu đề: "(2) Nhóm", "Nhóm (2)", "3 tin nhắn mới từ..."
    private static final Pattern PATTERN_UNREAD_PREFIX = Pattern.compile("^\\s*\\(?\\[?\\d+\\]?\\)?\\s*");
    private static final Pattern PATTERN_UNREAD_SUFFIX = Pattern.compile("\\s*\\(?\\[?\\d+\\s*(?:tin nhắn|tin nhắn mới|tin)?\\]?\\)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_UNREAD_FROM = Pattern.compile("^\\s*\\d+\\s*(?:tin nhắn|tin nhắn mới)?\\s*từ\\s*", Pattern.CASE_INSENSITIVE);

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
        private final String rawTitle;

        public ClassificationResult(MessageType type, String senderName, String messageText, String rawTitle) {
            this.type = type;
            this.senderName = senderName != null ? senderName.trim() : "";
            this.messageText = messageText != null ? messageText.trim() : "";
            this.rawTitle = rawTitle != null ? rawTitle.trim() : "";
        }

        public ClassificationResult(MessageType type, String senderName, String messageText) {
            this(type, senderName, messageText, senderName);
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

        public String getRawTitle() {
            return rawTitle;
        }
    }

    /**
     * Phân tích StatusBarNotification từ Zalo để xác định chuẩn xác loại tin nhắn và thông tin người gửi/nhóm
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

        // 2. Bỏ qua thông báo Group Summary (Android tự tạo bundle khi có nhiều tin nhắn dồn về)
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Ignore: Group summary notification bundle");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // 3. Bỏ qua Foreground Service / Progress notifications
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            Log.d(TAG, "Ignore: Foreground service notification");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // 4. Bỏ qua cuộc gọi Zalo
        if (Notification.CATEGORY_CALL.equals(notification.category)) {
            Log.d(TAG, "Ignore: Call category");
            return new ClassificationResult(MessageType.CALL, "", "");
        }

        if (Notification.CATEGORY_SYSTEM.equals(notification.category) ||
            Notification.CATEGORY_SERVICE.equals(notification.category) ||
            Notification.CATEGORY_PROGRESS.equals(notification.category)) {
            Log.d(TAG, "Ignore: System/Service/Progress category");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        Bundle extras = notification.extras;
        if (extras == null) return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");

        CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCS = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence subTextCS = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence summaryCS = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        CharSequence infoCS = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);
        CharSequence convoTitleCS = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence hiddenConvoCS = extras.getCharSequence("android.hiddenConversationTitle");

        String title = titleCS != null ? titleCS.toString().trim() : "";
        String text = textCS != null ? textCS.toString().trim() : (bigTextCS != null ? bigTextCS.toString().trim() : "");
        String subText = subTextCS != null ? subTextCS.toString().trim() : "";
        String summary = summaryCS != null ? summaryCS.toString().trim() : "";
        String info = infoCS != null ? infoCS.toString().trim() : "";
        String convoTitle = convoTitleCS != null ? convoTitleCS.toString().trim() : (hiddenConvoCS != null ? hiddenConvoCS.toString().trim() : "");
        String tag = sbn.getTag() != null ? sbn.getTag().trim().toLowerCase() : "";

        // Kiểm tra cuộc gọi qua text
        String fullCombined = (title + " " + text + " " + subText).toLowerCase();
        if (fullCombined.contains("cuộc gọi đến") || fullCombined.contains("cuộc gọi nhỡ") ||
            fullCombined.contains("cuộc gọi video") || fullCombined.contains("incoming call") ||
            fullCombined.contains("missed call")) {
            Log.d(TAG, "Ignore: Call text detected");
            return new ClassificationResult(MessageType.CALL, title, text, title);
        }

        // Bỏ qua các thông báo hệ thống, khuyến mãi, nhật ký của Zalo
        if (fullCombined.contains("đang chạy ngầm") || fullCombined.contains("sao lưu") ||
            fullCombined.contains("đang đồng bộ") || fullCombined.contains("bảo mật tài khoản") ||
            fullCombined.contains("zalo đang chạy") || fullCombined.contains("thời tiết") ||
            fullCombined.contains("khoảnh khắc") || fullCombined.contains("nhật ký") ||
            fullCombined.contains("gợi ý kết bạn") || fullCombined.contains("đăng nhập trên") ||
            fullCombined.contains("zalopay") || fullCombined.contains("ví qr") ||
            fullCombined.contains("zalo video") || fullCombined.contains("sinh nhật") ||
            fullCombined.contains("kết bạn mới") || fullCombined.contains("tin nổi bật") ||
            fullCombined.contains("nhắc nhở") || fullCombined.contains("official account") ||
            fullCombined.contains("zalo oa")) {
            Log.d(TAG, "Ignore: System/Promotional notice: " + fullCombined);
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text, title);
        }

        // Bỏ qua Android notification bundle summary (danh sách nhiều dòng từ nhiều chat)
        CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (textLines != null && textLines.length > 1) {
            String lowerTitle = title.toLowerCase();
            if (lowerTitle.equals("zalo") || lowerTitle.contains("cuộc trò chuyện") || lowerTitle.contains("tin nhắn")) {
                Log.d(TAG, "Ignore: Multi-line inbox summary bundle");
                return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text, title);
            }
        }

        // Trích xuất thông tin người gửi/nội dung từ MessagingStyle nếu có
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        String messagingSender = "";
        String messagingText = "";
        if (messages != null && messages.length > 0) {
            Parcelable lastMsg = messages[messages.length - 1];
            if (lastMsg instanceof Bundle) {
                Bundle msgBundle = (Bundle) lastMsg;
                CharSequence senderPerson = msgBundle.getCharSequence("sender");
                CharSequence msgContent = msgBundle.getCharSequence("text");
                if (senderPerson != null && !TextUtils.isEmpty(senderPerson)) {
                    messagingSender = senderPerson.toString().trim();
                }
                if (msgContent != null && !TextUtils.isEmpty(msgContent)) {
                    messagingText = msgContent.toString().trim();
                }
            }
        }

        // Bỏ qua tiêu đề placeholder generic như "Zalo", "Tin nhắn mới", "Thông báo mới" nếu không có tin nhắn cụ thể
        String lowerTitle = title.toLowerCase();
        String lowerText = text.toLowerCase();
        boolean isGenericTitle = title.isEmpty() || lowerTitle.equals("zalo") || lowerTitle.equals("com.zing.zalo") ||
                lowerTitle.equals("zalo chat") || lowerTitle.equals("tin nhắn mới") || lowerTitle.equals("thông báo mới");

        if (isGenericTitle) {
            if (!messagingSender.isEmpty() && !messagingSender.equalsIgnoreCase("zalo")) {
                // Sử dụng sender từ MessagingStyle
                title = messagingSender;
                if (!messagingText.isEmpty()) {
                    text = messagingText;
                }
            } else {
                Log.d(TAG, "Ignore: Generic Zalo header notification without sender (Title: " + title + ", Text: " + text + ")");
                return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text, title);
            }
        }

        // Bỏ qua nếu text là nội dung tạm thời/placeholder trước khi tải xong
        if (lowerText.equals("bạn có tin nhắn mới") || lowerText.equals("có tin nhắn mới") ||
            lowerText.equals("tin nhắn mới") || lowerText.equals("đang nhận tin nhắn...") ||
            lowerText.equals("đang kiểm tra tin nhắn...") || lowerText.equals("bạn có thông báo mới") ||
            lowerText.equals("đang nhận...") || lowerText.equals("đang tải...") ||
            (lowerText.contains("tin nhắn mới") && lowerText.contains("cuộc trò chuyện"))) {
            Log.d(TAG, "Ignore: Placeholder text message: " + text);
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text, title);
        }

        // Bỏ qua nếu rỗng hoàn toàn cả tiêu đề và nội dung
        if (title.isEmpty() && text.isEmpty()) {
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // ==================== NHẬN DIỆN TIN NHẮN NHÓM (GROUP DETECTION) ====================

        // 4.1 Cờ hệ thống xác định Group Conversation
        boolean isGroupFlag = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        if (isGroupFlag) {
            String groupName = !convoTitle.isEmpty() ? convoTitle : (!subText.isEmpty() ? subText : (!title.isEmpty() ? title : summary));
            groupName = cleanSenderOrGroupName(groupName);
            Log.d(TAG, "Classified as GROUP via EXTRA_IS_GROUP_CONVERSATION -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.2 Có EXTRA_CONVERSATION_TITLE (Tiêu đề cuộc trò chuyện nhóm)
        if (!convoTitle.isEmpty()) {
            String groupName = cleanSenderOrGroupName(convoTitle);
            Log.d(TAG, "Classified as GROUP via EXTRA_CONVERSATION_TITLE: " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.3 Notification Tag chứa định danh nhóm của Zalo (ví dụ: g_..., group_..., chat_group_...)
        if (tag.startsWith("g_") || tag.startsWith("group") || tag.contains("group_") || tag.contains("chat_group") || tag.contains("@g.us")) {
            String groupName = !subText.isEmpty() ? subText : title;
            groupName = cleanSenderOrGroupName(groupName);
            Log.d(TAG, "Classified as GROUP via Tag identifier: " + tag + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.4 Kiểm tra qua MessagingStyle messages bundle array
        if (!messagingSender.isEmpty() && !title.isEmpty() && !messagingSender.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via MessagingStyle sender!=title (" + messagingSender + " in " + title + ")");
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.5 Phân tích SubText, InfoText, SummaryText (Zalo thường gán tên nhóm ở đây)
        if (!subText.isEmpty() && !subText.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(subText);
            Log.d(TAG, "Classified as GROUP via SubText: " + subText + " (Title: " + title + ")");
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        if (!summary.isEmpty() && !summary.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(summary);
            Log.d(TAG, "Classified as GROUP via SummaryText: " + summary);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        if (!info.isEmpty() && !info.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(info);
            Log.d(TAG, "Classified as GROUP via InfoText: " + info);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.6 Phân tích định dạng Tiêu đề đặc trưng của nhóm:
        // "[Tên Nhóm]", "Tên Người > Tên Nhóm", "Tên Người @ Tên Nhóm", "Tên Người trong Tên Nhóm"
        if (title.startsWith("[") && title.contains("]")) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via Title bracket: " + title);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        Matcher titleSepMatcher = PATTERN_TITLE_GROUP_SEP.matcher(title);
        if (titleSepMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via Title separator: " + title);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.7 Phân tích định dạng Nội dung đặc trưng của nhóm:
        // "Thành viên: Tin nhắn", "[Thành viên]: Tin nhắn"
        Matcher memberPrefixMatcher = PATTERN_MEMBER_PREFIX.matcher(text);
        if (memberPrefixMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via member prefix in text: " + text);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        Matcher bracketMemberMatcher = PATTERN_BRACKET_MEMBER.matcher(text);
        if (bracketMemberMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via bracket member in text: " + text);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        Matcher actionMatcher = PATTERN_GROUP_ACTION.matcher(text);
        if (actionMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via group action in text: " + text);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.8 Kiểm tra text lines nếu có tiền tố thành viên
        if (textLines != null && textLines.length > 0) {
            for (CharSequence line : textLines) {
                if (line != null && PATTERN_MEMBER_PREFIX.matcher(line.toString().trim()).find()) {
                    String groupName = cleanSenderOrGroupName(title);
                    Log.d(TAG, "Classified as GROUP via EXTRA_TEXT_LINES member prefix");
                    return new ClassificationResult(MessageType.GROUP, groupName, text, title);
                }
            }
        }

        // ==================== TIN NHẮN CÁ NHÂN (1-1) ====================
        if (!title.isEmpty()) {
            String cleanSender = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as DIRECT_1_1. Sender: " + cleanSender + " | Text: " + text);
            return new ClassificationResult(MessageType.DIRECT_1_1, cleanSender, text, title);
        }

        return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
    }

    /**
     * Chuẩn hóa tên người gửi hoặc tên nhóm (loại bỏ số đếm chưa đọc như '(2)', '[3]', '3 tin nhắn mới từ')
     */
    public static String cleanSenderOrGroupName(String rawName) {
        if (rawName == null) return "";
        String s = rawName.trim();
        if (s.isEmpty()) return "";

        // Bỏ tiền tố "3 tin nhắn mới từ "
        Matcher mFrom = PATTERN_UNREAD_FROM.matcher(s);
        if (mFrom.find()) {
            s = s.substring(mFrom.end()).trim();
        }

        // Bỏ tiền tố "(2) ", "[2] "
        Matcher mPre = PATTERN_UNREAD_PREFIX.matcher(s);
        if (mPre.find()) {
            s = s.substring(mPre.end()).trim();
        }

        // Bỏ hậu tố " (2)", " [3]", " (2 tin nhắn mới)"
        Matcher mSuf = PATTERN_UNREAD_SUFFIX.matcher(s);
        if (mSuf.find()) {
            s = s.substring(0, mSuf.start()).trim();
        }

        // Bỏ ngoặc vuông bao quanh nếu có: "[Nhóm Dev]" -> "Nhóm Dev"
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }

        return s.isEmpty() ? rawName.trim() : s;
    }

    /**
     * Chuẩn hóa nội dung tin nhắn để phục vụ lọc trùng lặp ổn định
     * (loại bỏ tiền tố người gửi 'Nam: ', chuẩn hóa các hành động 'Nam đã gửi 1 ảnh' -> '[hình ảnh]')
     */
    public static String cleanMessageContent(String rawText) {
        if (rawText == null) return "";
        String t = rawText.trim();
        if (t.isEmpty()) return "";

        // 1. Loại bỏ tiền tố "Tên: "
        Matcher m1 = PATTERN_MEMBER_PREFIX.matcher(t);
        if (m1.find()) {
            t = m1.group(2).trim();
        } else {
            // 2. Loại bỏ tiền tố "[Tên]: "
            Matcher m2 = PATTERN_BRACKET_MEMBER.matcher(t);
            if (m2.find()) {
                t = m2.group(2).trim();
            }
        }

        // 3. Chuẩn hóa các hành động nhóm: "Nam đã gửi một ảnh", "Hoa đã gửi 1 sticker", v.v.
        Matcher mAction = PATTERN_GROUP_ACTION.matcher(t);
        if (mAction.find()) {
            String actionVerb = mAction.group(2) != null ? mAction.group(2).toLowerCase() : "";
            String actionDetail = mAction.group(3) != null ? mAction.group(3).toLowerCase() : "";
            String combinedAction = (actionVerb + " " + actionDetail).trim();

            if (combinedAction.contains("ảnh") || combinedAction.contains("hình") || combinedAction.contains("photo") || combinedAction.contains("image")) {
                return "[hình ảnh]";
            }
            if (combinedAction.contains("nhãn dán") || combinedAction.contains("sticker") || combinedAction.contains("icon") || combinedAction.contains("biểu cảm")) {
                return "[nhãn dán]";
            }
            if (combinedAction.contains("video") || combinedAction.contains("clip")) {
                return "[video]";
            }
            if (combinedAction.contains("vị trí") || combinedAction.contains("location") || combinedAction.contains("tọa độ")) {
                return "[vị trí]";
            }
            if (combinedAction.contains("thoại") || combinedAction.contains("voice") || combinedAction.contains("audio") || combinedAction.contains("ghi âm")) {
                return "[tin nhắn thoại]";
            }
            if (combinedAction.contains("tệp") || combinedAction.contains("tập tin") || combinedAction.contains("file") || combinedAction.contains("tài liệu")) {
                return "[tệp tin]";
            }
            if (!actionDetail.isEmpty()) {
                return actionDetail;
            }
            return combinedAction;
        }

        // 4. Chuẩn hóa các dạng placeholder media đơn lẻ
        String lower = t.toLowerCase();
        if (lower.contains("hình ảnh") || lower.contains("đã gửi 1 ảnh") || lower.contains("đã gửi một ảnh") || lower.equals("[hình ảnh]") || lower.equals("[ảnh]")) {
            return "[hình ảnh]";
        }
        if (lower.contains("nhãn dán") || lower.contains("sticker") || lower.equals("[nhãn dán]") || lower.equals("[sticker]")) {
            return "[nhãn dán]";
        }
        if (lower.contains("video") || lower.equals("[video]")) {
            return "[video]";
        }
        if (lower.contains("vị trí") || lower.equals("[vị trí]") || lower.equals("[location]")) {
            return "[vị trí]";
        }
        if (lower.contains("tin nhắn thoại") || lower.contains("voice") || lower.equals("[tin nhắn thoại]")) {
            return "[tin nhắn thoại]";
        }
        if (lower.contains("tập tin") || lower.contains("tệp tin") || lower.equals("[tập tin]") || lower.equals("[file]")) {
            return "[tệp tin]";
        }

        return t;
    }
}
