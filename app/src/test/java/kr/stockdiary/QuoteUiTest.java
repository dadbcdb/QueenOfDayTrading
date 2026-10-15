package kr.stockdiary;

import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class QuoteUiTest {
    public static class QuoteActivity extends MainActivity {
        final AtomicInteger requests=new AtomicInteger(); volatile boolean reject;volatile String lastCode;
        @Override public void onCreate(Bundle state) {
            try {getPreferences(0).edit().clear().putString("scope",Records.scope("test",false)).commit();}
            catch(Exception e){throw new RuntimeException(e);}
            super.onCreate(state);
        }
        @Override JSONObject loadQuoteCredentials() throws Exception {return new JSONObject().put("key","test").put("secret","test-secret").put("mock",false);}
        @Override QuoteClient newQuoteClient() {
            return new QuoteClient(){@Override BigDecimal price(String k,String s,boolean m,String c) throws Exception {
                lastCode=c;int n=requests.incrementAndGet();if(reject)throw new KiwoomApi.ApiException("현재가 조회 실패 (코드 3 · 상세 8001)");
                return BigDecimal.valueOf(70000+n);
            }};
        }
    }
    private View find(View root,String text,boolean contains) {
        if(root instanceof TextView){String s=((TextView)root).getText().toString();if(contains?s.contains(text):s.equals(text))return root;}
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text,contains);if(found!=null)return found;}
        return null;
    }
    private void await(BooleanSupplier check) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!check.getAsBoolean() && System.nanoTime()<until){ShadowLooper.idleMainLooper();Thread.sleep(10);}
        ShadowLooper.idleMainLooper();assertTrue("Quote worker did not update the screen",check.getAsBoolean());
    }
    private android.widget.Spinner spinner(View root) {
        if(root instanceof android.widget.Spinner)return (android.widget.Spinner)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){android.widget.Spinner s=spinner(((ViewGroup)root).getChildAt(i));if(s!=null)return s;}
        return null;
    }
    @Test public void priceRefreshesWhileVisibleStopsInBackgroundAndShowsFailures() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase("diary.db");
        try(DiaryDb db=new DiaryDb(RuntimeEnvironment.getApplication())) {
            JSONObject t=new JSONObject().put("trde_dt","20261001").put("trde_no","1").put("cntr_dt","20261001").put("stk_cd","A005930")
                .put("stk_nm","삼성전자").put("io_tp_nm","매수").put("trde_qty_jwa_cnt","1").put("trde_unit","70000").put("trde_amt","70000");
            db.snapshot(Records.scope("test",false),"20261001","20261001",new KiwoomApi.Snapshot(new JSONArray().put(t),new JSONArray(),new JSONArray(),new JSONObject()));
        }
        try(ActivityController<QuoteActivity> controller=Robolectric.buildActivity(QuoteActivity.class).create(new Bundle()).start().resume().visible()) {
            QuoteActivity activity=controller.get();
            find(activity.getWindow().getDecorView(),"매매 짝",false).performClick();
            await(()->find(activity.getWindow().getDecorView(),"현재가 70,001원",true)!=null);
            ShadowLooper.idleMainLooper(10,TimeUnit.SECONDS);
            await(()->find(activity.getWindow().getDecorView(),"현재가 70,002원",true)!=null);
            find(activity.getWindow().getDecorView(),"현재가 새로고침",false).performClick();
            await(()->find(activity.getWindow().getDecorView(),"현재가 70,003원",true)!=null);
            controller.pause();ShadowLooper.idleMainLooper(30,TimeUnit.SECONDS);assertEquals(3,activity.requests.get());
            controller.resume();ShadowLooper.idleMainLooper();
            await(()->find(activity.getWindow().getDecorView(),"현재가 70,004원",true)!=null);
            activity.reject=true;find(activity.getWindow().getDecorView(),"현재가 새로고침",false).performClick();
            await(()->find(activity.getWindow().getDecorView(),"상세 8001",true)!=null);
            assertNotNull("Last successful price must survive a failed refresh",find(activity.getWindow().getDecorView(),"현재가 70,004원",true));
            activity.reject=false;spinner(activity.getWindow().getDecorView()).setSelection(1);ShadowLooper.idleMainLooper();
            await(()->find(activity.getWindow().getDecorView(),"현재가 70,006원 · NXT",true)!=null);
            assertEquals("005930_NX",activity.lastCode);
            assertNull("KRX cached quote must not be used for NXT",find(activity.getWindow().getDecorView(),"현재가 70,004원",true));
        }
    }
}
