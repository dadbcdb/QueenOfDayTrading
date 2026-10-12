package kr.stockdiary;

import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.math.BigDecimal;

public class KiwoomApiTest {
    private MockWebServer server;
    private String base;
    private final List<String> calls=new ArrayList<>();
    private final List<String> bodies=new ArrayList<>();
    private final List<String> continuations=new ArrayList<>();
    private boolean fail=false,loop=false;
    @Before public void setup() throws Exception {
        server=new MockWebServer();
        server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest exchange) {
            try {
                String path=exchange.getPath(),id=exchange.getHeader("api-id");
                calls.add(path+":"+id); String body=exchange.getBody().readUtf8(); bodies.add(body);
                if(fail) return new MockResponse().setResponseCode(401).setBody("secret-test-do-not-expose");
                MockResponse response=new MockResponse();
                JSONObject r=new JSONObject().put("return_code",0);
                if(path.equals("/oauth2/token")) r.put("token","access-test");
                else {
                    assertEquals("Bearer access-test",exchange.getHeader("authorization"));
                    String next=exchange.getHeader("next-key"); continuations.add(next);
                    switch(id) {
                        case "kt00015":
                            if(next.isEmpty() || loop) {response.setHeader("cont-yn","Y");response.setHeader("next-key","page2");}
                            r.put("trst_ovrl_trde_prps_array",new JSONArray().put(new JSONObject().put("trde_dt","20261002").put("trde_no",next.isEmpty()?"1":"2"))); break;
                        case "ka10074": r.put("dt_rlzt_pl",new JSONArray().put(new JSONObject().put("dt","20261002").put("tdy_sel_pl","179419").put("tdy_trde_cmsn","940").put("tdy_trde_tax","852")));break;
                        case "ka10073": r.put("dt_stk_rlzt_pl",new JSONArray().put(new JSONObject().put("dt","20261002").put("stk_cd","A005930").put("tdy_sel_pl","179419")));break;
                        case "kt00004": r.put("stk_acnt_evlt_prst",new JSONArray()).put("entr","000000123456");break;
                        default: throw new AssertionError("Unexpected API "+id);
                    }
                }
                return response.setBody(r.toString());
            } catch(Exception e) {throw new RuntimeException(e);}
        }});
        server.start();base=server.url("/").toString();base=base.substring(0,base.length()-1);
    }
    @After public void teardown() throws Exception {server.shutdown();}
    @Test public void paginationAndDomesticQueryContracts() throws Exception {
        KiwoomApi.Snapshot s=new KiwoomApi(base).sync("key-test","secret-test","20261001","20261009");
        assertEquals(2,s.trades.length());assertEquals(1,s.profits.length());assertEquals(0,s.holdings.length());
        assertEquals("000000123456",s.balance.getString("entr"));
        assertEquals(Arrays.asList("/oauth2/token:null","/api/dostk/acnt:kt00015","/api/dostk/acnt:kt00015","/api/dostk/acnt:ka10074","/api/dostk/acnt:ka10073","/api/dostk/acnt:kt00004"),calls);
        JSONObject request=new JSONObject(bodies.get(1));assertEquals("3",request.getString("tp")); assertEquals("1",request.getString("gds_tp"));assertEquals("%",request.getString("dmst_stex_tp"));
        assertEquals("page2",continuations.get(1));assertEquals("KRX",new JSONObject(bodies.get(5)).getString("dmst_stex_tp"));assertEquals(1,s.symbolProfits.length());
        assertEquals("key-test",new JSONObject(bodies.get(0)).getString("appkey"));
    }
    @Test public void errorDoesNotExposeResponseOrCredentials() throws Exception {
        fail=true;
        try {new KiwoomApi(base).sync("key-test","secret-test","20261001","20261009");fail("Expected error");}
        catch(KiwoomApi.ApiException e) {assertTrue(e.getMessage().contains("401"));assertFalse(e.getMessage().contains("secret-test"));}
    }
    @Test public void validTokenWithoutOptionalReturnCodeIsAccepted() throws Exception {
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r){return new MockResponse().setBody("{\"token\":\"test-token\",\"token_type\":\"bearer\",\"expires_dt\":\"20261010120000\"}");}});
        assertEquals("test-token",new KiwoomApi(base).authenticate("k","s"));
    }
    @Test public void authenticationErrorReportsStageAndReasonWithoutRawMessage() throws Exception {
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r){return new MockResponse().setBody("{\"return_code\":3,\"return_msg\":\"IP error secret-test account-1234\"}");}});
        try {new KiwoomApi(base).authenticate("key-test","secret-test");fail("Expected error");}
        catch(KiwoomApi.ApiException e){assertTrue(e.getMessage().contains("인증·토큰 발급"));assertTrue(e.getMessage().contains("코드 3"));assertTrue(e.getMessage().contains("IP"));assertFalse(e.getMessage().contains("secret-test"));assertFalse(e.getMessage().contains("account-1234"));}
    }
    @Test public void genericErrorExposesNumericDetailWithoutSensitiveServerText() {
        String result=KiwoomApi.errorDescription("",3,"인증 실패[8001:app key secret-test account-1234]");
        assertTrue(result.contains("코드 3 · 상세 8001"));assertTrue(result.contains("키 한 쌍"));
        assertFalse(result.contains("secret-test"));assertFalse(result.contains("account-1234"));
        String mode=KiwoomApi.errorDescription("",3,"인증 실패[8030:wrong mode]");assertTrue(mode.contains("실전·모의 구분"));
        String ip=KiwoomApi.errorDescription("kt00015",3,"CODE=8010");assertTrue(ip.contains("발급받은 IP"));assertTrue(ip.contains("거래 내역 조회"));
    }
    @Test public void paginationLoopFailsInsteadOfReturningPartialData() throws Exception {
        loop=true;
        try {new KiwoomApi(base).sync("k","s","20261001","20261009");fail("Expected loop detection");}
        catch(KiwoomApi.ApiException e) {assertTrue(e.getMessage().contains("연속조회"));}
        assertEquals(3,calls.size());
    }
    @Test public void cancellationPreventsAnyNetworkRequest() throws Exception {
        KiwoomApi api=new KiwoomApi(base);api.cancel();
        try {api.sync("k","s","20261001","20261009");fail("Expected cancellation");}catch(InterruptedException expected){}
        assertTrue(calls.isEmpty());
    }
    @Test public void brokerNumberParsingKeepsSignsAndPrecision() {
        assertEquals(new BigDecimal("-5350"),Numbers.parse("-000000005350"));
        assertEquals(new BigDecimal("56900"),Numbers.parse("56,900"));
        assertEquals(new BigDecimal("2.125"),Numbers.parse("2.125"));
        assertEquals(BigDecimal.ZERO,Numbers.parse(" "));
    }
    @Test public void stableIdentityKeepsJournalWhenMutableFieldsChange() throws Exception {
        JSONObject a=new JSONObject().put("trde_dt","20261006").put("trde_no","000000002").put("cmsn","10");
        JSONObject b=new JSONObject(a.toString()).put("cmsn","20");assertEquals(Records.tradeId(a),Records.tradeId(b));
        assertNotEquals(Records.scope("key",false),Records.scope("key",true));assertNotEquals(Records.scope("key1",false),Records.scope("key2",false));
    }
    @Test public void missingIdentityAndOutOfRangeDateAreRejected() throws Exception {
        try {Records.tradeId(new JSONObject().put("trde_dt","20261001"));fail("Missing ID accepted");}catch(IllegalArgumentException expected){}
        try {Records.dateInRange("20260930","20261001","20261009");fail("Out of range accepted");}catch(IllegalArgumentException expected){}
        Records.dateInRange("20261001","20261001","20261009");
    }
    @Test public void tradeSideUsesExplicitBrokerLabel() throws Exception {
        assertEquals("매수",Records.side(new JSONObject().put("io_tp_nm","매수").put("rmrk_nm","매도")));
        assertEquals("매도",Records.side(new JSONObject().put("rmrk_nm","장내매도")));
        assertEquals("기타",Records.side(new JSONObject().put("rmrk_nm","입고")));
    }
}
