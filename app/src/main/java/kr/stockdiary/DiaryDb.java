package kr.stockdiary;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

final class DiaryDb extends SQLiteOpenHelper {
    DiaryDb(Context c) { super(c,"diary.db",null,3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trades(scope TEXT,id TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE notes(scope TEXT,id TEXT,note TEXT,updated TEXT,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE profits(scope TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,day))");
        db.execSQL("CREATE TABLE symbol_profits(scope TEXT,day TEXT,idx INTEGER,raw TEXT,PRIMARY KEY(scope,day,idx))");
        db.execSQL("CREATE TABLE holdings(scope TEXT,idx INTEGER,raw TEXT,PRIMARY KEY(scope,idx))");
        db.execSQL("CREATE TABLE meta(scope TEXT PRIMARY KEY,raw TEXT)");
        createLotTables(db); createStartTable(db);
    }
    private static void createStartTable(SQLiteDatabase db) {
        // 종목별 짝 계산 시작 체결. 이 체결보다 앞선 체결은 짝과 손익에서 뺀다.
        db.execSQL("CREATE TABLE pair_starts(scope TEXT,code TEXT,fill_id TEXT,PRIMARY KEY(scope,code))");
    }
    private static void createLotTables(SQLiteDatabase db) {
        // 수동으로 지정한 매수-매도 짝만 저장한다. 나머지는 Pairing이 매번 계산한다.
        db.execSQL("CREATE TABLE pairs(scope TEXT,sell_id TEXT,buy_id TEXT,qty INTEGER,PRIMARY KEY(scope,sell_id,buy_id))");
        db.execSQL("CREATE TABLE opening_lots(scope TEXT,id TEXT,raw TEXT,PRIMARY KEY(scope,id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b) {
        if(b!=3 || a<1 || a>2) throw new IllegalStateException("지원하지 않는 데이터 버전");
        if(a==1) createLotTables(db);
        createStartTable(db);
    }
    void snapshot(String scope,String from,String to,KiwoomApi.Snapshot result) throws Exception { snapshot(scope,from,to,result,null); }
    /** settledThrough: 이 날짜까지는 다시 받을 필요가 없는 확정 기록으로 표시한다. null이면 표시하지 않는다. */
    void snapshot(String scope,String from,String to,KiwoomApi.Snapshot result,String settledThrough) throws Exception {
        List<String[]> covered=covered(scope);
        String until=settledThrough==null || to.compareTo(settledThrough)<0?to:settledThrough;
        if(settledThrough!=null && from.compareTo(until)<=0) covered.add(new String[]{from,until});
        Set<String> ids=new HashSet<>(), dates=new HashSet<>();
        for(int i=0;i<result.trades.length();i++) {
            JSONObject r=result.trades.getJSONObject(i); Records.dateInRange(r.optString("trde_dt"),from,to);
            if(!ids.add(Records.tradeId(r))) throw new IllegalArgumentException("중복 거래번호가 응답되었습니다. 기존 내역을 유지합니다.");
            for(String f:new String[]{"trde_amt","trde_qty_jwa_cnt","trde_unit","cmsn","trde_agri_tax"}) Numbers.parse(r.optString(f));
        }
        for(int i=0;i<result.profits.length();i++) {
            JSONObject r=result.profits.getJSONObject(i); String day=r.optString("dt"); Records.dateInRange(day,from,to);
            if(!dates.add(day)) throw new IllegalArgumentException("손익 일자가 중복되었습니다.");
            for(String f:new String[]{"tdy_sel_pl","tdy_trde_cmsn","tdy_trde_tax"}) {
                if(!r.has(f) || r.optString(f).trim().isEmpty()) throw new IllegalArgumentException("손익 조회에 필요한 값이 누락되었습니다.");
                Numbers.parse(r.getString(f));
            }
        }
        for(int i=0;i<result.symbolProfits.length();i++) {
            JSONObject r=result.symbolProfits.getJSONObject(i); Records.dateInRange(r.optString("dt"),from,to);
            if(r.optString("stk_cd").isEmpty() || r.optString("tdy_sel_pl").trim().isEmpty()) throw new IllegalArgumentException("종목 손익 응답이 누락되었습니다.");
            Numbers.parse(r.getString("tdy_sel_pl"));
        }
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            // 결제 전 체결은 정식 거래로 바뀔 때 settlePending이 지운다. 일지를 옮기려면 그때까지 남아 있어야 한다.
            db.delete("trades","scope=? AND day BETWEEN ? AND ? AND id NOT LIKE 'p:%'",new String[]{scope,from,to});
            db.delete("profits","scope=? AND day BETWEEN ? AND ?",new String[]{scope,from,to});
            db.delete("symbol_profits","scope=? AND day BETWEEN ? AND ?",new String[]{scope,from,to});
            db.delete("holdings","scope=?",new String[]{scope});
            for(int i=0;i<result.trades.length();i++) {
                JSONObject r=result.trades.getJSONObject(i); ContentValues v=new ContentValues();
                v.put("scope",scope); v.put("id",Records.tradeId(r)); v.put("day",r.getString("trde_dt")); v.put("raw",r.toString());
                if(Records.isPending(r)) db.insertWithOnConflict("trades",null,v,SQLiteDatabase.CONFLICT_REPLACE); else db.insertOrThrow("trades",null,v);
            }
            settlePending(db,scope);
            for(int i=0;i<result.profits.length();i++) {
                JSONObject r=result.profits.getJSONObject(i); ContentValues v=new ContentValues();
                v.put("scope",scope); v.put("day",r.getString("dt")); v.put("raw",r.toString()); db.insertOrThrow("profits",null,v);
            }
            for(int i=0;i<result.holdings.length();i++) {
                ContentValues v=new ContentValues(); v.put("scope",scope); v.put("idx",i); v.put("raw",result.holdings.getJSONObject(i).toString()); db.insertOrThrow("holdings",null,v);
            }
            for(int i=0;i<result.symbolProfits.length();i++) {
                JSONObject r=result.symbolProfits.getJSONObject(i); ContentValues v=new ContentValues(); v.put("scope",scope); v.put("day",r.getString("dt")); v.put("idx",i); v.put("raw",r.toString()); db.insertOrThrow("symbol_profits",null,v);
            }
            JSONArray done=new JSONArray(); for(String[] r:Records.merge(covered)) done.put(new JSONArray().put(r[0]).put(r[1]));
            JSONObject meta=new JSONObject().put("balance",result.balance).put("from",from).put("to",to).put("synced",System.currentTimeMillis()).put("covered",done);
            ContentValues v=new ContentValues(); v.put("scope",scope); v.put("raw",meta.toString()); db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** 같은 체결일의 정식 거래가 저장되면 결제 전 체결을 지운다. 일지와 수동 짝은 종목·방향·수량·단가가 같은 정식 거래가 하나뿐일 때만 옮긴다. */
    private static void settlePending(SQLiteDatabase db,String scope) throws Exception {
        List<JSONObject> pending=new ArrayList<>(),settled=new ArrayList<>(); Set<String> days=new HashSet<>();
        try(Cursor c=db.query("trades",new String[]{"raw"},"scope=?",new String[]{scope},null,null,null)) {
            while(c.moveToNext()) { JSONObject r=new JSONObject(c.getString(0)); if(Records.isPending(r)) pending.add(r); else { settled.add(r); days.add(executed(r)); } }
        }
        Set<String> claimed=new HashSet<>();
        for(JSONObject p:pending) {
            if(!days.contains(executed(p))) continue;
            String id=Records.tradeId(p); List<String> matches=new ArrayList<>();
            for(JSONObject s:settled) if(sameFill(p,s)) matches.add(Records.tradeId(s));
            if(matches.size()==1 && claimed.add(matches.get(0))) {
                String[] args={matches.get(0),scope,id};
                db.execSQL("UPDATE OR IGNORE notes SET id=? WHERE scope=? AND id=?",args);
                db.execSQL("UPDATE OR IGNORE pairs SET sell_id=? WHERE scope=? AND sell_id=?",args);
                db.execSQL("UPDATE OR IGNORE pairs SET buy_id=? WHERE scope=? AND buy_id=?",args);
                db.execSQL("UPDATE pair_starts SET fill_id=? WHERE scope=? AND fill_id=?",args);
            }
            db.delete("trades","scope=? AND id=?",new String[]{scope,id});
        }
    }
    private static String executed(JSONObject r) { String d=r.optString("cntr_dt"); return d.matches("\\d{8}")?d:r.optString("trde_dt"); }
    private static boolean sameFill(JSONObject a,JSONObject b) {
        return executed(a).equals(executed(b)) && Pairing.code(a.optString("stk_cd")).equals(Pairing.code(b.optString("stk_cd"))) && Records.side(a).equals(Records.side(b))
            && Numbers.parse(a.optString("trde_qty_jwa_cnt")).compareTo(Numbers.parse(b.optString("trde_qty_jwa_cnt")))==0
            && Numbers.parse(a.optString("trde_unit")).compareTo(Numbers.parse(b.optString("trde_unit")))==0;
    }
    /** 주문 시각을 아직 확인하지 않은 결제 거래를 체결일별로. */
    Map<String,List<JSONObject>> tradesWithoutTime(String scope) throws Exception {
        Map<String,List<JSONObject>> out=new TreeMap<>();
        for(JSONObject r:rows("trades",scope)) if(!Records.isPending(r) && r.optInt("tm_checked")<Records.TIME_CHECK) out.computeIfAbsent(executed(r),k->new ArrayList<>()).add(r);
        return out;
    }
    /** 이미 저장된 거래의 원본 행을 고친다(주문 시각 추가 등). id는 바뀌지 않는다. */
    void updateTrades(String scope,List<JSONObject> trades) throws Exception {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { for(JSONObject r:trades) { ContentValues v=new ContentValues(); v.put("raw",r.toString()); db.update("trades",v,"scope=? AND id=?",new String[]{scope,Records.tradeId(r)}); } db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    List<JSONObject> rows(String table,String scope) throws Exception {
        if(!Arrays.asList("trades","profits","holdings","symbol_profits").contains(table)) throw new IllegalArgumentException();
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"raw"},"scope=?",new String[]{scope},null,null,table.equals("holdings")?"idx ASC":"day DESC")) {
            while(c.moveToNext()) result.add(new JSONObject(c.getString(0)));
        }
        return result;
    }
    JSONObject meta(String scope) throws Exception {
        try(Cursor c=getReadableDatabase().query("meta",new String[]{"raw"},"scope=?",new String[]{scope},null,null,null)) { return c.moveToFirst()?new JSONObject(c.getString(0)):null; }
    }
    /** 저장을 마친 기간. 이전 버전 기록은 마지막 조회 기간 중 저장한 날의 전날까지로 본다. */
    List<String[]> covered(String scope) throws Exception {
        List<String[]> out=new ArrayList<>(); JSONObject meta=meta(scope); if(meta==null) return out;
        JSONArray done=meta.optJSONArray("covered");
        if(done!=null) { for(int i=0;i<done.length();i++) out.add(new String[]{done.getJSONArray(i).getString(0),done.getJSONArray(i).getString(1)}); return out; }
        String synced=Records.day(java.time.Instant.ofEpochMilli(meta.getLong("synced")).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().minusDays(1));
        String until=meta.getString("to").compareTo(synced)<0?meta.getString("to"):synced;
        if(meta.getString("from").compareTo(until)<=0) out.add(new String[]{meta.getString("from"),until});
        return out;
    }
    List<String[]> missing(String scope,String from,String to) throws Exception { return Records.missing(covered(scope),from,to); }
    String note(String scope,String id) {
        try(Cursor c=getReadableDatabase().query("notes",new String[]{"note"},"scope=? AND id=?",new String[]{scope,id},null,null,null)) { return c.moveToFirst()?c.getString(0):""; }
    }
    List<JSONObject> journal(String scope) throws Exception {
        List<JSONObject> entries=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT n.id,n.note,t.raw FROM notes n LEFT JOIN trades t ON t.scope=n.scope AND t.id=n.id WHERE n.scope=? ORDER BY n.updated DESC",new String[]{scope})) {
            while(c.moveToNext()) entries.add(new JSONObject().put("id",c.getString(0)).put("note",c.getString(1)).put("trade",c.isNull(2)?JSONObject.NULL:new JSONObject(c.getString(2))));
        }
        return entries;
    }
    void note(String scope,String id,String note) {
        if(note.trim().isEmpty()) { getWritableDatabase().delete("notes","scope=? AND id=?",new String[]{scope,id}); return; }
        ContentValues v=new ContentValues(); v.put("scope",scope); v.put("id",id); v.put("note",note.trim()); v.put("updated",java.time.Instant.now().toString());
        getWritableDatabase().insertWithOnConflict("notes",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    void clearScope(String scope) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { for(String t:new String[]{"trades","profits","symbol_profits","holdings","notes","meta","pairs","opening_lots","pair_starts"}) db.delete(t,"scope=?",new String[]{scope}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    Map<String,Map<String,Long>> manualPairs(String scope) {
        Map<String,Map<String,Long>> out=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().query("pairs",new String[]{"sell_id","buy_id","qty"},"scope=?",new String[]{scope},null,null,"sell_id,buy_id")) {
            while(c.moveToNext()) out.computeIfAbsent(c.getString(0),k->new LinkedHashMap<>()).put(c.getString(1),c.getLong(2));
        }
        return out;
    }
    /** 매도 한 건의 수동 짝을 통째로 교체한다. 비어 있으면 자동 계산으로 되돌린다. */
    void setPairs(String scope,String sellId,Map<String,Long> buys) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("pairs","scope=? AND sell_id=?",new String[]{scope,sellId});
            for(Map.Entry<String,Long> e:buys.entrySet()) {
                if(e.getValue()<=0) continue;
                ContentValues v=new ContentValues(); v.put("scope",scope); v.put("sell_id",sellId); v.put("buy_id",e.getKey()); v.put("qty",e.getValue()); db.insertOrThrow("pairs",null,v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    List<JSONObject> openingLots(String scope) throws Exception {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("opening_lots",new String[]{"raw"},"scope=?",new String[]{scope},null,null,"id")) { while(c.moveToNext()) out.add(new JSONObject(c.getString(0))); }
        return out;
    }
    /** 조회 기간 이전에 산 물량. id는 open:으로 시작해 거래 id와 겹치지 않는다. */
    String addOpeningLot(String scope,String code,String name,String day,String price,long qty) throws Exception {
        if(day==null || !day.matches("\\d{8}")) throw new IllegalArgumentException("날짜는 8자리로 입력해 주세요.");
        java.time.LocalDate.parse(day,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        if(Numbers.parse(price).signum()<=0 || qty<=0) throw new IllegalArgumentException("가격과 수량을 확인해 주세요.");
        String id="open:"+day+":"+UUID.randomUUID().toString().substring(0,8);
        JSONObject r=new JSONObject().put("id",id).put("stk_cd",code).put("stk_nm",name).put("day",day).put("price",Numbers.parse(price).toPlainString()).put("qty",qty);
        ContentValues v=new ContentValues(); v.put("scope",scope); v.put("id",id); v.put("raw",r.toString()); getWritableDatabase().insertOrThrow("opening_lots",null,v);
        return id;
    }
    void deleteOpeningLot(String scope,String id) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.delete("opening_lots","scope=? AND id=?",new String[]{scope,id}); db.delete("pairs","scope=? AND buy_id=?",new String[]{scope,id}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    /** 종목코드 → 짝 계산 시작 체결 id. */
    Map<String,String> pairStarts(String scope) {
        Map<String,String> out=new HashMap<>();
        try(Cursor c=getReadableDatabase().query("pair_starts",new String[]{"code","fill_id"},"scope=?",new String[]{scope},null,null,null)) { while(c.moveToNext()) out.put(c.getString(0),c.getString(1)); }
        return out;
    }
    /** fillId가 null이면 처음부터 계산으로 되돌린다. */
    void setPairStart(String scope,String code,String fillId) {
        SQLiteDatabase db=getWritableDatabase();
        db.delete("pair_starts","scope=? AND code=?",new String[]{scope,code});
        if(fillId==null) return;
        ContentValues v=new ContentValues(); v.put("scope",scope); v.put("code",code); v.put("fill_id",fillId); db.insertOrThrow("pair_starts",null,v);
    }
    Pairing.Result pairing(String scope) throws Exception {
        List<Pairing.Fill> fills=new ArrayList<>();
        for(JSONObject r:rows("trades",scope)) { Pairing.Fill f=Pairing.fromTrade(r); if(f!=null) fills.add(f); }
        // 시작 체결보다 앞선 거래 체결은 뺀다. 기초 물량은 사용자가 직접 넣은 것이라 남긴다.
        Map<String,Pairing.Fill> byId=new HashMap<>(); for(Pairing.Fill f:fills) byId.put(f.id,f);
        Map<String,Pairing.Fill> starts=new HashMap<>();
        for(Map.Entry<String,String> e:pairStarts(scope).entrySet()) { Pairing.Fill s=byId.get(e.getValue()); if(s!=null && s.code.equals(e.getKey())) starts.put(e.getKey(),s); }
        fills.removeIf(f->starts.containsKey(f.code) && f.before(starts.get(f.code)));
        for(JSONObject r:openingLots(scope)) fills.add(Pairing.fromOpening(r));
        // 빠진 체결을 가리키는 수동 짝은 조용히 무시한다.
        Set<String> kept=new HashSet<>(); for(Pairing.Fill f:fills) kept.add(f.id);
        Map<String,Map<String,Long>> manual=new LinkedHashMap<>();
        for(Map.Entry<String,Map<String,Long>> e:manualPairs(scope).entrySet()) {
            if(!kept.contains(e.getKey())) continue;
            Map<String,Long> buys=new LinkedHashMap<>(); for(Map.Entry<String,Long> b:e.getValue().entrySet()) if(kept.contains(b.getKey())) buys.put(b.getKey(),b.getValue());
            manual.put(e.getKey(),buys);
        }
        return Pairing.run(fills,manual);
    }
}
