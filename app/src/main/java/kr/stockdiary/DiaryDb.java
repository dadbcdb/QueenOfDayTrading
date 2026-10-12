package kr.stockdiary;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

final class DiaryDb extends SQLiteOpenHelper {
    DiaryDb(Context c) { super(c,"diary.db",null,1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trades(scope TEXT,id TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE notes(scope TEXT,id TEXT,note TEXT,updated TEXT,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE profits(scope TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,day))");
        db.execSQL("CREATE TABLE symbol_profits(scope TEXT,day TEXT,idx INTEGER,raw TEXT,PRIMARY KEY(scope,day,idx))");
        db.execSQL("CREATE TABLE holdings(scope TEXT,idx INTEGER,raw TEXT,PRIMARY KEY(scope,idx))");
        db.execSQL("CREATE TABLE meta(scope TEXT PRIMARY KEY,raw TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b) { throw new IllegalStateException("지원하지 않는 데이터 버전"); }
    void snapshot(String scope,String from,String to,KiwoomApi.Snapshot result) throws Exception {
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
            db.delete("trades","scope=? AND day BETWEEN ? AND ?",new String[]{scope,from,to});
            db.delete("profits","scope=? AND day BETWEEN ? AND ?",new String[]{scope,from,to});
            db.delete("symbol_profits","scope=? AND day BETWEEN ? AND ?",new String[]{scope,from,to});
            db.delete("holdings","scope=?",new String[]{scope});
            for(int i=0;i<result.trades.length();i++) {
                JSONObject r=result.trades.getJSONObject(i); ContentValues v=new ContentValues();
                v.put("scope",scope); v.put("id",Records.tradeId(r)); v.put("day",r.getString("trde_dt")); v.put("raw",r.toString()); db.insertOrThrow("trades",null,v);
            }
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
            JSONObject meta=new JSONObject().put("balance",result.balance).put("from",from).put("to",to).put("synced",System.currentTimeMillis());
            ContentValues v=new ContentValues(); v.put("scope",scope); v.put("raw",meta.toString()); db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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
        try { for(String t:new String[]{"trades","profits","symbol_profits","holdings","notes","meta"}) db.delete(t,"scope=?",new String[]{scope}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}
