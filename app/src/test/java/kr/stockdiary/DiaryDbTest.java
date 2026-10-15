package kr.stockdiary;

import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.util.Collections;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class DiaryDbTest {
    private DiaryDb db;
    @Before public void setup() {RuntimeEnvironment.getApplication().deleteDatabase("diary.db");db=new DiaryDb(RuntimeEnvironment.getApplication());}
    @After public void close() {db.close();}
    private KiwoomApi.Snapshot snapshot(String day,String no,String fee) throws Exception {
        JSONObject trade=new JSONObject().put("trde_dt",day).put("trde_no",no).put("cmsn",fee).put("stk_nm","삼성전자");
        JSONObject profit=new JSONObject().put("dt",day).put("tdy_sel_pl","14000").put("tdy_trde_cmsn",fee).put("tdy_trde_tax","840");
        JSONObject symbol=new JSONObject().put("dt",day).put("stk_cd","A005930").put("stk_nm","삼성전자").put("tdy_sel_pl","14000");
        return new KiwoomApi.Snapshot(new JSONArray().put(trade),new JSONArray().put(profit),new JSONArray(),new JSONObject(),new JSONArray().put(symbol));
    }
    @Test public void repeatedSyncDoesNotDuplicateOrLoseNotesAndKeepsOtherDates() throws Exception {
        db.snapshot("real","20261001","20261002",snapshot("20261002","000000001","10"));
        db.note("real","20261002:000000001","목표가 도달 후 매도");
        db.snapshot("real","20261003","20261009",snapshot("20261006","000000002","20"));
        db.snapshot("real","20261001","20261002",snapshot("20261002","000000001","30"));
        assertEquals(2,db.rows("trades","real").size());assertEquals(2,db.rows("profits","real").size());
        assertEquals(2,db.rows("symbol_profits","real").size());
        assertEquals("목표가 도달 후 매도",db.note("real","20261002:000000001"));
        assertEquals("30",db.rows("trades","real").get(1).getString("cmsn"));
    }
    @Test public void failedValidationPreservesExistingSnapshotAndNotes() throws Exception {
        db.snapshot("real","20261001","20261009",snapshot("20261002","000000001","10"));
        KiwoomApi.Snapshot bad=snapshot("20261002","000000002","20");bad.profits.getJSONObject(0).put("tdy_sel_pl","bad-number");
        try {db.snapshot("real","20261001","20261009",bad);fail("Invalid snapshot accepted");}catch(IllegalArgumentException expected){}
        assertEquals("000000001",db.rows("trades","real").get(0).getString("trde_no"));assertEquals("14000",db.rows("profits","real").get(0).getString("tdy_sel_pl"));
    }
    @Test public void scopesAndDeletionKeepOtherAccountIsolated() throws Exception {
        db.snapshot("real","20261001","20261009",snapshot("20261002","000000001","10"));
        db.snapshot("demo","20261001","20261009",snapshot("20261002","000000001","20"));
        db.note("real","20261002:000000001","real journal");db.note("demo","20261002:000000001","demo journal");
        db.clearScope("demo");assertEquals(1,db.rows("trades","real").size());assertEquals(0,db.rows("trades","demo").size());assertEquals("real journal",db.note("real","20261002:000000001"));
    }
    @Test public void manualPairsAndOpeningLotsSurviveSyncAndClearWithScope() throws Exception {
        JSONObject sell=new JSONObject().put("trde_dt","20261002").put("trde_no","000000001").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","매도").put("trde_qty_jwa_cnt","2").put("trde_unit","70,000");
        KiwoomApi.Snapshot s=new KiwoomApi.Snapshot(new JSONArray().put(sell),new JSONArray(),new JSONArray(),new JSONObject());
        db.snapshot("real","20261001","20261009",s);
        assertEquals(Long.valueOf(2),db.pairing("real").unmatched.get("20261002:000000001"));
        String lot=db.addOpeningLot("real","005930","삼성전자","20260901","65000",2);
        assertTrue(db.pairing("real").unmatched.isEmpty());
        db.setPairs("real","20261002:000000001",Collections.singletonMap(lot,2L));
        db.snapshot("real","20261001","20261009",s);
        Pairing.Result r=db.pairing("real");
        assertEquals(1,r.pairs.size());assertTrue(r.pairs.get(0).manual);assertEquals(new java.math.BigDecimal("10000"),r.pairs.get(0).profit());
        db.setPairs("real","20261002:000000001",Collections.emptyMap());assertFalse(db.pairing("real").pairs.get(0).manual);
        db.clearScope("real");assertTrue(db.openingLots("real").isEmpty());assertTrue(db.manualPairs("real").isEmpty());
    }
    @Test public void pairStartDropsEarlierFillsOfThatSymbolOnly() throws Exception {
        JSONArray trades=new JSONArray()
            .put(new JSONObject().put("trde_dt","20260601").put("trde_no","1").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","매수").put("trde_qty_jwa_cnt","5").put("trde_unit","50000"))
            .put(new JSONObject().put("trde_dt","20260701").put("trde_no","2").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","매수").put("trde_qty_jwa_cnt","5").put("trde_unit","60000"))
            .put(new JSONObject().put("trde_dt","20260801").put("trde_no","3").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","매도").put("trde_qty_jwa_cnt","10").put("trde_unit","70000"))
            .put(new JSONObject().put("trde_dt","20260601").put("trde_no","4").put("stk_cd","A000660").put("stk_nm","SK하이닉스").put("io_tp_nm","매수").put("trde_qty_jwa_cnt","1").put("trde_unit","100000"));
        db.snapshot("real","20260101","20261009",new KiwoomApi.Snapshot(trades,new JSONArray(),new JSONArray(),new JSONObject()));
        db.setPairs("real","20260801:3",Collections.singletonMap("20260601:1",5L));
        db.setPairStart("real","005930","20260701:2");
        Pairing.Result r=db.pairing("real");
        assertNull(r.fill("20260601:1"));assertNotNull(r.fill("20260601:4"));
        assertTrue(r.issues.isEmpty());assertEquals(1,r.pairs.size());assertEquals(Long.valueOf(5),r.unmatched.get("20260801:3"));
        db.setPairStart("real","005930",null);assertEquals(2,db.pairing("real").pairs.size());
    }
    @Test public void version1DatabaseUpgradesWithoutLosingTrades() throws Exception {
        db.close(); android.content.Context ctx=RuntimeEnvironment.getApplication(); ctx.deleteDatabase("diary.db");
        android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("diary.db"),null);
        old.execSQL("CREATE TABLE trades(scope TEXT,id TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,id))");
        old.execSQL("INSERT INTO trades VALUES('real','20261002:1','20261002','{\"trde_dt\":\"20261002\",\"trde_no\":\"1\"}')");
        old.setVersion(1); old.close();
        db=new DiaryDb(ctx);
        assertEquals(1,db.rows("trades","real").size());assertTrue(db.manualPairs("real").isEmpty());assertTrue(db.openingLots("real").isEmpty());
    }
    @Test public void settledTradeReplacesPendingFillAndKeepsItsJournal() throws Exception {
        JSONObject fill=new JSONObject().put("ord_no","0000050").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","현금매수").put("cntr_qty","3").put("cntr_uv","70000");
        JSONObject pending=Records.pendingTrade("20261008",fill,new java.util.HashMap<>());
        db.snapshot("real","20261001","20261008",new KiwoomApi.Snapshot(new JSONArray().put(pending),new JSONArray(),new JSONArray(),new JSONObject()));
        db.note("real","p:20261008:0000050","눌림목 매수");
        // 결제 전이면 같은 기간을 다시 받아도 일지가 붙은 채로 남는다.
        db.snapshot("real","20261001","20261008",new KiwoomApi.Snapshot(new JSONArray().put(pending),new JSONArray(),new JSONArray(),new JSONObject()));
        assertEquals(1,db.rows("trades","real").size());assertEquals("눌림목 매수",db.note("real","p:20261008:0000050"));
        JSONObject settled=new JSONObject().put("trde_dt","20261013").put("trde_no","000000007").put("cntr_dt","20261008").put("stk_cd","A005930").put("stk_nm","삼성전자").put("io_tp_nm","매수").put("trde_qty_jwa_cnt","3").put("trde_unit","70,000");
        db.snapshot("real","20261009","20261013",new KiwoomApi.Snapshot(new JSONArray().put(settled),new JSONArray(),new JSONArray(),new JSONObject()));
        assertEquals(1,db.rows("trades","real").size());assertEquals("000000007",db.rows("trades","real").get(0).getString("trde_no"));
        assertEquals("눌림목 매수",db.note("real","20261013:000000007"));assertEquals("",db.note("real","p:20261008:0000050"));
    }
    private static String ranges(java.util.List<String[]> list) { StringBuilder b=new StringBuilder(); for(String[] r:list) b.append(r[0]).append('-').append(r[1]).append(' '); return b.toString().trim(); }
    @Test public void onlyMissingDaysAreFetchedAndTodayStaysOpen() throws Exception {
        assertEquals("20261001-20261013",ranges(db.missing("real","20261001","20261013")));
        db.snapshot("real","20261005","20261013",snapshot("20261006","000000001","10"),"20261012");
        assertEquals("20261005-20261012",ranges(db.covered("real")));
        assertEquals("20261001-20261004 20261013-20261013",ranges(db.missing("real","20261001","20261013")));
        db.snapshot("real","20261001","20261004",snapshot("20261002","000000002","10"),"20261012");
        assertEquals("20261001-20261012",ranges(db.covered("real")));
        assertEquals("",ranges(db.missing("real","20261003","20261010")));
    }
    @Test public void legacyMetaCountsAsCoveredUntilTheDayBeforeSync() throws Exception {
        android.content.ContentValues v=new android.content.ContentValues(); v.put("scope","real");
        v.put("raw",new JSONObject().put("from","20250101").put("to","20261013").put("synced",System.currentTimeMillis()).toString());
        db.getWritableDatabase().insert("meta",null,v);
        String yesterday=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals("20250101-"+(yesterday.compareTo("20261013")<0?yesterday:"20261013"),ranges(db.covered("real")));
    }
    @Test public void longGapsSplitIntoYearChunksNewestFirst() {
        java.util.List<String[]> gaps=new java.util.ArrayList<>(); gaps.add(new String[]{"20240101","20261013"});
        assertEquals("20251014-20261013 20241014-20251013 20240101-20241013",ranges(Records.chunks(gaps)));
    }
    @Test public void brokerRemovedTradeStillHasAnAccessibleJournal() throws Exception {
        db.snapshot("real","20261001","20261009",snapshot("20261002","000000001","10"));db.note("real","20261002:000000001","복기 기록");
        db.snapshot("real","20261001","20261009",new KiwoomApi.Snapshot(new JSONArray(),new JSONArray(),new JSONArray(),new JSONObject()));
        assertEquals(0,db.rows("trades","real").size());assertEquals(1,db.journal("real").size());assertEquals("복기 기록",db.journal("real").get(0).getString("note"));
    }
}
