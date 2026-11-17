package com.noteweave.research;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 仅解析原文中无歧义的完整日期，不猜测数字日期的月日顺序。 */
final class ResearchSourceDateGrounding {
    private static final String MONTH = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final List<DatePattern> PATTERNS = List.of(
            new DatePattern(Pattern.compile("(?<![0-9])([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})(?![0-9])"), 1, 2, 3, false),
            new DatePattern(Pattern.compile("(?<![0-9])([0-9]{4})年\\s*([0-9]{1,2})月\\s*([0-9]{1,2})日"), 1, 2, 3, false),
            new DatePattern(Pattern.compile("(?i)(?<![a-z0-9])([0-9]{1,2})(?:st|nd|rd|th)?[\\s-]+(" + MONTH + ")\\.?[\\s,-]+([0-9]{4})(?![0-9])"), 3, 2, 1, true),
            new DatePattern(Pattern.compile("(?i)(?<![a-z0-9])(" + MONTH + ")\\.?[\\s-]+([0-9]{1,2})(?:st|nd|rd|th)?,?[\\s-]+([0-9]{4})(?![0-9])"), 3, 1, 2, true));
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    static boolean matches(String quote, LocalDate expected) {
        if (quote == null || expected == null) return false;
        for (DatePattern format : PATTERNS) {
            var matcher = format.pattern().matcher(quote);
            while (matcher.find()) {
                try {
                    int month = format.namedMonth()
                            ? MONTHS.indexOf(matcher.group(format.month()).substring(0, 3).toLowerCase(Locale.ROOT)) + 1
                            : Integer.parseInt(matcher.group(format.month()));
                    LocalDate actual = LocalDate.of(Integer.parseInt(matcher.group(format.year())), month,
                            Integer.parseInt(matcher.group(format.day())));
                    if (actual.equals(expected)) return true;
                } catch (DateTimeException | NumberFormatException ignored) {
                    // 非法日历日期不作为日期依据，继续检查原文中其他完整日期。
                }
            }
        }
        return false;
    }

    private record DatePattern(Pattern pattern, int year, int month, int day, boolean namedMonth) { }
}
