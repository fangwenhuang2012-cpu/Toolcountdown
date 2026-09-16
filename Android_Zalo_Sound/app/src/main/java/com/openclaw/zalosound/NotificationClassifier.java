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
    private static final Pattern PATTERN_MEMBER_PREFIX = Pattern.compile("^([^:\\n]{1,45}):\\s*([\\s\\S]*)");
    // Regex dạng "[Tên Người]: Tin nhắn"
    private static final Pattern PATTERN_BRACKET_MEMBER = Pattern.compile("^\\[([^\\]]{1,45})\\]:\\s*([\\s\\S]*)");
    // Regex dạng hành động trong nhóm: "Nam đã gửi một ảnh", "Hoa đã chia sẻ vị trí", v.v.
    private static final Pattern PATTERN_GROUP_ACTION = Pattern.compile(
            "^([^:\\n]{1,45})\\s+(đã gửi|đã chia sẻ|đã tạo|đã ghim|đã đổi|đã thêm|đã rời|đã tham gia|gửi|thu hồi)\\s*([\\s\\S]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    // Regex tiêu đề chứa phân cách nhóm: "Nam > Team Dev", "Nam @ Team Dev", "Nam trong Team Dev", "Nam -> Team Dev"
    private static final Pattern PATTERN_TITLE_GROUP_SEP = Pattern.compile("(\\s+>\\s+|\\s+@\\s+|\\s+->\\s+|\\s+trong\\s+)");

    // Regex bóc tách số đếm tin nhắn chưa đọc trong tiêu đề: "(2) Nhóm", "Nhóm (2)", "3 tin nhắn mới từ..."
    private static final Pattern PATTERN_UNREAD_PREFIX = Pattern.compile("^\\s*\\(?\\[?\\d+\\]?\\)?\\s*");
    private static final Pattern PATTERN_UNREAD_SUFFIX = Pattern.compile("\\s*\\(?\\[?\\d+\\s*(?:tin nhắn|tin nhắn mới|tin|thông báo)?\\]?\\)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_UNREAD_FROM = Pattern.compile("^\\s*\\d+\\s*(?:tin nhắn|tin nhắn mới|thông báo)?\\s*từ\\s*", Pattern.CASE_INSENSITIVE);

    // Regex phát hiện thông báo tổng hợp (Summary / Bundle / Unread Count header) để loại bỏ hoàn toàn duplicate
    private static final Pattern PATTERN_SUMMARY_HEADER = Pattern.compile(
            "^\\s*(?:\\(?\\[?\\d+\\]?\\)?\\s*)?(?:(?:bạn\\s+)?có\\s+)?\\d+\\s*(?:tin nhắn mới|tin nhắn|thông báo mới|thông báo|tin|cuộc trò chuyện|new message|new messages)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Regex phát hiện các chuỗi placeholder tạm thời hoặc thông báo hệ thống của Zalo
    private static final Pattern PATTERN_PLACEHOLDER_TEXT = Pattern.compile(
            "^\\s*(?:đang nhận|đang tải|đang kiểm tra|đang đồng bộ|bạn có tin nhắn|bạn có thông báo|có tin nhắn|có thông báo|tin nhắn mới|thông báo mới|nội dung.*đã.*ẩn|tin nhắn.*đã.*ẩn|nhấp để|chạm để|click to|tap to)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public enum MessageType {
        DIRECT_1_1,    // Tin nhắn cá nhân 1-1 (bất kỳ ai)
        GROUP,         // Tin nhắn nhóm chat
        CALL,          // Cuộc gọi đến (bỏ qua)
        SYSTEM_IGNORE  // Thông báo hệ thống, đồng bộ, summary bundle, đang chạy ngầm (bỏ qua)
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

        // 2. Bỏ qua thông báo Group Summary (Android / Zalo tự tạo bundle khi có nhiều tin nhắn dồn về)
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

        // Kiểm tra cờ group summary bổ sung từ bundle extras
        if (extras.getBoolean("android.isGroupSummary", false)) {
            Log.d(TAG, "Ignore: android.isGroupSummary flag");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

        // Bỏ qua TUYỆT ĐỐI các thông báo dạng InboxStyle / Bundle Summary nhiều dòng
        CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (textLines != null && textLines.length > 1) {
            Log.d(TAG, "Ignore: Multi-line summary bundle (lines count: " + textLines.length + ")");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, "", "");
        }

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
        String fullCombined = (title + " " + text + " " + subText + " " + summary).toLowerCase();
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

        // Bỏ qua nếu text hoặc title khớp với regex Summary / Unread Count Header (ngăn 100% duplicate từ summary stack)
        if (PATTERN_SUMMARY_HEADER.matcher(text).find() || PATTERN_PLACEHOLDER_TEXT.matcher(text).find()) {
            Log.d(TAG, "Ignore: Summary header / placeholder in text -> '" + text + "'");
            return new ClassificationResult(MessageType.SYSTEM_IGNORE, title, text, title);
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
        boolean isGenericTitle = title.isEmpty() || lowerTitle.equals("zalo") || lowerTitle.equals("com.zing.zalo") ||
                lowerTitle.equals("zalo chat") || lowerTitle.equals("tin nhắn mới") || lowerTitle.equals("thông báo mới") ||
                PATTERN_SUMMARY_HEADER.matcher(title).find();

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

        // 4.4 Phân tích tiêu đề chứa phân cách nhóm: "Nam > Team Dev", "Nam @ Team Dev", "Nam trong Team Dev", "Nam -> Team Dev"
        Matcher titleSepMatcher = PATTERN_TITLE_GROUP_SEP.matcher(title);
        if (titleSepMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via Title separator -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.5 Phân tích tiêu đề dạng "[Team Dev] Nam" hoặc "[Team Dev]"
        if (title.startsWith("[") && title.contains("]")) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via Title bracket -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.6 Phân tích SubText, InfoText, SummaryText (Zalo thường gán tên nhóm ở đây)
        if (!subText.isEmpty() && !subText.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(subText);
            Log.d(TAG, "Classified as GROUP via SubText: " + subText + " (Title: " + title + ") -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        if (!summary.isEmpty() && !summary.equalsIgnoreCase(title) && !PATTERN_SUMMARY_HEADER.matcher(summary).find()) {
            String groupName = cleanSenderOrGroupName(summary);
            Log.d(TAG, "Classified as GROUP via SummaryText: " + summary + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        if (!info.isEmpty() && !info.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(info);
            Log.d(TAG, "Classified as GROUP via InfoText: " + info + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.7 Kiểm tra qua MessagingStyle messages bundle array (sender != title)
        if (!messagingSender.isEmpty() && !title.isEmpty() && !messagingSender.equalsIgnoreCase(title)) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via MessagingStyle sender!=title (" + messagingSender + " in " + title + ") -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        // 4.8 Phân tích định dạng Nội dung đặc trưng của nhóm:
        // "Thành viên: Tin nhắn", "[Thành viên]: Tin nhắn"
        Matcher memberPrefixMatcher = PATTERN_MEMBER_PREFIX.matcher(text);
        if (memberPrefixMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via member prefix in text: " + text + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        Matcher bracketMemberMatcher = PATTERN_BRACKET_MEMBER.matcher(text);
        if (bracketMemberMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via bracket member in text: " + text + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
        }

        Matcher actionMatcher = PATTERN_GROUP_ACTION.matcher(text);
        if (actionMatcher.find()) {
            String groupName = cleanSenderOrGroupName(title);
            Log.d(TAG, "Classified as GROUP via group action in text: " + text + " -> " + groupName);
            return new ClassificationResult(MessageType.GROUP, groupName, text, title);
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
     * Chuẩn hóa tên người gửi hoặc tên nhóm (loại bỏ số đếm chưa đọc như '(2)', '[3]', '3 tin nhắn mới từ', và tách tên nhóm từ 'Nam > Team Dev')
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

        // Bỏ hậu tố " (2)", " [3]", " (2 tin nhắn mới)", " [2 tin nhắn]"
        Matcher mSuf = PATTERN_UNREAD_SUFFIX.matcher(s);
        if (mSuf.find()) {
            s = s.substring(0, mSuf.start()).trim();
        }

        // Nếu dạng "Nam > Team Dev" hoặc "Nam trong Team Dev", trích xuất tên nhóm sau phân cách
        Matcher mSep = PATTERN_TITLE_GROUP_SEP.matcher(s);
        if (mSep.find()) {
            String[] parts = PATTERN_TITLE_GROUP_SEP.split(s);
            if (parts.length >= 2 && !parts[parts.length - 1].trim().isEmpty()) {
                s = parts[parts.length - 1].trim();
            }
        }

        // Nếu dạng "[Team Dev] Nam", trích xuất "Team Dev"
        Matcher mBracketHead = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)").matcher(s);
        if (mBracketHead.find()) {
            String inside = mBracketHead.group(1).trim();
            String outside = mBracketHead.group(2).trim();
            if (!outside.isEmpty()) {
                s = inside;
            }
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
            if (combinedAction.contains("thu hồi")) {
                return "[thu hồi]";
            }
            if (!actionDetail.isEmpty()) {
                return actionDetail;
            }
            return combinedAction;
        }

        // 4. Chuẩn hóa các dạng placeholder media đơn lẻ
        String lower = t.toLowerCase();
        if (lower.contains("hình ảnh") || lower.contains("đã gửi 1 ảnh") || lower.contains("đã gửi một ảnh") || lower.contains("đã gửi ảnh") || lower.equals("[hình ảnh]") || lower.equals("[ảnh]") || lower.equals("ảnh") || lower.equals("[photo]")) {
            return "[hình ảnh]";
        }
        if (lower.contains("nhãn dán") || lower.contains("sticker") || lower.contains("biểu cảm") || lower.equals("[nhãn dán]") || lower.equals("[sticker]")) {
            return "[nhãn dán]";
        }
        if (lower.contains("video") || lower.equals("[video]") || lower.equals("[clip]")) {
            return "[video]";
        }
        if (lower.contains("vị trí") || lower.equals("[vị trí]") || lower.equals("[location]")) {
            return "[vị trí]";
        }
        if (lower.contains("tin nhắn thoại") || lower.contains("voice") || lower.contains("ghi âm") || lower.equals("[tin nhắn thoại]")) {
            return "[tin nhắn thoại]";
        }
        if (lower.contains("tập tin") || lower.contains("tệp tin") || lower.contains("file") || lower.equals("[tập tin]") || lower.equals("[tệp tin]") || lower.equals("[file]")) {
            return "[tệp tin]";
        }
        if (lower.contains("thu hồi") || lower.equals("[thu hồi]")) {
            return "[thu hồi]";
        }

        // 5. Bỏ qua mention @all, @tất cả ở đầu tin nhắn
        if (lower.startsWith("@tất cả") || lower.startsWith("@all") || lower.startsWith("@everyone")) {
            t = t.replaceFirst("^(?i)@(tất cả|all|everyone)\\s*", "").trim();
        }

        return t;
    }
}

