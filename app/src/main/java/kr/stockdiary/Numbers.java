package kr.stockdiary;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public final class Numbers {
    private Numbers() {}
    public static BigDecimal parse(String value) {
        if (value == null || value.trim().isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(value.trim().replace(",", ""));
    }
    public static String money(String value) { return format(parse(value)) + "원"; }
    public static String format(BigDecimal n) { return new DecimalFormat("#,##0.##").format(n); }
    public static String date(String d) {
        return d != null && d.matches("\\d{8}") ? d.substring(0,4)+"."+d.substring(4,6)+"."+d.substring(6,8) : d;
    }
}
