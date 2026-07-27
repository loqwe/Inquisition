package moe.dazecake.inquisition.utils;

import java.util.List;

public final class GameLogClassifier {
    private static final List<String> NON_PROGRESS_TITLES = List.of(
            "\u8d26\u53f7\u4e34\u65f6\u51b7\u5374",
            "\u4efb\u52a1\u5931\u8d25",
            "\u4ee3\u7406\u8d85\u65f6",
            "\u4ee3\u7406\u5931\u8d25",
            "\u6218\u672f\u91cd\u542f",
            "\u901a\u77e5",
            "\u8bbe\u5907\u79bb\u7ebf",
            "\u4efb\u52a1\u8d85\u65f6",
            "\u89e6\u53d1\u56fe\u7075\u6d4b\u8bd5"
    );

    private GameLogClassifier() {
    }

    public static boolean isValidGameLog(String level, String title, String from) {
        if (from == null || from.isBlank() || "SYSTEM".equalsIgnoreCase(from)) {
            return false;
        }
        if (level == null || !"INFO".equalsIgnoreCase(level)) {
            return false;
        }
        var normalizedTitle = normalizeTitle(title);
        if (normalizedTitle.isBlank()) {
            return false;
        }
        return NON_PROGRESS_TITLES.stream().noneMatch(normalizedTitle::contains);
    }

    public static boolean isLoginLog(String title) {
        var normalizedTitle = normalizeTitle(title);
        return normalizedTitle.contains("\u5f00\u59cb\u767b\u5f55")
                || normalizedTitle.contains("\u767b\u5f55\u6210\u529f");
    }

    public static boolean isSuccessfulLoginLog(String title) {
        return normalizeTitle(title).contains("\u767b\u5f55\u6210\u529f");
    }

    public static boolean isGameStarted(String title) {
        var normalizedTitle = normalizeTitle(title);
        return normalizedTitle.contains("\u767b\u5f55\u6210\u529f")
                || normalizedTitle.contains("\u57fa\u5efa")
                || normalizedTitle.contains("\u4f5c\u6218")
                || normalizedTitle.contains("\u4efb\u52a1\u6536\u96c6")
                || normalizedTitle.contains("\u6d3b\u52a8")
                || normalizedTitle.contains("\u8089\u9e3d");
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceFirst("^\\[\\d{2}-\\d{2}]\\[\\d{2}:\\d{2}]\\s*", "").trim();
    }
}
