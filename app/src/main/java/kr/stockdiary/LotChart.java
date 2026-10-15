package kr.stockdiary;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.math.BigDecimal;
import java.util.*;

/** 종목 하나의 매수(▲)·매도(▼)를 체결 순서대로 놓고 짝을 선으로 잇는다. 끌어서 기준 시점을 고른다. */
final class LotChart extends View {
    interface OnCursor { void moved(Pairing.Fill at); }
    static final int BUY=Color.rgb(122,226,182), SELL=Color.rgb(255,145,145), TEXT=Color.rgb(238,244,248), MUTED=Color.rgb(161,176,189), GRID=Color.rgb(42,57,68), CURSOR=Color.rgb(255,214,102), QUOTE=Color.rgb(125,190,255);
    /** 현재가. 없으면 null. */
    private BigDecimal quote;

    private final float density;
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG), fill=new Paint(Paint.ANTI_ALIAS_FLAG), label=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private Pairing.Result result; private List<Pairing.Fill> fills=Collections.emptyList(); private final List<Pairing.Pair> pairs=new ArrayList<>();
    private final DashPathEffect dash;
    private double low,high; private int cursor=-1; private OnCursor listener;

    LotChart(Context c) { super(c); density=c.getResources().getDisplayMetrics().density; label.setTextSize(11*density); dash=new DashPathEffect(new float[]{dp(5),dp(4)},0); }
    private float dp(float n) { return n*density; }

    void show(Pairing.Result r,String code,OnCursor l) {
        result=r; listener=l; fills=r.fillsOf(code); pairs.clear();
        for(Pairing.Pair p:r.pairs) if(p.sell.code.equals(code)) pairs.add(p);
        range(); cursor=fills.size()-1; notifyCursor(); invalidate();
    }
    /** 현재가 선을 그린다. 세로 범위에 현재가도 들어가게 다시 잡는다. */
    void setQuote(BigDecimal price) { quote=price; range(); notifyCursor(); invalidate(); }
    BigDecimal quote() { return quote; }
    private void range() {
        low=Double.MAX_VALUE; high=-Double.MAX_VALUE;
        for(Pairing.Fill f:fills) { low=Math.min(low,f.price.doubleValue()); high=Math.max(high,f.price.doubleValue()); }
        if(quote!=null) { low=Math.min(low,quote.doubleValue()); high=Math.max(high,quote.doubleValue()); }
        double pad=Math.max((high-low)*.12,high*.01); low-=pad; high+=pad;
    }
    int cursorIndex() { return cursor; }
    void moveCursor(int index) { if(fills.isEmpty()) return; index=Math.max(0,Math.min(fills.size()-1,index)); if(index!=cursor) { cursor=index; notifyCursor(); invalidate(); } }
    private void notifyCursor() { if(listener!=null && cursor>=0) listener.moved(fills.get(cursor)); }

    private float left() { return dp(10); }
    private float right() { return getWidth()-dp(62); }
    private float top() { return dp(14); }
    private float bottom() { return getHeight()-dp(26); }
    private float x(int i) { return fills.size()<2?(left()+right())/2:left()+dp(14)+(right()-left()-dp(28))*i/(fills.size()-1); }
    private float y(BigDecimal price) { return (float)(bottom()-(price.doubleValue()-low)/(high-low)*(bottom()-top())); }
    private float size(long qty) { return dp(5)+dp(2.2f)*(float)Math.min(5,Math.log(1+qty)); }

    @Override protected void onMeasure(int w,int h) { setMeasuredDimension(MeasureSpec.getSize(w),(int)dp(300)); }

    @Override protected void onDraw(Canvas c) {
        if(fills.isEmpty()) return;
        line.setPathEffect(null); line.setStyle(Paint.Style.STROKE); label.setTextAlign(Paint.Align.LEFT);
        // 가격 눈금
        line.setColor(GRID); line.setStrokeWidth(dp(1)); label.setColor(MUTED);
        for(int i=0;i<=4;i++) { float gy=top()+(bottom()-top())*i/4; c.drawLine(left(),gy,right(),gy,line);
            c.drawText(Numbers.format(BigDecimal.valueOf(high-(high-low)*i/4).setScale(0,java.math.RoundingMode.HALF_UP)),right()+dp(6),gy+dp(4),label); }
        // 끝까지 남은 묶음: 매수가에서 오른쪽 끝까지 점선 = 매도 기준선
        line.setPathEffect(dash); line.setStrokeWidth(dp(1.5f));
        for(Pairing.Lot l:result.open) { if(!l.buy.code.equals(fills.get(0).code)) continue;
            float ly=y(l.buy.price); line.setColor(withAlpha(BUY,150)); c.drawLine(x(fills.indexOf(l.buy)),ly,right(),ly,line); }
        // 현재가: 파란 점선과 오른쪽 눈금 자리에 가격
        if(quote!=null) { float qy=y(quote); line.setColor(QUOTE); line.setStrokeWidth(dp(1.5f)); c.drawLine(left(),qy,right(),qy,line);
            label.setColor(QUOTE); label.setTextAlign(Paint.Align.LEFT); c.drawText(Numbers.format(quote),right()+dp(6),qy-dp(3),label); }
        line.setPathEffect(null);
        // 짝: 매수 → 매도 실선. 수익은 밝게, 손실은 붉게.
        for(Pairing.Pair p:pairs) {
            int bi=fills.indexOf(p.buy), si=fills.indexOf(p.sell); if(bi<0 || si<0) continue;
            line.setColor(p.profit().signum()<0?withAlpha(SELL,170):withAlpha(TEXT,120)); line.setStrokeWidth(dp(1)+dp(.6f)*(float)Math.min(4,Math.log(1+p.qty)));
            c.drawLine(x(bi),y(p.buy.price),x(si),y(p.sell.price),line);
        }
        // 기준 시점
        if(cursor>=0) { line.setColor(CURSOR); line.setStrokeWidth(dp(1.5f)); float cx=x(cursor); c.drawLine(cx,top()-dp(6),cx,bottom(),line);
            fill.setColor(CURSOR); label.setColor(CURSOR); label.setTextAlign(cx>right()-dp(40)?Paint.Align.RIGHT:Paint.Align.LEFT);
            for(Pairing.Lot l:result.heldAt(fills.get(cursor))) { float ly=y(l.buy.price); c.drawCircle(cx,ly,dp(3.5f),fill); c.drawText(l.remaining+"주",cx+(cx>right()-dp(40)?-dp(7):dp(7)),ly-dp(5),label); } }
        // 커서 체결과 현재가의 차이: 체결 가격에서 현재가 선까지 세로 점선과 주당 차이·등락률
        if(cursor>=0 && quote!=null) { Pairing.Fill f=fills.get(cursor); float cx=x(cursor),fy=y(f.price),qy=y(quote);
            BigDecimal per=quote.subtract(f.price); boolean good=f.side.equals("매수")?per.signum()>=0:per.signum()<=0;
            line.setPathEffect(dash); line.setColor(QUOTE); line.setStrokeWidth(dp(1.2f)); c.drawLine(cx,fy,cx,qy,line); line.setPathEffect(null);
            String txt=(per.signum()>0?"+":"")+Numbers.format(per)+(f.price.signum()==0?"":String.format(java.util.Locale.KOREA," %+.1f%%",per.doubleValue()*100/f.price.doubleValue()));
            label.setColor(good?BUY:SELL); boolean leftSide=cx>right()-dp(90); label.setTextAlign(leftSide?Paint.Align.RIGHT:Paint.Align.LEFT);
            c.drawText(txt,cx+(leftSide?-dp(8):dp(8)),(fy+qy)/2+dp(4),label); }
        // 체결 표시와 수량
        for(int i=0;i<fills.size();i++) { Pairing.Fill f=fills.get(i); boolean buy=f.side.equals("매수"); float fx=x(i),fy=y(f.price),s=size(f.qty);
            path.reset(); if(buy) { path.moveTo(fx,fy-s); path.lineTo(fx+s,fy+s*.8f); path.lineTo(fx-s,fy+s*.8f); } else { path.moveTo(fx,fy+s); path.lineTo(fx+s,fy-s*.8f); path.lineTo(fx-s,fy-s*.8f); } path.close();
            fill.setColor(buy?BUY:SELL); fill.setAlpha(f.opening?140:255); c.drawPath(path,fill);
            label.setColor(TEXT); label.setTextAlign(Paint.Align.CENTER); c.drawText(f.qty+"",fx,buy?fy+s+dp(12):fy-s-dp(4),label);
        }
        // 날짜: 처음·끝·기준 시점
        label.setColor(MUTED); label.setTextAlign(Paint.Align.LEFT); c.drawText(shortDate(fills.get(0)),left(),getHeight()-dp(8),label);
        label.setTextAlign(Paint.Align.RIGHT); c.drawText(shortDate(fills.get(fills.size()-1)),right(),getHeight()-dp(8),label);
        if(cursor>0 && cursor<fills.size()-1) { label.setColor(CURSOR); label.setTextAlign(Paint.Align.CENTER); c.drawText(shortDate(fills.get(cursor)),x(cursor),getHeight()-dp(8),label); }
    }
    private static String shortDate(Pairing.Fill f) { return f.day.substring(4,6)+"."+f.day.substring(6); }
    private static int withAlpha(int color,int alpha) { return (color&0x00ffffff)|(alpha<<24); }

    private float downX,downY; private boolean dragging;
    private void cursorAt(float px) { int best=0; for(int i=1;i<fills.size();i++) if(Math.abs(x(i)-px)<Math.abs(x(best)-px)) best=i; moveCursor(best); }
    /** 오른쪽 가격 눈금은 터치를 받지 않아 화면이 스크롤된다. 그래프는 옆으로 끌면 시점을 고르고, 위아래로 끌면 화면 스크롤에 넘긴다. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(fills.isEmpty()) return false;
        int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(e.getX()>right()) return false;
                downX=e.getX(); downY=e.getY(); dragging=false; return true;
            case MotionEvent.ACTION_MOVE:
                float dx=Math.abs(e.getX()-downX),dy=Math.abs(e.getY()-downY);
                if(!dragging && dx>slop && dx>dy) { dragging=true; getParent().requestDisallowInterceptTouchEvent(true); }
                if(dragging) cursorAt(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if(!dragging) { cursorAt(e.getX()); performClick(); }
                dragging=false; return true;
            default: dragging=false; return true;
        }
    }
    @Override public boolean performClick() { return super.performClick(); }
}
