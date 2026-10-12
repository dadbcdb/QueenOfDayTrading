package kr.stockdiary;

import android.view.*;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class MainActivityTest {
    private View find(View root,String label) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(label))return root;
        if(root instanceof ViewGroup) {ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View v=find(group.getChildAt(i),label);if(v!=null)return v;}}
        return null;
    }
    @Test public void demoOverviewTradesJournalAndSettingsOpen() {
        RuntimeEnvironment.getApplication().deleteDatabase("diary.db");
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"나의 투자 기록"));find(root,"샘플로 둘러보기").performClick();root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"14,000원"));assertNotNull(find(root,"종목별 실현 손익"));assertNotNull(find(root,"19,350원"));find(root,"거래 내역").performClick();root=activity.getWindow().getDecorView();assertNotNull(find(root,"매수  ·  삼성전자"));
            find(root,"매매일지").performClick();root=activity.getWindow().getDecorView();assertNotNull(find(root,"삼성전자 · 2026.10.06"));
            find(root,"계좌 설정").performClick();root=activity.getWindow().getDecorView();assertNotNull(find(root,"선택한 기간 가져오기"));
        }
    }
}
