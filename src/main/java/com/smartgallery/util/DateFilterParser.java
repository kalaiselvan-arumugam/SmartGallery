package com.smartgallery.util;

import com.smartgallery.dto.SearchFilters;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateFilterParser {

    public static class ParsedQuery {
        public String cleanQuery = "";
        public String dateFrom = null;
        public String dateTo = null;
    }

    public static LocalDate NOW = LocalDate.now();

    public static ParsedQuery parse(String input) {
        if (input == null || input.isBlank())
            return new ParsedQuery();
        ParsedQuery res = new ParsedQuery();
        res.cleanQuery = input.trim();
        String lower = res.cleanQuery.toLowerCase();

        // 1. Between A and B | From A to B
        Pattern pBetween = Pattern.compile("(?i)^(.*?)\\s*\\b(?:between)\\s+(.+?)\\s+(?:and)\\s+(.+?)$");
        Matcher mBetween = pBetween.matcher(lower);
        if (mBetween.find()) {
            LocalDate[] d1 = parseDateSpan(mBetween.group(2));
            LocalDate[] d2 = parseDateSpan(mBetween.group(3));
            if (d1 != null && d2 != null) {
                res.dateFrom = d1[0].toString();
                res.dateTo = d2[1].toString();
                res.cleanQuery = input.substring(0, mBetween.start(2) - 8).trim(); // Remove "between ..."
                return clean(res);
            }
        }

        Pattern pFromTo = Pattern.compile("(?i)^(.*?)\\s*\\b(?:from)\\s+(.+?)\\s+(?:to|till)\\s+(.+?)$");
        Matcher mFromTo = pFromTo.matcher(lower);
        if (mFromTo.find()) {
            LocalDate[] d1 = parseDateSpan(mFromTo.group(2));
            LocalDate[] d2 = parseDateSpan(mFromTo.group(3));
            if (d1 != null && d2 != null) {
                res.dateFrom = d1[0].toString();
                res.dateTo = d2[1].toString();
                res.cleanQuery = input.substring(0, mFromTo.start(2) - 5).trim();
                return clean(res);
            }
        }

        // 2. Relational prefixes: After, Since, Before, Until, Till, Up to, In, On,
        // During
        Pattern pRel = Pattern
                .compile("(?i)^(.*?)\\s*\\b(?:after|since|before|until|till|up to|in|on|during)\\s+(.+?)$");
        Matcher mRel = pRel.matcher(lower);
        if (mRel.find()) {
            String prefix = lower.substring(mRel.start(0), mRel.start(2)).trim()
                    .replaceAll(".*\\b(after|since|before|until|till|up to|in|on|during)\\b.*", "$1");
            LocalDate[] d = parseDateSpan(mRel.group(2));
            if (d != null) {
                if (prefix.equals("after") || prefix.equals("since")) {
                    res.dateFrom = d[0].toString();
                } else if (prefix.equals("before") || prefix.equals("until") || prefix.equals("till")
                        || prefix.equals("up to")) {
                    res.dateTo = d[1].toString();
                } else {
                    res.dateFrom = d[0].toString();
                    res.dateTo = d[1].toString();
                }
                res.cleanQuery = mRel.group(1).trim();
                return clean(res);
            }
        }

        // 3. Just a date span at the end of the query (or the whole query is a span)
        // E.g., "Photos 2024", "Pictures Last 7 days"
        // We iterate backwards from the end of the string, splitting by spaces, until
        // parseDateSpan returns something.
        String[] words = lower.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String suffix = lower.substring(lower.indexOf(words[i]));
            LocalDate[] d = parseDateSpan(suffix);
            if (d != null) {
                res.dateFrom = d[0].toString();
                res.dateTo = d[1].toString();
                res.cleanQuery = input.substring(0, lower.indexOf(words[i])).trim();
                return clean(res);
            }
        }

        return clean(res);
    }

    private static ParsedQuery clean(ParsedQuery res) {
        String base = res.cleanQuery.replaceAll("(?i)^(?:photos|images|pictures)?\\s*(?:taken)?\\s*", "").trim();
        base = base.replaceAll("(?i)\\s+(?:photos|images|pictures)$", "").trim();
        if (base.toLowerCase().matches("^(after|before|since|until|till|from|between|in|on)$"))
            base = "";
        res.cleanQuery = base;
        return res;
    }

    public static void applyToFilters(String rawQuery, SearchFilters filters) {
        ParsedQuery parsed = parse(rawQuery);
        if (parsed.dateFrom != null)
            filters.setDateFrom(parsed.dateFrom);
        if (parsed.dateTo != null)
            filters.setDateTo(parsed.dateTo);
    }

    /**
     * Parses a raw phrase into [StartDate, EndDate].
     * Returns null if unparseable.
     */
    public static LocalDate[] parseDateSpan(String input) {
        String s = input.toLowerCase().trim().replaceAll("[^a-z0-9\\s/\\-]", "");
        if (s.isEmpty())
            return null;

        // X days/weeks/months/years
        Matcher m1 = Pattern.compile("^(last|past|previous|next)\\s+(\\d+)\\s+(day|week|month|year)s?$").matcher(s);
        if (m1.matches()) {
            boolean isNext = m1.group(1).equals("next");
            int amt = Integer.parseInt(m1.group(2));
            String unit = m1.group(3);
            LocalDate start = NOW, end = NOW;
            if (unit.equals("day")) {
                if (isNext)
                    end = NOW.plusDays(amt);
                else
                    start = NOW.minusDays(amt);
            } else if (unit.equals("week")) {
                if (isNext)
                    end = NOW.plusWeeks(amt);
                else
                    start = NOW.minusWeeks(amt);
            } else if (unit.equals("month")) {
                if (isNext)
                    end = NOW.plusMonths(amt);
                else
                    start = NOW.minusMonths(amt);
            } else if (unit.equals("year")) {
                if (isNext)
                    end = NOW.plusYears(amt);
                else
                    start = NOW.minusYears(amt);
            }
            return new LocalDate[] { start, end };
        }

        // Relative singles: Today, Tomorrow, Yesterday, Day before yesterday, Day after
        // tomorrow
        if (s.equals("today") || s.equals("now"))
            return new LocalDate[] { NOW, NOW };
        if (s.equals("yesterday"))
            return new LocalDate[] { NOW.minusDays(1), NOW.minusDays(1) };
        if (s.equals("tomorrow"))
            return new LocalDate[] { NOW.plusDays(1), NOW.plusDays(1) };
        if (s.equals("day before yesterday"))
            return new LocalDate[] { NOW.minusDays(2), NOW.minusDays(2) };
        if (s.equals("day after tomorrow"))
            return new LocalDate[] { NOW.plusDays(2), NOW.plusDays(2) };

        // Relative modifiers + keywords
        Matcher m2 = Pattern.compile(
                "^(last|this|next|previous|current)\\s+(week|month|year|quarter|financial year|monday|tuesday|wednesday|thursday|friday|saturday|sunday)$")
                .matcher(s);
        if (m2.matches()) {
            String mod = m2.group(1);
            if (mod.equals("previous"))
                mod = "last";
            if (mod.equals("current"))
                mod = "this";
            String kw = m2.group(2);

            if (kw.equals("year")) {
                int y = NOW.getYear();
                if (mod.equals("last"))
                    y--;
                else if (mod.equals("next"))
                    y++;
                return new LocalDate[] { LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31) };
            } else if (kw.equals("month")) {
                YearMonth ym = YearMonth.from(NOW);
                if (mod.equals("last"))
                    ym = ym.minusMonths(1);
                else if (mod.equals("next"))
                    ym = ym.plusMonths(1);
                return new LocalDate[] { ym.atDay(1), ym.atEndOfMonth() };
            } else if (kw.equals("week")) {
                LocalDate ref = NOW;
                if (mod.equals("last"))
                    ref = ref.minusWeeks(1);
                else if (mod.equals("next"))
                    ref = ref.plusWeeks(1);
                LocalDate start = ref.with(DayOfWeek.MONDAY);
                return new LocalDate[] { start, start.plusDays(6) };
            } else if (kw.equals("quarter")) {
                int q = (NOW.getMonthValue() - 1) / 3 + 1;
                int y = NOW.getYear();
                if (mod.equals("last")) {
                    q--;
                    if (q == 0) {
                        q = 4;
                        y--;
                    }
                } else if (mod.equals("next")) {
                    q++;
                    if (q == 5) {
                        q = 1;
                        y++;
                    }
                }
                int firstMonth = (q - 1) * 3 + 1;
                YearMonth ym1 = YearMonth.of(y, firstMonth);
                YearMonth ym2 = YearMonth.of(y, firstMonth + 2);
                return new LocalDate[] { ym1.atDay(1), ym2.atEndOfMonth() };
            } else if (kw.equals("financial year")) {
                int y = NOW.getYear();
                if (NOW.getMonthValue() < 4)
                    y--; // FY goes Apr-Mar
                if (mod.equals("last"))
                    y--;
                else if (mod.equals("next"))
                    y++;
                return new LocalDate[] { LocalDate.of(y, 4, 1), LocalDate.of(y + 1, 3, 31) };
            } else {
                // Day of week
                DayOfWeek dow = DayOfWeek.valueOf(kw.toUpperCase());
                LocalDate d = NOW;
                if (mod.equals("last"))
                    d = d.with(TemporalAdjusters.previous(dow));
                else if (mod.equals("next"))
                    d = d.with(TemporalAdjusters.next(dow));
                else
                    d = d.with(TemporalAdjusters.nextOrSame(dow));
                return new LocalDate[] { d, d };
            }
        }

        // Quarters: Q1 2025, First quarter of 2025
        Matcher m3 = Pattern
                .compile("^(q[1-4]|first quarter of|second quarter of|third quarter of|fourth quarter of)\\s+(\\d{4})$")
                .matcher(s);
        if (m3.matches()) {
            String p = m3.group(1);
            int y = Integer.parseInt(m3.group(2));
            int q = 1;
            if (p.equals("q2") || p.startsWith("second"))
                q = 2;
            else if (p.equals("q3") || p.startsWith("third"))
                q = 3;
            else if (p.equals("q4") || p.startsWith("fourth"))
                q = 4;
            int firstMonth = (q - 1) * 3 + 1;
            YearMonth ym1 = YearMonth.of(y, firstMonth);
            YearMonth ym2 = YearMonth.of(y, firstMonth + 2);
            return new LocalDate[] { ym1.atDay(1), ym2.atEndOfMonth() };
        }

        // Financial Year: FY 2023-24, Financial year 2023-24
        Matcher m4 = Pattern.compile("^(?:fy|financial year)\\s+(\\d{4})(?:-\\d{2,4})?$").matcher(s);
        if (m4.matches()) {
            int y = Integer.parseInt(m4.group(1));
            return new LocalDate[] { LocalDate.of(y, 4, 1), LocalDate.of(y + 1, 3, 31) };
        }

        // Early/Mid/Late/Beginning/Start/End of [Month/Year]
        Matcher m5 = Pattern.compile("^(early|mid|late|beginning of|start of|end of)\\s+(.+)$").matcher(s);
        if (m5.matches()) {
            String mod = m5.group(1);
            LocalDate[] inner = parseDateSpan(m5.group(2));
            if (inner != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(inner[0], inner[1]) + 1;
                long third = days / 3;
                if (mod.equals("early") || mod.equals("beginning of") || mod.equals("start of")) {
                    return new LocalDate[] { inner[0], inner[0].plusDays(third) };
                } else if (mod.equals("late") || mod.equals("end of")) {
                    return new LocalDate[] { inner[1].minusDays(third), inner[1] };
                } else if (mod.equals("mid")) {
                    return new LocalDate[] { inner[0].plusDays(third), inner[1].minusDays(third) };
                }
            }
        }

        // Exact Date Parsing: Let's extract Month, Day, Year chunks robustly.
        // We will strip 'of' and split
        String cleanDate = s.replaceAll("\\bof\\b", "").replaceAll("\\s+", " ").trim();

        // Formats:
        // YYYY-MM-DD or YYYY/MM/DD
        Matcher mDashYMD = Pattern.compile("^(\\d{4})[\\/-](\\d{2})[\\/-](\\d{2})$").matcher(cleanDate);
        if (mDashYMD.matches()) {
            int y = Integer.parseInt(mDashYMD.group(1));
            int m = Integer.parseInt(mDashYMD.group(2));
            int d = Integer.parseInt(mDashYMD.group(3));
            try {
                LocalDate loc = LocalDate.of(y, m, d);
                return new LocalDate[] { loc, loc };
            } catch (Exception e) {
            }
        }

        // DD/MM/YYYY or MM/DD/YYYY
        Matcher mSlash = Pattern.compile("^(\\d{2})[\\/-](\\d{2})[\\/-](\\d{4})$").matcher(cleanDate);
        if (mSlash.matches()) {
            int p1 = Integer.parseInt(mSlash.group(1));
            int p2 = Integer.parseInt(mSlash.group(2));
            int y = Integer.parseInt(mSlash.group(3));
            int m = p2;
            int d = p1;
            if (p1 > 12) {
                m = p2;
                d = p1;
            } else if (p2 > 12) {
                m = p1;
                d = p2;
            } // Auto-detect DD/MM vs MM/DD based on bounds
            try {
                LocalDate loc = LocalDate.of(y, m, d);
                return new LocalDate[] { loc, loc };
            } catch (Exception e) {
            }
        }

        // Year only
        if (cleanDate.matches("^\\d{4}$")) {
            int y = Integer.parseInt(cleanDate);
            return new LocalDate[] { LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31) };
        }

        // Textual dates
        int year = -1, month = -1, day = -1;
        String[] pts = cleanDate.split(" ");
        for (String p : pts) {
            p = p.replaceAll(",$", "");
            if (p.matches("^\\d{4}$"))
                year = Integer.parseInt(p);
            else if (p.matches("^\\d{1,2}(st|nd|rd|th)?$"))
                day = Integer.parseInt(p.replaceAll("\\D", ""));
            else if (p.matches(
                    "^(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)$")) {
                if (p.startsWith("jan"))
                    month = 1;
                else if (p.startsWith("feb"))
                    month = 2;
                else if (p.startsWith("mar"))
                    month = 3;
                else if (p.startsWith("apr"))
                    month = 4;
                else if (p.startsWith("may"))
                    month = 5;
                else if (p.startsWith("jun"))
                    month = 6;
                else if (p.startsWith("jul"))
                    month = 7;
                else if (p.startsWith("aug"))
                    month = 8;
                else if (p.startsWith("sep"))
                    month = 9;
                else if (p.startsWith("oct"))
                    month = 10;
                else if (p.startsWith("nov"))
                    month = 11;
                else if (p.startsWith("dec"))
                    month = 12;
            }
        }

        if (month != -1 && year != -1) {
            if (day != -1) {
                try {
                    LocalDate loc = LocalDate.of(year, month, day);
                    return new LocalDate[] { loc, loc };
                } catch (Exception e) {
                }
            } else {
                YearMonth ym = YearMonth.of(year, month);
                return new LocalDate[] { ym.atDay(1), ym.atEndOfMonth() };
            }
        }

        return null; // Unable to parse
    }
}
