package kr.stockdiary;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
    static LocalDate day(String d) { return LocalDate.parse(d,DateTimeFormatter.BASIC_ISO_DATE); }
    static String day(LocalDate d) { return d.format(DateTimeFormatter.BASIC_ISO_DATE); }
    /** 빠진 기간들을 1년 이하 조회 구간으로 나눈다. 오래된 기간이 서버에서 거절돼도 최근 기록은 남도록 최근 구간부터. */
    public static List<String[]> chunks(List<String[]> gaps) {
        List<String[]> out=new ArrayList<>();
        for(String[] g:gaps) {
            LocalDate start=day(g[0]),end=day(g[1]);
            while(!end.isBefore(start)) {
                LocalDate first=end.minusYears(1).plusDays(1); if(first.isBefore(start)) first=start;
                out.add(new String[]{day(first),day(end)}); end=first.minusDays(1);
            }
        }
        out.sort((a,b)->b[1].compareTo(a[1]));
        return out;
    }
    /** 겹치거나 맞닿은 [시작,끝] 기간을 합친다. */
    public static List<String[]> merge(List<String[]> ranges) {
        List<String[]> sorted=new ArrayList<>(ranges); sorted.sort((a,b)->a[0].compareTo(b[0]));
        List<String[]> out=new ArrayList<>();
        for(String[] r:sorted) {
            String[] last=out.isEmpty()?null:out.get(out.size()-1);
            if(last!=null && !day(r[0]).isAfter(day(last[1]).plusDays(1))) { if(r[1].compareTo(last[1])>0) last[1]=r[1]; }
            else out.add(new String[]{r[0],r[1]});
        }
        return out;
    }
    /** [from,to] 중 covered에 없는 기간. */
    public static List<String[]> missing(List<String[]> covered,String from,String to) {
        List<String[]> gaps=new ArrayList<>(); LocalDate cur=day(from),end=day(to);
        for(String[] r:merge(covered)) {
            LocalDate a=day(r[0]),b=day(r[1]);
            if(b.isBefore(cur)) continue;
            if(a.isAfter(end)) break;
            if(a.isAfter(cur)) gaps.add(new String[]{day(cur),day(a.minusDays(1))});
            cur=b.plusDays(1);
            if(cur.isAfter(end)) return gaps;
        }
        gaps.add(new String[]{day(cur),day(end)});
        return gaps;
    }
    public static String tradeId(JSONObject row) throws Exception {
        if(isPending(row)) return row.getString("pending_id");
        String day=row.getString("trde_dt"), number=row.optString("trde_no");
        if(!day.matches("\\d{8}") || !number.matches("\\d+") || Numbers.parse(number).signum()==0)
            throw new IllegalArgumentException("거래 식별번호를 확인할 수 없습니다. 기존 내역을 유지합니다.");
        // Ledger date + transaction number are the broker's stable record identity.
        return day+":"+number;
    }
    /** 결제 전 체결. id는 p:로 시작해 키움 거래번호 id와 겹치지 않는다. */
    public static boolean isPending(JSONObject row) { return row.optString("pending_id").startsWith("p:"); }
    /** 주문체결내역(kt00007) 행 → kt00015 모양의 결제 전 거래 행. 체결 수량이 없거나 매수·매도가 아니면 null. */
    public static JSONObject pendingTrade(String day,JSONObject r,Map<String,Integer> seen) throws Exception {
        BigDecimal qty=Numbers.parse(r.optString("cntr_qty")),price=Numbers.parse(r.optString("cntr_uv"));
        String kind=r.optString("io_tp_nm").trim(),side=kind.contains("매수")?"매수":kind.contains("매도")?"매도":"기타";
        if(qty.signum()<=0 || side.equals("기타")) return null;
        String order=r.optString("ord_no").trim();
        if(!order.matches("\\d+")) throw new IllegalArgumentException("주문번호를 확인할 수 없습니다.");
        int n=seen.merge(order,1,Integer::sum);
        return new JSONObject().put("pending_id","p:"+day+":"+order+(n>1?":"+n:"")).put("trde_dt",day).put("cntr_dt",day).put("trde_no",order)
            .put("stk_cd",r.optString("stk_cd")).put("stk_nm",r.optString("stk_nm")).put("io_tp_nm",side).put("rmrk_nm",kind)
            .put("trde_qty_jwa_cnt",qty.toPlainString()).put("trde_unit",price.toPlainString()).put("trde_amt",qty.multiply(price).toPlainString())
            .put("stex_nm",r.optString("dmst_stex_tp")).put("ord_tm",time(r.optString("ord_tm"))).put("cntr_tm",time(r.optString("cntr_tm"))).put("tm_checked",TIME_CHECK);
    }
    /** 시각 확인 방식 버전. 2부터 체결 시각(cntr_tm)을 붙인다. 낮은 버전 거래는 다시 확인한다. */
    static final int TIME_CHECK=2;
    /** 정렬·표시에 쓸 시각. 체결 시각을 우선하고 없으면 주문 시각. */
    public static String fillTime(JSONObject row) { String c=time(row.optString("cntr_tm")); return c.isEmpty()?time(row.optString("ord_tm")):c; }
    /** HH:mm:ss 형태만 받는다. */
    static String time(String raw) { String t=raw==null?"":raw.trim(); return t.matches("\\d{2}:\\d{2}(:\\d{2})?")?t:""; }
    /**
     * 결제된 거래에는 체결 시각이 없다. 같은 날 주문체결내역(kt00007)에서 종목·방향·수량·단가가 같은 주문의 주문 시각을 붙인다.
     * 같은 조건의 주문이 여럿이면 거래번호 순서와 주문 시각 순서를 맞춘다. 맞는 주문이 없으면 시각 없이 둔다.
     */
    public static void attachTimes(List<JSONObject> trades,JSONArray orders) throws Exception {
        List<JSONObject> pool=new ArrayList<>(); for(int i=0;i<orders.length();i++) pool.add(orders.getJSONObject(i));
        pool.sort(Comparator.comparing(o->{ String c=time(o.optString("cntr_tm")); return c.isEmpty()?time(o.optString("ord_tm")):c; }));
        List<JSONObject> sorted=new ArrayList<>(trades); sorted.sort(Comparator.comparing(t->Numbers.parse(t.optString("trde_no"))));
        for(JSONObject t:sorted) {
            for(Iterator<JSONObject> it=pool.iterator();it.hasNext();) {
                JSONObject o=it.next(); String kind=o.optString("io_tp_nm"); String side=kind.contains("매수")?"매수":kind.contains("매도")?"매도":"기타";
                if(!Pairing.code(o.optString("stk_cd")).equals(Pairing.code(t.optString("stk_cd"))) || !side.equals(side(t))
                    || Numbers.parse(o.optString("cntr_qty")).compareTo(Numbers.parse(t.optString("trde_qty_jwa_cnt")))!=0
                    || Numbers.parse(o.optString("cntr_uv")).compareTo(Numbers.parse(t.optString("trde_unit")))!=0) continue;
                String tm=time(o.optString("ord_tm")),fill=time(o.optString("cntr_tm"));
                if(!tm.isEmpty()) t.put("ord_tm",tm); if(!fill.isEmpty()) t.put("cntr_tm",fill); it.remove(); break;
            }
            t.put("tm_checked",TIME_CHECK);
        }
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
