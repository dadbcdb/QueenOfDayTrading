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
    @Test public void version1DatabaseUpgradesWithoutLosingTrades() throws Exception {
        db.close(); android.content.Context ctx=RuntimeEnvironment.getApplication(); ctx.deleteDatabase("diary.db");
        android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("diary.db"),null);
        old.execSQL("CREATE TABLE trades(scope TEXT,id TEXT,day TEXT,raw TEXT,PRIMARY KEY(scope,id))");
        old.execSQL("INSERT INTO trades VALUES('real','20261002:1','20261002','{\"trde_dt\":\"20261002\",\"trde_no\":\"1\"}')");
        old.setVersion(1); old.close();
        db=new DiaryDb(ctx);
        assertEquals(1,db.rows("trades","real").size());assertTrue(db.manualPairs("real").isEmpty());assertTrue(db.openingLots("real").isEmpty());
    }
    @Test public void brokerRemovedTradeStillHasAnAccessibleJournal() throws Exception {
        db.snapshot("real","20261001","20261009",snapshot("20261002","000000001","10"));db.note("real","20261002:000000001","복기 기록");
        db.snapshot("real","20261001","20261009",new KiwoomApi.Snapshot(new JSONArray(),new JSONArray(),new JSONArray(),new JSONObject()));
        assertEquals(0,db.rows("trades","real").size());assertEquals(1,db.journal("real").size());assertEquals("복기 기록",db.journal("real").get(0).getString("note"));
    }
}
