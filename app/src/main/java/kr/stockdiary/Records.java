package kr.stockdiary;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class Records {
    private Records() {}
    public static String scope(String key, boolean mock) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(((mock?"mock:":"real:")+key).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format("%02x",b)); return out.toString();
    }
    public static void dateInRange(String date,String from,String to) {
        if(date==null || !date.matches("\\d{8}")) throw new IllegalArgumentException("조회 결과에 날짜가 없습니다.");
        LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        if(date.compareTo(from)<0 || date.compareTo(to)>0) throw new IllegalArgumentException("조회 기간 밖의 응답입니다. 기존 내역을 유지합니다.");
    }
    public static String tradeId(JSONObject row) throws Exception {
        String day=row.getString("trde_dt"), number=row.optString("trde_no");
        if(!day.matches("\\d{8}") || !number.matches("\\d+") || Numbers.parse(number).signum()==0)
            throw new IllegalArgumentException("거래 식별번호를 확인할 수 없습니다. 기존 내역을 유지합니다.");
        // Ledger date + transaction number are the broker's stable record identity.
        return day+":"+number;
    }
    public static String side(JSONObject row) {
        String explicit=row.optString("io_tp_nm").trim();
        if(explicit.equals("매수") || explicit.equals("매도")) return explicit;
        String desc=row.optString("rmrk_nm");
        if(desc.contains("매수")) return "매수";
        if(desc.contains("매도")) return "매도";
        return "기타";
    }
}
