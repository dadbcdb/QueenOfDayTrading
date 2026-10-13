package kr.stockdiary;

import org.junit.Test;
import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.*;

public class PairingTest {
    private static Pairing.Fill fill(String id,String day,long seq,String side,String price,long qty) {
        return new Pairing.Fill(id,"005930","삼성전자",day,seq,side,new BigDecimal(price),qty,false);
    }
    private static String describe(Pairing.Result r) {
        StringBuilder out=new StringBuilder();
        for(Pairing.Pair p:r.pairs) out.append(p.buy.id).append(">").append(p.sell.id).append(":").append(p.qty).append(p.manual?"m":"").append(" ");
        return out.toString().trim();
    }
    @Test public void sellsMatchMostRecentCheaperLotFirst() {
        List<Pairing.Fill> fills=Arrays.asList(
            fill("b1","20261001",1,"매수","70000",10),
            fill("b2","20261002",2,"매수","68000",3),
            fill("b3","20261003",3,"매수","66000",2),
            fill("s1","20261004",4,"매도","69000",4));
        Pairing.Result r=Pairing.run(fills,Collections.emptyMap());
        // 69,000 매도 → 70,000 묶음은 제외, 66,000(2주) → 68,000(2주) 순.
        assertEquals("b3>s1:2 b2>s1:2",describe(r));
        assertEquals(2,r.open.size());
        assertEquals(new BigDecimal("8000"),r.pairs.get(0).profit().add(r.pairs.get(1).profit()));
    }
    @Test public void heldAtShowsLotsBeforeLaterSellsCloseThem() {
        List<Pairing.Fill> fills=Arrays.asList(
            fill("b1","20261001",1,"매수","70000",10),
            fill("s1","20261002",2,"매도","74000",5),
            fill("b2","20261003",3,"매수","68000",3),
            fill("s2","20261004",4,"매도","71000",3));
        Pairing.Result r=Pairing.run(fills,Collections.emptyMap());
        List<Pairing.Lot> beforeSecondSell=r.heldAt(fills.get(2));
        assertEquals(2,beforeSecondSell.size());
        assertEquals(5,beforeSecondSell.get(0).remaining);assertEquals("b2",beforeSecondSell.get(1).buy.id);
        List<Pairing.Lot> end=r.heldAt(fills.get(3));
        assertEquals(1,end.size());assertEquals("b1",end.get(0).buy.id);
        assertEquals(10,r.heldAt(fills.get(0)).get(0).remaining);
    }
    @Test public void stopLossFallsBackToPlainLifo() {
        Pairing.Result r=Pairing.run(Arrays.asList(
            fill("b1","20261001",1,"매수","70000",1),
            fill("b2","20261002",2,"매수","72000",1),
            fill("s1","20261003",3,"매도","65000",1)),Collections.emptyMap());
        assertEquals("b2>s1:1",describe(r));
        assertEquals(new BigDecimal("-7000"),r.pairs.get(0).profit());
    }
    @Test public void buysAfterTheSellAreNeverUsedAndShortfallIsReported() {
        Pairing.Result r=Pairing.run(Arrays.asList(
            fill("s1","20261001",1,"매도","70000",5),
            fill("b1","20261002",2,"매수","60000",5)),Collections.emptyMap());
        assertTrue(r.pairs.isEmpty());
        assertEquals(Long.valueOf(5),r.unmatched.get("s1"));
        assertEquals(5,r.open.get(0).remaining);
    }
    @Test public void manualPairsWinAndRemainderIsAutomatic() {
        List<Pairing.Fill> fills=Arrays.asList(
            fill("b1","20261001",1,"매수","70000",10),
            fill("b2","20261002",2,"매수","68000",3),
            fill("s1","20261003",3,"매도","75000",5),
            fill("s2","20261004",4,"매도","69000",3));
        Map<String,Map<String,Long>> manual=new HashMap<>();
        manual.put("s1",new LinkedHashMap<>(Collections.singletonMap("b1",4L)));
        Pairing.Result r=Pairing.run(fills,manual);
        // s1: 수동 b1 4주 + 자동 1주(최근 b2). s2: 69,000보다 싼 b2 남은 2주, 부족분 1주는 손절 LIFO로 b1.
        assertEquals("b1>s1:4m b2>s1:1 b2>s2:2 b1>s2:1",describe(r));
        assertEquals(5,r.open.get(0).remaining);
    }
    @Test public void invalidManualPairIsIgnoredWithIssue() {
        Map<String,Map<String,Long>> manual=new HashMap<>();
        manual.put("s1",Collections.singletonMap("b2",1L));
        Pairing.Result r=Pairing.run(Arrays.asList(
            fill("b1","20261001",1,"매수","70000",1),
            fill("s1","20261002",2,"매도","71000",1),
            fill("b2","20261003",3,"매수","60000",1)),manual);
        assertEquals(1,r.issues.size());
        assertEquals("b1>s1:1",describe(r));
    }
    @Test public void openingLotsSortBeforeSameDayTradesAndCodesAreNormalized() throws Exception {
        org.json.JSONObject open=new org.json.JSONObject().put("id","open:x").put("stk_cd","A005930").put("stk_nm","삼성전자").put("day","20261001").put("price","65000").put("qty",2);
        Pairing.Fill lot=Pairing.fromOpening(open);
        Pairing.Result r=Pairing.run(Arrays.asList(fill("s1","20261001",1,"매도","70000",2),lot),Collections.emptyMap());
        assertEquals("005930",lot.code);
        assertEquals("open:x>s1:2",describe(r));
    }
}
