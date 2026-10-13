package kr.stockdiary;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

public class MainActivity extends Activity {
    private final int BG=Color.rgb(16,24,32), PANEL=Color.rgb(27,38,48), TEXT=Color.rgb(238,244,248), MUTED=Color.rgb(161,176,189), GREEN=Color.rgb(122,226,182), RED=Color.rgb(255,145,145);
    private LinearLayout root,content,nav;
    private DiaryDb db; private Secrets secrets;
    private String scope="unlinked",from=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE),to=LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
    private int tab=0; private boolean busy=false; private boolean mock=false;
    private KiwoomApi activeApi; private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String notice="",exportData,chartCode="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        db=new DiaryDb(this); secrets=new Secrets(this);
        scope=getPreferences(0).getString("scope","unlinked"); mock=getPreferences(0).getBoolean("mock",false);
        from=getPreferences(0).getString("from",from); to=getPreferences(0).getString("to",to);
        if(state!=null) tab=state.getInt("tab",0);
        render();
    }
    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putInt("tab",tab); }
    @Override protected void onDestroy() { if(activeApi!=null) activeApi.cancel(); worker.execute(db::close); worker.shutdown(); super.onDestroy(); }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String s,int size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(3),0,dp(3)); return t; }
    private TextView heading(String s) { TextView t=text(s,23,TEXT); t.setTypeface(null,Typeface.BOLD); return t; }
    private String appVersion() {
        try {
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            return "v"+info.versionName+" (빌드 "+info.versionCode+")";
        } catch(android.content.pm.PackageManager.NameNotFoundException e) { return "버전 확인 불가"; }
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout v=column(); v.setPadding(dp(18),dp(15),dp(18),dp(15)); v.setBackground(shape(PANEL,18));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(10)); parent.addView(v,lp); return v;
    }
    private Button button(LinearLayout parent,String label,Runnable action,boolean accent) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(accent?BG:TEXT);
        b.setBackground(shape(accent?GREEN:Color.rgb(42,57,68),12));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(49)); lp.setMargins(0,dp(7),0,dp(7)); parent.addView(b,lp);
        b.setEnabled(!busy); b.setAlpha(busy?.5f:1f); b.setOnClickListener(v->action.run()); return b;
    }
    private EditText input(LinearLayout p,String hint,String value,boolean password) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setTextSize(15); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setHint(hint);
        e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(shape(BG,10));
        e.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setText(value); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50)); lp.setMargins(0,dp(7),0,dp(7)); p.addView(e,lp); return e;
    }
    private void render() {
        if(isFinishing() || isDestroyed()) return;
        root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)-> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(root);
        LinearLayout header=column(); header.setPadding(dp(22),dp(16),dp(22),dp(8));
        TextView logo=text("주식일지  /  STOCK DIARY",14,GREEN); logo.setTypeface(null,Typeface.BOLD); header.addView(logo);
        header.addView(text(appVersion(),12,MUTED));
        String status=scope.equals("demo-v1")?"샘플 데이터 · 실제 계좌와 분리됨":scope.equals("unlinked")?"계좌 연결 전":mock?"모의투자 계좌":"실전 계좌 · 조회";
        header.addView(text(status,12,MUTED)); root.addView(header);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); content=column(); content.setPadding(dp(22),dp(8),dp(22),dp(20)); scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(busy) { ProgressBar progress=new ProgressBar(this); content.addView(progress,new LinearLayout.LayoutParams(-1,dp(36))); content.addView(text("키움에서 내역을 가져오는 중…",14,GREEN)); }
        if(!notice.isEmpty()) content.addView(text(notice,13,GREEN));
        try { switch(tab) { case 0: overview(); break; case 1: lots(); break; case 2: trades(); break; case 3: journal(); break; default: settings(); } }
        catch(Exception e) { content.addView(text("저장된 데이터를 읽지 못했습니다. 앱을 다시 실행해 주세요.",14,RED)); }
        nav=new LinearLayout(this); nav.setPadding(dp(9),dp(8),dp(9),dp(8)); nav.setBackgroundColor(PANEL);
        String[] labels={"요약","매매 짝","거래 내역","매매일지","계좌 설정"};
        for(int i=0;i<labels.length;i++) { final int next=i; TextView item=text(labels[i],12,i==tab?GREEN:MUTED); item.setGravity(Gravity.CENTER); item.setTypeface(null,Typeface.BOLD); item.setPadding(0,dp(14),0,dp(14)); nav.addView(item,new LinearLayout.LayoutParams(0,-2,1)); item.setOnClickListener(v->{tab=next;notice="";render();}); }
        root.addView(nav); root.requestApplyInsets();
    }
    private void overview() throws Exception {
        content.addView(heading("나의 투자 기록")); content.addView(text("거래를 모으고, 판단을 돌아보세요.",14,MUTED));
        JSONObject meta=db.meta(scope);
        if(meta==null) {
            LinearLayout c=card(content); c.addView(text("첫 기록을 시작하세요",20,TEXT)); c.addView(text("계좌를 연결한 뒤 조회 기간을 선택하면 거래 내역과 손익이 이 기기에 저장됩니다.",14,MUTED));
            button(c,"키움 계좌 연결",()->{tab=4;render();},true); button(c,"샘플로 둘러보기",this::demo,false); return;
        }
        List<JSONObject> profits=db.rows("profits",scope); BigDecimal total=BigDecimal.ZERO,fees=BigDecimal.ZERO,taxes=BigDecimal.ZERO;
        TreeMap<String,BigDecimal> months=new TreeMap<>(Collections.reverseOrder());
        for(JSONObject r:profits) { BigDecimal p=Numbers.parse(r.getString("tdy_sel_pl")); total=total.add(p); fees=fees.add(Numbers.parse(r.getString("tdy_trde_cmsn"))); taxes=taxes.add(Numbers.parse(r.getString("tdy_trde_tax"))); String m=r.getString("dt").substring(0,6); months.put(m,months.getOrDefault(m,BigDecimal.ZERO).add(p)); }
        LinearLayout c=card(content); c.addView(text("저장된 조회 기간의 실현 손익",13,MUTED)); TextView value=text(Numbers.format(total)+"원",32,total.signum()<0?RED:GREEN); value.setTypeface(null,Typeface.BOLD); c.addView(value);
        c.addView(text("키움 일별 실현 손익 합계 · 수수료·세금 재차감 없음",11,MUTED));
        c.addView(text("손익 조회 수수료  "+Numbers.format(fees)+"원    세금  "+Numbers.format(taxes)+"원",12,MUTED));
        c.addView(text("거래 "+db.rows("trades",scope).size()+"건  ·  일지 "+db.journal(scope).size()+"개",13,TEXT));
        try { Pairing.Result pr=db.pairing(scope); BigDecimal paired=BigDecimal.ZERO; long left=0;
            for(Pairing.Pair p:pr.pairs) paired=paired.add(p.profit()); for(Pairing.Lot l:pr.open) left+=l.remaining;
            TextView pairLine=text("매매 짝 손익 "+signed(paired)+"  ·  남은 물량 "+pr.open.size()+"묶음 "+Numbers.format(BigDecimal.valueOf(left))+"주",13,profitColor(paired));
            pairLine.setOnClickListener(v->{tab=1;render();}); c.addView(pairLine);
        } catch(Exception e) { c.addView(text("매매 짝을 계산하지 못했습니다.",12,RED)); }
        content.addView(text("월별 실현 손익",18,TEXT));
        if(months.isEmpty()) content.addView(text("조회된 기간에 실현 손익 기록이 없습니다.",13,MUTED));
        for(Map.Entry<String,BigDecimal> m:months.entrySet()) { LinearLayout row=card(content); row.addView(text(m.getKey().substring(0,4)+"년 "+m.getKey().substring(4)+"월",14,MUTED)); row.addView(text(Numbers.format(m.getValue())+"원",22,m.getValue().signum()<0?RED:GREEN)); }
        content.addView(text("종목별 실현 손익",18,TEXT));
        TreeMap<String,BigDecimal> symbols=new TreeMap<>(); Map<String,String> names=new HashMap<>();
        for(JSONObject r:db.rows("symbol_profits",scope)) {String code=r.getString("stk_cd");names.put(code,r.optString("stk_nm",code));symbols.put(code,symbols.getOrDefault(code,BigDecimal.ZERO).add(Numbers.parse(r.getString("tdy_sel_pl"))));}
        if(symbols.isEmpty())content.addView(text("조회된 종목별 실현 손익이 없습니다.",13,MUTED));
        for(Map.Entry<String,BigDecimal> s:symbols.entrySet()) {LinearLayout row=card(content);row.addView(text(names.get(s.getKey()),15,TEXT));row.addView(text(Numbers.format(s.getValue())+"원",22,s.getValue().signum()<0?RED:GREEN));}
        content.addView(text("보유 종목 · KRX 기준",18,TEXT));
        for(JSONObject r:db.rows("holdings",scope)) { LinearLayout h=card(content); h.addView(text(r.optString("stk_nm"),17,TEXT)); h.addView(text("보유 "+Numbers.format(Numbers.parse(r.optString("rmnd_qty")))+"주 · 평균 "+Numbers.money(r.optString("avg_prc")),13,MUTED)); h.addView(text("평가 손익  "+Numbers.money(r.optString("pl_amt")),18,Numbers.parse(r.optString("pl_amt")).signum()<0?RED:GREEN)); }
        content.addView(text("마지막 조회: "+Numbers.date(meta.getString("from"))+" ~ "+Numbers.date(meta.getString("to")),12,MUTED));
        content.addView(text("저장 시각: "+java.time.Instant.ofEpochMilli(meta.getLong("synced")).atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),12,MUTED));
        content.addView(text("월별 합계는 가져온 날짜만 포함합니다. 기간을 넓혀 동기화하면 기록이 추가됩니다. 보유 종목 평가는 실시간 시세가 아닙니다.",12,MUTED));
        button(content,"거래 내역 동기화",()->{tab=4;render();},true);
    }
    private void trades() throws Exception {
        content.addView(heading("거래 내역")); content.addView(text("거래를 눌러 매매 이유와 복기를 남기세요.",13,MUTED));
        EditText search=input(content,"종목명 · 종목코드 검색","",false);
        Spinner filter=new Spinner(this); String[] options={"전체 거래","매수","매도"}; filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options)); content.addView(filter);
        LinearLayout list=column(); content.addView(list); List<JSONObject> rows=db.rows("trades",scope);
        Runnable update=()-> { list.removeAllViews(); String query=search.getText().toString().trim().toLowerCase(Locale.ROOT); int count=0;
            for(JSONObject r:rows) { String side=Records.side(r); if(!query.isEmpty() && !(r.optString("stk_nm")+r.optString("stk_cd")).toLowerCase(Locale.ROOT).contains(query)) continue;
                if(filter.getSelectedItemPosition()>0 && !side.equals(options[filter.getSelectedItemPosition()])) continue;
                tradeCard(list,r); count++;
            }
            if(count==0) list.addView(text("표시할 거래가 없습니다. 계좌 설정에서 조회 기간을 선택해 동기화하세요.",14,MUTED));
        };
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int c,int f) {} public void onTextChanged(CharSequence s,int a,int b,int c) { update.run(); } public void afterTextChanged(Editable e) {} });
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onItemSelected(AdapterView<?> p,View v,int pos,long id) {update.run();} public void onNothingSelected(AdapterView<?> p) {} }); update.run();
    }
    private void tradeCard(LinearLayout parent,JSONObject r) {
        try { String id=Records.tradeId(r),side=Records.side(r); LinearLayout c=card(parent);
            c.addView(text(side+"  ·  "+r.optString("stk_nm",r.optString("stk_cd")),18,side.equals("매도")?RED:GREEN));
            c.addView(text(Numbers.format(Numbers.parse(r.optString("trde_qty_jwa_cnt")))+"주 × "+Numbers.money(r.optString("trde_unit")),15,TEXT));
            String executed=r.optString("cntr_dt"); c.addView(text("거래일 "+Numbers.date(r.optString("trde_dt"))+(executed.isEmpty()?"":" · 체결일 "+Numbers.date(executed)),11,MUTED));
            c.addView(text("거래금액 "+Numbers.money(r.optString("trde_amt"))+" · 수수료 "+Numbers.money(r.optString("cmsn"))+" · 세금 "+Numbers.money(r.optString("trde_agri_tax")),11,MUTED));
            String note=db.note(scope,id); c.addView(text(note.isEmpty()?"＋ 매매일지 작성":note,13,note.isEmpty()?MUTED:TEXT));
            c.setOnClickListener(v->editNote(id,r.optString("stk_nm")+" · "+Numbers.date(r.optString("cntr_dt",r.optString("trde_dt")))));
        } catch(Exception e) { parent.addView(text("읽을 수 없는 거래 기록",13,RED)); }
    }
    private int profitColor(BigDecimal n) { return n.signum()<0?RED:GREEN; }
    private String signed(BigDecimal n) { return (n.signum()>0?"+":"")+Numbers.format(n)+"원"; }
    private void lots() throws Exception {
        content.addView(heading("매매 짝")); content.addView(text("떨어질 때 산 묶음을 산 값보다 오를 때 팝니다. 매도는 매도가보다 싸게 산 최근 매수부터 자동으로 짝짓습니다(LIFO).",13,MUTED));
        Pairing.Result r=db.pairing(scope);
        for(String issue:r.issues) content.addView(text(issue,12,RED));
        Map<String,String> names=new LinkedHashMap<>(); for(Pairing.Fill f:r.fills) names.putIfAbsent(f.code,f.name);
        Map<String,BigDecimal> held=new HashMap<>();
        for(JSONObject h:db.rows("holdings",scope)) { String code=Pairing.code(h.optString("stk_cd")); names.putIfAbsent(code,h.optString("stk_nm",code)); held.put(code,Numbers.parse(h.optString("rmnd_qty"))); }
        if(names.isEmpty()) { LinearLayout c=card(content); c.addView(text("짝지을 거래가 없습니다",18,TEXT)); c.addView(text("계좌 설정에서 거래 내역을 가져오세요.",14,MUTED)); button(c,"계좌 설정",()->{tab=4;render();},true); return; }
        chart(r,names);
        content.addView(text("남은 물량 · 이 값보다 오르면 매도",18,TEXT));
        for(Map.Entry<String,String> s:names.entrySet()) {
            List<Pairing.Lot> open=new ArrayList<>(); long remaining=0;
            for(Pairing.Lot l:r.open) if(l.buy.code.equals(s.getKey())) { open.add(l); remaining+=l.remaining; }
            long orphan=0; for(Map.Entry<String,Long> u:r.unmatched.entrySet()) if(r.fill(u.getKey()).code.equals(s.getKey())) orphan+=u.getValue();
            BigDecimal broker=held.get(s.getKey()); boolean mismatch=broker!=null && broker.compareTo(BigDecimal.valueOf(remaining))!=0;
            if(open.isEmpty() && orphan==0 && !mismatch) continue;
            open.sort((a,b)->b.buy.price.compareTo(a.buy.price));
            LinearLayout c=card(content); c.addView(text(s.getValue(),17,TEXT)); c.addView(text("남은 "+Numbers.format(BigDecimal.valueOf(remaining))+"주 · "+open.size()+"묶음",13,MUTED));
            for(Pairing.Lot l:open) {
                TextView line=text(Numbers.format(l.buy.price)+"원 × "+Numbers.format(BigDecimal.valueOf(l.remaining))+"주    "+(l.buy.opening?"기초 · ":"")+Numbers.date(l.buy.day),16,GREEN); c.addView(line);
                if(l.buy.opening) line.setOnLongClickListener(v->{ new AlertDialog.Builder(this).setTitle("기초 물량 삭제").setMessage(line.getText()).setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{db.deleteOpeningLot(scope,l.buy.id);notice="기초 물량을 삭제했습니다.";render();}).show(); return true; });
            }
            if(mismatch) c.addView(text("키움 보유 "+Numbers.format(broker)+"주 · 장부 "+Numbers.format(BigDecimal.valueOf(remaining))+"주. 조회 기간 이전에 산 물량은 기초 물량으로 입력하세요.",12,RED));
            if(orphan>0) c.addView(text("매수 기록이 없는 매도 "+Numbers.format(BigDecimal.valueOf(orphan))+"주",12,RED));
        }
        button(content,"기초 물량 추가",()->addOpeningLot(names,r),false);
        content.addView(text("완료된 짝",18,TEXT)); content.addView(text("매도를 눌러 짝지을 매수를 직접 고를 수 있습니다. 손익은 수수료·세금 제외.",12,MUTED));
        Map<String,List<Pairing.Pair>> bySell=new LinkedHashMap<>(); BigDecimal total=BigDecimal.ZERO;
        for(int i=r.fills.size()-1;i>=0;i--) { Pairing.Fill f=r.fills.get(i); if(f.side.equals("매도")) bySell.put(f.id,r.pairsOf(f.id)); }
        for(Map.Entry<String,List<Pairing.Pair>> e:bySell.entrySet()) {
            Pairing.Fill sell=r.fill(e.getKey()); LinearLayout c=card(content);
            c.addView(text(sell.name+" · "+Numbers.date(sell.day)+" 매도 "+Numbers.format(BigDecimal.valueOf(sell.qty))+"주",13,MUTED));
            for(Pairing.Pair p:e.getValue()) { BigDecimal profit=p.profit(); total=total.add(profit);
                c.addView(text(Numbers.format(p.buy.price)+" → "+Numbers.format(p.sell.price)+" × "+Numbers.format(BigDecimal.valueOf(p.qty))+"주   "+signed(profit)+(p.manual?"  · 직접 지정":""),15,profitColor(profit))); }
            Long orphan=r.unmatched.get(sell.id); if(orphan!=null) c.addView(text("짝 없음 "+Numbers.format(BigDecimal.valueOf(orphan))+"주",13,RED));
            c.setOnClickListener(v->editPairs(sell,r));
        }
        if(bySell.isEmpty()) content.addView(text("아직 매도 기록이 없습니다.",13,MUTED));
        else content.addView(text("짝 손익 합계  "+signed(total),16,profitColor(total)));
    }
    private void chart(Pairing.Result r,Map<String,String> names) {
        List<String> codes=new ArrayList<>(); for(String code:names.keySet()) if(!r.fillsOf(code).isEmpty()) codes.add(code);
        if(codes.isEmpty()) return;
        if(!codes.contains(chartCode)) chartCode=codes.get(0);
        LinearLayout c=card(content);
        if(codes.size()>1) {
            Spinner pick=new Spinner(this); List<String> labels=new ArrayList<>(); for(String code:codes) labels.add(names.get(code));
            pick.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); pick.setSelection(codes.indexOf(chartCode)); c.addView(pick);
            pick.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onItemSelected(AdapterView<?> p,View v,int pos,long id) { if(!codes.get(pos).equals(chartCode)) { chartCode=codes.get(pos); render(); } } public void onNothingSelected(AdapterView<?> p) {} });
        } else c.addView(text(names.get(chartCode),17,TEXT));
        c.addView(text("▲ 매수  ▼ 매도  숫자는 수량 · 실선은 짝 · 점선은 남은 묶음. 그래프를 끌어 시점을 고르세요.",11,MUTED));
        LotChart chart=new LotChart(this); c.addView(chart,new LinearLayout.LayoutParams(-1,-2));
        TextView at=text("",14,LotChart.CURSOR),held=text("",16,TEXT),detail=text("",12,MUTED); c.addView(at); c.addView(held); c.addView(detail);
        LinearLayout steps=new LinearLayout(this); c.addView(steps);
        for(int d:new int[]{-1,1}) { Button b=new Button(this); b.setText(d<0?"◀ 이전 체결":"다음 체결 ▶"); b.setAllCaps(false); b.setTextColor(TEXT); b.setBackground(shape(Color.rgb(42,57,68),12));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1); lp.setMargins(d<0?0:dp(5),dp(6),d<0?dp(5):0,0); steps.addView(b,lp); b.setOnClickListener(v->chart.moveCursor(chart.cursorIndex()+d)); }
        chart.show(r,chartCode,f->{
            List<Pairing.Lot> lots=r.heldAt(f); long qty=0; StringBuilder ladder=new StringBuilder();
            for(Pairing.Lot l:lots) { qty+=l.remaining; if(ladder.length()>0) ladder.append('\n'); ladder.append(Numbers.format(l.buy.price)).append("원 × ").append(l.remaining).append("주"); }
            at.setText(String.format(Locale.KOREA,"%s %s %s원 × %d주 직후",Numbers.date(f.day),f.side,Numbers.format(f.price),f.qty));
            held.setText(lots.isEmpty()?"남은 묶음 없음":"남은 "+lots.size()+"묶음 "+qty+"주\n"+ladder);
            StringBuilder partners=new StringBuilder();
            for(Pairing.Pair p:r.pairs) { Pairing.Fill other=p.buy==f?p.sell:p.sell==f?p.buy:null; if(other==null) continue;
                partners.append(partners.length()==0?"짝: ":", ").append(other.side).append(" ").append(Numbers.date(other.day)).append(" ").append(Numbers.format(other.price)).append("원 ").append(p.qty).append("주 (").append(signed(p.profit())).append(")"); }
            detail.setText(partners.length()==0?"이 체결은 아직 짝이 없습니다.":partners);
        });
    }
    private LinearLayout dialogBody(AlertDialog.Builder b) {
        LinearLayout body=column(); body.setPadding(dp(22),dp(8),dp(22),dp(8)); ScrollView sv=new ScrollView(this); sv.addView(body); b.setView(sv); return body;
    }
    private EditText numberInput(LinearLayout p,String hint,String value) { EditText e=input(p,hint,value,false); e.setInputType(InputType.TYPE_CLASS_NUMBER); return e; }
    private void editPairs(Pairing.Fill sell,Pairing.Result r) {
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(sell.name+" "+Numbers.format(sell.price)+"원 × "+Numbers.format(BigDecimal.valueOf(sell.qty))+"주 매도");
        LinearLayout body=dialogBody(b); body.addView(text("각 매수 묶음에서 몇 주를 이 매도로 팔았는지 입력하세요. 비워 둔 수량은 자동(LIFO)으로 짝짓습니다.",13,MUTED));
        Map<String,EditText> fields=new LinkedHashMap<>(); Map<String,Long> limits=new HashMap<>();
        for(int i=r.fills.size()-1;i>=0;i--) { Pairing.Fill buy=r.fills.get(i);
            if(!buy.side.equals("매수") || !buy.code.equals(sell.code) || !buy.before(sell)) continue;
            long mine=0,others=0; for(Pairing.Pair p:r.pairs) if(p.buy==buy) { if(p.sell==sell) mine+=p.qty; else if(p.manual) others+=p.qty; }
            long limit=buy.qty-others; if(limit<=0) continue; limits.put(buy.id,limit);
            body.addView(text(Numbers.format(buy.price)+"원 · "+Numbers.date(buy.day)+(buy.opening?" 기초":"")+" · 최대 "+limit+"주",14,TEXT));
            fields.put(buy.id,numberInput(body,"0",mine>0?String.valueOf(mine):""));
        }
        if(fields.isEmpty()) body.addView(text("이 매도보다 먼저 산 매수 묶음이 없습니다. 기초 물량을 먼저 추가하세요.",13,RED));
        b.setNegativeButton("닫기",null).setNeutralButton("자동으로",(d,w)->{db.setPairs(scope,sell.id,Collections.emptyMap());notice="자동 짝(LIFO)으로 되돌렸습니다.";render();});
        b.setPositiveButton("저장",(d,w)->{
            Map<String,Long> chosen=new LinkedHashMap<>(); long sum=0;
            for(Map.Entry<String,EditText> e:fields.entrySet()) { String v=e.getValue().getText().toString().trim(); if(v.isEmpty()) continue;
                long q; try {q=Long.parseLong(v);} catch(NumberFormatException ex) {toast("수량을 숫자로 입력해 주세요.");return;}
                if(q<0 || q>limits.get(e.getKey())) {toast("묶음별 최대 수량을 넘었습니다.");return;}
                if(q>0) {chosen.put(e.getKey(),q);sum+=q;} }
            if(sum>sell.qty) {toast("매도 수량("+sell.qty+"주)보다 많습니다.");return;}
            db.setPairs(scope,sell.id,chosen); notice="짝을 저장했습니다."; render();
        });
        b.show();
    }
    private void addOpeningLot(Map<String,String> names,Pairing.Result r) {
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("기초 물량 추가");
        LinearLayout body=dialogBody(b); body.addView(text("조회 기간 이전에 산 물량을 매수 묶음으로 등록합니다.",13,MUTED));
        List<String> codes=new ArrayList<>(names.keySet()); List<String> labels=new ArrayList<>(); for(String c:codes) labels.add(names.get(c)+" ("+c+")");
        Spinner symbol=new Spinner(this); symbol.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); body.addView(symbol);
        String first=r.fills.isEmpty()?LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE):LocalDate.parse(r.fills.get(0).day,DateTimeFormatter.BASIC_ISO_DATE).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        EditText day=numberInput(body,"매수일 (예: 20260901)",first),price=numberInput(body,"매수 단가 (원)",""),qty=numberInput(body,"수량 (주)","");
        b.setNegativeButton("취소",null).setPositiveButton("추가",(d,w)->{
            try { String code=codes.get(symbol.getSelectedItemPosition());
                db.addOpeningLot(scope,code,names.get(code),day.getText().toString().trim(),price.getText().toString().trim(),Long.parseLong(qty.getText().toString().trim()));
                notice="기초 물량을 추가했습니다."; render();
            } catch(IllegalArgumentException e) {toast(e instanceof NumberFormatException?"가격과 수량을 확인해 주세요.":e.getMessage());}
            catch(Exception e) {toast("날짜·가격·수량을 확인해 주세요.");}
        });
        b.show();
    }
    private void journal() throws Exception {
        content.addView(heading("매매일지")); content.addView(text("매매 이유, 지킨 원칙, 다음에 바꿀 점.",14,MUTED));
        List<JSONObject> entries=db.journal(scope);
        if(entries.isEmpty()) { LinearLayout c=card(content); c.addView(text("아직 작성한 일지가 없습니다",18,TEXT)); c.addView(text("거래 내역에서 거래를 선택해 첫 일지를 남겨보세요.",14,MUTED)); button(c,"거래 내역 보기",()->{tab=2;render();},true); }
        for(JSONObject entry:entries) {
            JSONObject r=entry.optJSONObject("trade"); LinearLayout c=card(content); String title=r==null?"거래 "+entry.getString("id"):r.optString("stk_nm")+" · "+Numbers.date(r.optString("trde_dt"));
            c.addView(text(title,17,GREEN)); c.addView(text(entry.getString("note"),15,TEXT));
            c.setOnClickListener(v->editNote(entry.optString("id"),title));
        }
        button(content,"기록 백업 파일 저장",this::export,false);
    }
    private void editNote(String id,String title) {
        EditText e=new EditText(this); e.setText(db.note(scope,id)); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setHint("매매 이유\n당시 판단\n잘한 점과 개선할 점"); e.setMinLines(6); e.setGravity(Gravity.TOP); e.setPadding(dp(20),dp(16),dp(20),dp(16)); e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final String noteScope=scope;
        new AlertDialog.Builder(this).setTitle(title).setView(e).setNegativeButton("닫기",null).setPositiveButton("저장",(d,w)->{ db.note(noteScope,id,e.getText().toString()); notice="일지를 저장했습니다."; render(); }).show();
    }
    private void settings() throws Exception {
        content.addView(heading("계좌 연결")); content.addView(text("앱 버전  "+appVersion(),13,MUTED)); content.addView(text("인증키는 이 기기에 암호화해 보관합니다.",13,MUTED));
        LinearLayout c=card(content); JSONObject saved=null; try {saved=secrets.load();} catch(Exception e) {c.addView(text("저장된 키를 읽지 못했습니다. 다시 입력해 주세요.",13,RED));}
        final boolean hasSaved=saved!=null;
        c.addView(text(hasSaved?"인증키 저장됨 · 수정할 때만 입력":"키움 REST API 인증키",15,GREEN));
        EditText key=input(c,"App Key","",true),secret=input(c,"Secret Key","",true);
        Switch environment=new Switch(this); environment.setText("모의투자 계좌"); environment.setTextColor(TEXT); environment.setChecked(saved!=null&&saved.optBoolean("mock")); c.addView(environment);
        button(c,"인증키 저장",()->{
            try { String k=key.getText().toString().trim(),s=secret.getText().toString().trim();
                if(k.isEmpty() || s.isEmpty()) { toast("App Key와 Secret Key를 모두 입력해 주세요."); return; }
                String newScope=Records.scope(k,environment.isChecked()); secrets.save(k,s,environment.isChecked()); scope=newScope;mock=environment.isChecked(); persist(); key.setText("");secret.setText(""); notice="인증키를 저장했습니다. 아래에서 동기화하세요."; render();
            } catch(Exception e) {toast("인증정보를 저장하지 못했습니다.");}
        },true);
        c.addView(text("키움에 등록된 공인 IP에서 조회해야 합니다. Wi-Fi나 모바일망이 바뀌면 등록 IP를 확인해 주세요. 실전·모의 키는 각각 다릅니다.",12,MUTED));
        c.addView(text("다른 키로 변경하면 기록을 분리합니다. 키를 재발급했다면 이전 기록은 백업해 두세요.",12,MUTED));
        button(c,"연결 확인 · 인증만 테스트",this::testConnection,false);
        LinearLayout sync=card(content); sync.addView(text("거래 내역 동기화",18,TEXT));
        button(sync,"시작일  "+Numbers.date(from),()->pickDate(true),false); button(sync,"종료일  "+Numbers.date(to),()->pickDate(false),false);
        sync.addView(text("거래일 기준 조회 · 국내주식 KRX/NXT 거래 포함\n보유 종목 평가는 KRX 기준입니다.",12,MUTED));
        button(sync,"선택한 기간 가져오기",this::sync,true);
        button(content,"샘플 데이터로 둘러보기",this::demo,false);
        if(hasSaved && scope.equals("demo-v1")) button(content,"내 계좌 기록으로 돌아가기",()->{ try {JSONObject credentials=secrets.load();scope=Records.scope(credentials.getString("key"),credentials.getBoolean("mock"));mock=credentials.getBoolean("mock");persist();tab=0;render();} catch(Exception e){toast("저장된 키를 읽지 못했습니다.");}},false);
        button(content,"기록 백업 파일 저장",this::export,false);
        button(content,"현재 계좌의 저장 기록 삭제",()->new AlertDialog.Builder(this).setTitle("저장 기록 삭제").setMessage("현재 선택한 계좌의 거래·손익·일지를 이 기기에서 삭제합니다. 먼저 백업 파일을 저장해 주세요.").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{db.clearScope(scope);notice="저장 기록을 삭제했습니다.";render();}).show(),false);
        if(hasSaved) button(content,"인증키 삭제",()->new AlertDialog.Builder(this).setTitle("연결 해제").setMessage("저장된 인증키를 삭제합니다. 거래 기록과 일지는 기기에 남습니다.").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{notice=secrets.clear()?"인증키를 삭제했습니다.":"인증키 삭제를 완료하지 못했습니다.";render();}).show(),false);
    }
    private void pickDate(boolean start) {
        LocalDate date=LocalDate.parse(start?from:to,DateTimeFormatter.BASIC_ISO_DATE);
        DatePickerDialog d=new DatePickerDialog(this,(p,y,m,day)->{ String value=LocalDate.of(y,m+1,day).format(DateTimeFormatter.BASIC_ISO_DATE); if(start) from=value;else to=value;persist();render(); },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        d.getDatePicker().setMaxDate(System.currentTimeMillis()); d.show();
    }
    private void persist() { getPreferences(0).edit().putString("scope",scope).putBoolean("mock",mock).putString("from",from).putString("to",to).apply(); }
    private void testConnection() {
        if(busy)return;
        final JSONObject credentials;
        try {credentials=secrets.load();if(credentials==null){toast("먼저 인증키를 저장해 주세요.");return;}}
        catch(Exception e){toast("저장된 인증키를 읽지 못했습니다. 다시 저장해 주세요.");return;}
        busy=true;notice="인증 연결을 확인하는 중입니다.";render();activeApi=new KiwoomApi(credentials.optBoolean("mock"));
        worker.execute(()->{
            String message;
            try {activeApi.authenticate(credentials.getString("key"),credentials.getString("secret"));message="인증 성공 · 현재 네트워크에서 키움 토큰 발급이 가능합니다. 이제 기간을 선택해 거래 내역을 조회하세요.";}
            catch(KiwoomApi.ApiException e){message=e.getMessage();}
            catch(Exception e){message="인증 확인을 완료하지 못했습니다. 다시 시도해 주세요.";}
            final String result=message;runOnUiThread(()->{if(isDestroyed()||isFinishing())return;busy=false;activeApi=null;notice=result;render();});
        });
    }
    private void sync() {
        if(busy) return;
        if(from.compareTo(to)>0) {toast("시작일은 종료일보다 늦을 수 없습니다.");return;}
        if(LocalDate.parse(from,DateTimeFormatter.BASIC_ISO_DATE).plusDays(366).isBefore(LocalDate.parse(to,DateTimeFormatter.BASIC_ISO_DATE))) {toast("한 번에 1년 이내의 기간을 조회해 주세요.");return;}
        final JSONObject credentials; final String targetScope; final String start=from,end=to;
        try { credentials=secrets.load(); if(credentials==null) {toast("먼저 인증키를 저장해 주세요.");return;} targetScope=Records.scope(credentials.getString("key"),credentials.getBoolean("mock")); }
        catch(Exception e) {toast("저장된 키를 읽지 못했습니다. 다시 입력해 주세요.");return;}
        busy=true; notice=""; render();
        activeApi=new KiwoomApi(credentials.optBoolean("mock"));
        worker.execute(()->{
            String result; boolean success=false;
            try { KiwoomApi.Snapshot snapshot=activeApi.sync(credentials.getString("key"),credentials.getString("secret"),start,end); db.snapshot(targetScope,start,end,snapshot); result="동기화 완료 · 거래 "+snapshot.trades.length()+"건"; success=true; }
            catch(KiwoomApi.ApiException e) {result=e.getMessage();}
            catch(IllegalArgumentException e) {result="조회 데이터 검증에 실패했습니다. 기존 기록은 유지됩니다.";}
            catch(Exception e) {result="동기화하지 못했습니다. 인터넷·인증키·등록 IP를 확인해 주세요. 기존 기록은 유지됩니다.";}
            final String message=result; final boolean ok=success;
            runOnUiThread(()->{ if(isDestroyed() || isFinishing()) return;busy=false;activeApi=null;if(ok){scope=targetScope;mock=credentials.optBoolean("mock");persist();tab=0;}notice=message;render(); });
        });
    }
    private void demo() {
        if(busy)return;
        try(InputStream in=getAssets().open("demo.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int read;
            while((read=in.read(buffer))!=-1) bytes.write(buffer,0,read);
            JSONObject data=new JSONObject(bytes.toString("UTF-8"));
            scope="demo-v1";mock=false;
            db.snapshot(scope,"20261001","20261009",new KiwoomApi.Snapshot(data.getJSONArray("trades"),data.getJSONArray("profits"),data.getJSONArray("holdings"),data.getJSONObject("balance"),data.getJSONArray("symbolProfits")));
            if(db.note(scope,"20261006:000000002").isEmpty()) db.note(scope,"20261006:000000002","매도 이유: 계획한 목표가에 도달해 일부 정리.\n복기: 충동적으로 전량 매도하지 않고 계획을 지켰다.");
            persist(); tab=0;notice="샘플 데이터입니다. 실제 계좌 거래가 아닙니다.";render();
        } catch(Exception e) {toast("샘플을 불러오지 못했습니다.");}
    }
    private void export() {
        try { JSONObject pairs=new JSONObject(); for(Map.Entry<String,Map<String,Long>> e:db.manualPairs(scope).entrySet()) pairs.put(e.getKey(),new JSONObject(e.getValue()));
            JSONObject data=new JSONObject().put("format","stockdiary-v2").put("manualPairs",pairs).put("openingLots",new JSONArray(db.openingLots(scope))).put("exported",Instant.now().toString()).put("trades",new JSONArray(db.rows("trades",scope))).put("profits",new JSONArray(db.rows("profits",scope))).put("symbolProfits",new JSONArray(db.rows("symbol_profits",scope))).put("holdings",new JSONArray(db.rows("holdings",scope))).put("journal",new JSONArray(db.journal(scope))).put("meta",db.meta(scope));
            exportData=data.toString(2);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"stock-diary-"+LocalDate.now()+".json");startActivityForResult(i,101);
        } catch(Exception e) {toast("백업 파일을 준비하지 못했습니다.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==101 && result==RESULT_OK && data!=null && data.getData()!=null && exportData!=null) {
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())) { if(out==null)throw new IOException();out.write(exportData.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("백업 파일을 저장했습니다."); }
            catch(Exception e){toast("백업 파일을 저장하지 못했습니다.");}
        }
        exportData=null;
    }
    private void toast(String s) {Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
