package kr.stockdiary;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

/** 매수 묶음(lot) 단위로 매도를 짝짓는다. 수동 지정 우선, 나머지는 매도가보다 싸게 산 최근 매수부터(LIFO). */
public final class Pairing {
    private Pairing() {}

    public static final class Fill {
        /** time: 체결 시각(없으면 주문 시각) HH:mm:ss. 모르면 빈 값. */
        public final String id,code,name,day,side,time; public final long seq,qty; public final BigDecimal price; public final boolean opening;
        public Fill(String id,String code,String name,String day,long seq,String side,BigDecimal price,long qty,boolean opening) { this(id,code,name,day,"",seq,side,price,qty,opening); }
        public Fill(String id,String code,String name,String day,String time,long seq,String side,BigDecimal price,long qty,boolean opening) {
            this.id=id;this.code=code;this.name=name;this.day=day;this.time=time==null?"":time;this.seq=seq;this.side=side;this.price=price;this.qty=qty;this.opening=opening;
        }
        /** 날짜 → 기초 물량 먼저 → 주문 시각(모르면 그날 맨 뒤) → 번호 → id. */
        static final Comparator<Fill> ORDER=Comparator.comparing((Fill f)->f.day).thenComparing(f->!f.opening).thenComparing(f->f.time.isEmpty()?"99":f.time).thenComparingLong(f->f.seq).thenComparing(f->f.id);
        boolean before(Fill o) { return ORDER.compare(this,o)<0; }
    }
    public static final class Pair {
        public final Fill buy,sell; public final long qty; public final boolean manual;
        Pair(Fill buy,Fill sell,long qty,boolean manual) {this.buy=buy;this.sell=sell;this.qty=qty;this.manual=manual;}
        public BigDecimal profit() { return sell.price.subtract(buy.price).multiply(BigDecimal.valueOf(qty)); }
    }
    public static final class Lot {
        public final Fill buy; public final long remaining;
        Lot(Fill buy,long remaining) {this.buy=buy;this.remaining=remaining;}
    }
    public static final class Result {
        public final List<Fill> fills=new ArrayList<>();
        public final List<Pair> pairs=new ArrayList<>();
        public final List<Lot> open=new ArrayList<>();
        /** 매수 기록이 없어 짝을 찾지 못한 매도 수량. */
        public final Map<String,Long> unmatched=new LinkedHashMap<>();
        public final List<String> issues=new ArrayList<>();
        public List<Pair> pairsOf(String sellId) { List<Pair> out=new ArrayList<>(); for(Pair p:pairs) if(p.sell.id.equals(sellId)) out.add(p); return out; }
        public Fill fill(String id) { for(Fill f:fills) if(f.id.equals(id)) return f; return null; }
        public List<Fill> fillsOf(String code) { List<Fill> out=new ArrayList<>(); for(Fill f:fills) if(f.code.equals(code)) out.add(f); return out; }
        /** at 체결 직후 들고 있던 묶음. 그 이후 매도로 정리된 수량은 아직 남은 것으로 본다. */
        public List<Lot> heldAt(Fill at) {
            List<Lot> out=new ArrayList<>();
            for(Fill buy:fills) {
                if(!buy.side.equals("매수") || !buy.code.equals(at.code) || at.before(buy)) continue;
                long left=buy.qty; for(Pair p:pairs) if(p.buy==buy && !at.before(p.sell)) left-=p.qty;
                if(left>0) out.add(new Lot(buy,left));
            }
            out.sort((a,b)->b.buy.price.compareTo(a.buy.price));
            return out;
        }
    }

    public static String code(String raw) {
        String c=raw==null?"":raw.trim();
        return c.matches("A[0-9A-Z]{6}")?c.substring(1):c;
    }
    /** 키움 거래 행 → Fill. 매수·매도가 아니거나 수량이 없으면 null. */
    public static Fill fromTrade(JSONObject r) throws Exception {
        String side=Records.side(r); long qty=Numbers.parse(r.optString("trde_qty_jwa_cnt")).longValueExact();
        if(side.equals("기타") || qty<=0) return null;
        String day=r.optString("cntr_dt"); if(!day.matches("\\d{8}")) day=r.getString("trde_dt");
        String code=code(r.optString("stk_cd"));
        return new Fill(Records.tradeId(r),code,r.optString("stk_nm",code),day,Records.fillTime(r),Numbers.parse(r.optString("trde_no")).longValue(),side,Numbers.parse(r.optString("trde_unit")),qty,false);
    }
    public static Fill fromOpening(JSONObject r) throws Exception {
        String code=code(r.getString("stk_cd"));
        return new Fill(r.getString("id"),code,r.optString("stk_nm",code),r.getString("day"),0,"매수",Numbers.parse(r.getString("price")),r.getLong("qty"),true);
    }

    /** manual: 매도 id → (매수 id → 수량) */
    public static Result run(List<Fill> input,Map<String,Map<String,Long>> manual) {
        Result result=new Result(); result.fills.addAll(input);
        result.fills.sort(Fill.ORDER);
        Map<String,Fill> byId=new HashMap<>(); Map<String,Long> left=new HashMap<>();
        for(Fill f:result.fills) { byId.put(f.id,f); left.put(f.id,f.qty); }
        // 1) 수동 지정 짝을 먼저 확보한다.
        for(Map.Entry<String,Map<String,Long>> e:manual.entrySet()) {
            Fill sell=byId.get(e.getKey()); if(sell==null || !sell.side.equals("매도")) continue;
            for(Map.Entry<String,Long> m:e.getValue().entrySet()) {
                Fill buy=byId.get(m.getKey()); long qty=m.getValue();
                if(buy==null || !buy.side.equals("매수") || !buy.code.equals(sell.code) || !buy.before(sell) || qty<=0 || qty>left.get(buy.id) || qty>left.get(sell.id)) {
                    result.issues.add(sell.name+" "+Numbers.date(sell.day)+" 매도의 수동 짝을 적용하지 못했습니다. 자동으로 계산합니다."); continue;
                }
                left.put(buy.id,left.get(buy.id)-qty); left.put(sell.id,left.get(sell.id)-qty);
                result.pairs.add(new Pair(buy,sell,qty,true));
            }
        }
        // 2) 남은 매도 수량은 시간 순으로 자동 연결한다.
        for(Fill sell:result.fills) {
            if(!sell.side.equals("매도")) continue;
            long need=left.get(sell.id);
            for(int pass=0;pass<2 && need>0;pass++) {
                for(int i=result.fills.indexOf(sell)-1;i>=0 && need>0;i--) {
                    Fill buy=result.fills.get(i);
                    if(!buy.side.equals("매수") || !buy.code.equals(sell.code) || left.get(buy.id)==0) continue;
                    // 첫 번째: 매도가보다 싸게 산 묶음만. 두 번째: 손절 매도라면 최근 매수부터.
                    if(pass==0 && buy.price.compareTo(sell.price)>=0) continue;
                    long qty=Math.min(need,left.get(buy.id));
                    left.put(buy.id,left.get(buy.id)-qty); need-=qty;
                    result.pairs.add(new Pair(buy,sell,qty,false));
                }
            }
            left.put(sell.id,need);
            if(need>0) result.unmatched.put(sell.id,need);
        }
        for(Fill f:result.fills) if(f.side.equals("매수") && left.get(f.id)>0) result.open.add(new Lot(f,left.get(f.id)));
        return result;
    }
}
