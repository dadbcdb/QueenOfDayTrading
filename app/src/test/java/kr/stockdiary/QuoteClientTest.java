package kr.stockdiary;

import org.junit.*;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

public class QuoteClientTest {
    private MockWebServer server;private KiwoomApi api;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start();String base=server.url("/").toString();api=new KiwoomApi(base.substring(0,base.length()-1));
    }
    @After public void close() throws Exception {server.shutdown();}
    private void response(String json){server.enqueue(new MockResponse().setBody(json));}
    @Test public void priceRequestNormalizesSymbolAndPreservesSignedPriceMagnitude() throws Exception {
        response("{\"return_code\":0,\"cur_prc\":\"-070,500\"}");
        assertEquals(new BigDecimal("70500"),api.currentPrice("token"," A005930 "));
        RecordedRequest r=server.takeRequest();assertEquals("/api/dostk/stkinfo",r.getPath());assertEquals("ka10001",r.getHeader("api-id"));
        assertEquals("Bearer token",r.getHeader("authorization"));assertEquals("005930",new JSONObject(r.getBody().readUtf8()).getString("stk_cd"));
    }
    @Test public void missingPriceIsNotDisplayedAsZero() throws Exception {
        response("{\"return_code\":0}");
        try{api.currentPrice("token","005930");fail("Missing price accepted");}catch(KiwoomApi.ApiException e){assertTrue(e.getMessage().contains("비어"));}
    }
    @Test public void hynixMarketSuffixIsSentWithoutChangingTheBaseSymbol() throws Exception {
        for(String code:new String[]{" A000660 ","A000660_NX","000660_AL"}) {
            response("{\"return_code\":0,\"stk_cd\":\"A000660\",\"cur_prc\":\"+1,306,000\"}");
            assertEquals(new BigDecimal("1306000"),api.currentPrice("token",code));
            RecordedRequest r=server.takeRequest();String requested=new JSONObject(r.getBody().readUtf8()).getString("stk_cd");
            assertEquals(code.contains("_NX")?"000660_NX":code.contains("_AL")?"000660_AL":"000660",requested);
        }
        assertEquals("000660_NX",QuoteMarket.NXT.requestCode("A000660"));
        assertEquals("000660_AL",QuoteMarket.SOR.requestCode("000660"));
    }
    @Test public void differentResponseSymbolCannotBeShownAsTheRequestedStock() throws Exception {
        response("{\"return_code\":0,\"stk_cd\":\"A005930\",\"cur_prc\":\"70000\"}");
        try{api.currentPrice("token","000660");fail("Wrong stock accepted");}
        catch(KiwoomApi.ApiException e){assertTrue(e.getMessage().contains("종목코드"));}
    }
    @Test public void expiredTokenIsRenewedOnceThenPriceIsRetried() throws Exception {
        response("{\"token\":\"old\"}");response("{\"return_code\":3,\"return_msg\":\"인증 실패[8005:expired]\"}");
        response("{\"token\":\"new\"}");response("{\"return_code\":0,\"cur_prc\":\"70500\"}");
        assertEquals(new BigDecimal("70500"),new QuoteClient(api).price("key","secret",false,"005930"));
        assertEquals(4,server.getRequestCount());server.takeRequest();assertEquals("Bearer old",server.takeRequest().getHeader("authorization"));
        assertEquals("/oauth2/token",server.takeRequest().getPath());assertEquals("Bearer new",server.takeRequest().getHeader("authorization"));
    }
    @Test public void credentialFailureDoesNotRetryAndAccountChangeGetsNewToken() throws Exception {
        response("{\"return_code\":3,\"return_msg\":\"접속 실패[8001:wrong key]\"}");
        QuoteClient client=new QuoteClient(api);
        try{client.price("bad","secret",false,"005930");fail("Invalid key accepted");}catch(KiwoomApi.ApiException e){assertEquals(8001,e.code);}
        assertEquals(1,server.getRequestCount());
        response("{\"token\":\"account-a\"}");response("{\"return_code\":0,\"cur_prc\":\"1\"}");
        response("{\"return_code\":0,\"cur_prc\":\"2\"}");
        response("{\"token\":\"account-b\"}");response("{\"return_code\":0,\"cur_prc\":\"3\"}");
        client.price("a","sa",false,"005930");client.price("a","sa",false,"005930");client.price("b","sb",true,"005930");
        List<String> paths=new ArrayList<>();for(int i=0;i<6;i++)paths.add(server.takeRequest().getPath());
        assertEquals(Arrays.asList("/oauth2/token","/oauth2/token","/api/dostk/stkinfo","/api/dostk/stkinfo","/oauth2/token","/api/dostk/stkinfo"),paths);
    }
}
