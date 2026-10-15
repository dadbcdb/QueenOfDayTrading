package kr.stockdiary;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only authentication, the six account query TRs and the current-price query are available. */
public final class KiwoomApi {
    private final String base;
    private long lastCall;
    private volatile boolean cancelled;
    public KiwoomApi(boolean mock) { this(mock ? "https://mockapi.kiwoom.com" : "https://api.kiwoom.com"); }
    KiwoomApi(String base) { this.base = base; }
    public void cancel() { cancelled = true; }
    /** 일시적 오류의 재시도 대기(ms). 시도마다 두 배로 늘린다. */
    long retryDelay = 2000; static final int RETRIES = 3;
    public static final class ApiException extends IOException {
        /** 연결 끊김·서버 과부하·요청 한도처럼 잠시 뒤 다시 하면 될 수 있는 오류. */
        final boolean temporary;
        public ApiException(String message) { this(message,false); }
        ApiException(String message,boolean temporary) { super(message); this.temporary=temporary; }
    }
    public static final class Snapshot {
        public final JSONArray trades, profits, holdings, symbolProfits;
        public final JSONObject balance;
        /** trades에 포함된 결제 전 체결 수와, 가져오지 못했을 때의 안내. */
        public int pending; public String pendingNotice="";
        public Snapshot(JSONArray t, JSONArray p, JSONArray h, JSONObject b) { this(t,p,h,b,new JSONArray()); }
        public Snapshot(JSONArray t, JSONArray p, JSONArray h, JSONObject b, JSONArray sp) { trades=t; profits=p; holdings=h; balance=b; symbolProfits=sp; }
    }
    private static final class Response {
        JSONObject body; String continuation, next;
    }
    private static String stage(String id) {
        switch(id) {
            case "kt00015": return "거래 내역 조회";
            case "kt00007": return "체결 내역 조회";
            case "kt00009": return "체결 시각 조회";
            case "ka10001": return "현재가 조회";
            case "ka10074": return "일별 손익 조회";
            case "ka10073": return "종목별 손익 조회";
            case "kt00004": return "보유 종목 조회";
            default: return "인증·토큰 발급";
        }
    }
    private static String advice(String message) {
        String m=message.toLowerCase(Locale.ROOT);
        if(m.contains("ip") || m.contains("아이피")) return "키움이 IP 관련 오류를 반환했습니다. 현재 연결망의 공인 IP와 키움 등록 IP를 확인해 주세요.";
        if(m.contains("appkey") || m.contains("secret") || m.contains("앱키")) return "키움이 인증키 관련 오류를 반환했습니다. 키를 확인하고 실전·모의 구분을 맞춰 다시 저장해 주세요.";
        if(m.contains("계좌")) return "키움이 계좌 관련 오류를 반환했습니다. API에 등록한 계좌와 사용 신청 상태를 확인해 주세요.";
        if(m.contains("기간") || m.contains("일자")) return "키움이 조회 기간 관련 오류를 반환했습니다. 기간을 줄여 다시 조회해 주세요.";
        return "인증키의 실전·모의 구분, 등록 IP, API 사용 상태와 조회 기간을 확인해 주세요.";
    }
    static String errorDescription(String id,int code,String message) {
        // Read only a numeric detail code; never display arbitrary server text.
        java.util.regex.Matcher detail=java.util.regex.Pattern.compile("\\[\\s*(\\d{3,5})\\s*:|CODE\\s*=\\s*(\\d{3,5})",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        Integer embedded=null;
        if(detail.find()) embedded=Integer.valueOf(detail.group(1)!=null?detail.group(1):detail.group(2));
        int reason=embedded==null?code:embedded;
        String help;
        switch(reason) {
            case 8001: case 8002: help="App Key와 Secret Key 검증에 실패했습니다. 같은 계좌에서 발급한 키 한 쌍을 정확히 입력해 다시 저장해 주세요.";break;
            case 8020: help="인증키 입력값이 누락되었습니다. App Key와 Secret Key를 모두 다시 저장해 주세요.";break;
            case 8030: case 8031: help="실전·모의 구분이 맞지 않습니다. 실전 키라면 모의투자 설정을 끄고, 두 키를 다시 입력해 저장해 주세요.";break;
            case 8010: help="토큰을 발급받은 IP와 조회 요청 IP가 다릅니다. Wi-Fi와 모바일망 전환을 멈춘 뒤 다시 시도해 주세요.";break;
            case 8040: case 8050: case 8103: help="단말기 또는 토큰 인증에 실패했습니다. 키움의 허용 IP·인증 설정과 현재 연결망을 확인해 주세요.";break;
            case 8003: case 8005: case 8006: case 8009: help="접근토큰 조회·발급·검증에 실패했습니다. 다시 시도하고 반복되면 키움 API 설정을 확인해 주세요.";break;
            case 8104: help="이 조회는 모의투자에서 지원되지 않습니다. 실제 거래 내역에는 실전 키를 사용해 주세요.";break;
            case 1700: case 1701: case 1702: help="조회 요청 한도를 초과했습니다. 잠시 기다린 뒤 다시 시도해 주세요.";break;
            default: help=advice(message);
        }
        return stage(id)+" 실패 (코드 "+code+(embedded==null?" · 상세코드 없음":" · 상세 "+embedded)+"). "+help;
    }
    /** 조회만 하므로 같은 요청을 다시 보내도 안전하다. 일시적 오류는 2·4·8초 쉬고 최대 3번 다시 보낸다. */
    private Response call(String path, String id, String token, JSONObject body, String next) throws Exception {
        for (int attempt=0;;attempt++) {
            try { return attempt(path,id,token,body,next); }
            catch(ApiException e) {
                if (!e.temporary || attempt>=RETRIES) throw e;
                long until=System.currentTimeMillis()+(retryDelay<<attempt);
                while (System.currentTimeMillis()<until) { if (cancelled) throw new InterruptedException(); Thread.sleep(Math.min(200,Math.max(1,until-System.currentTimeMillis()))); }
            }
        }
    }
    private Response attempt(String path, String id, String token, JSONObject body, String next) throws Exception {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long wait = 1100 - (System.currentTimeMillis() - lastCall);
        if (wait > 0) Thread.sleep(wait);
        lastCall = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (!id.isEmpty()) c.setRequestProperty("api-id", id);
        if (!token.isEmpty()) c.setRequestProperty("authorization", "Bearer " + token);
        c.setRequestProperty("cont-yn", next.isEmpty() ? "N" : "Y"); c.setRequestProperty("next-key", next);
        try {
            try (OutputStream out=c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status=c.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(stage(id)+" 실패 (HTTP " + status + "). 키움 서버에 요청이 거절되었습니다. 등록 IP와 API 사용 상태를 확인해 주세요.",status==429 || status>=500);
            StringBuilder raw=new StringBuilder();
            try (BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line=r.readLine())!=null) { raw.append(line); if(raw.length()>8_000_000) throw new ApiException("응답 크기가 너무 큽니다. 조회 기간을 줄여 주세요."); }
            }
            Response result=new Response(); result.body=new JSONObject(raw.toString());
            boolean tokenWithoutCode=id.isEmpty() && !result.body.optString("token").trim().isEmpty() && !result.body.has("return_code");
            if (!tokenWithoutCode && (!result.body.has("return_code") || result.body.getInt("return_code")!=0)) {
                // Do not expose server text that might contain credentials or account details.
                String reason=errorDescription(id,result.body.optInt("return_code",-1),result.body.optString("return_msg"));
                throw new ApiException(reason,reason.contains("요청 한도"));
            }
            result.continuation=c.getHeaderField("cont-yn"); result.next=c.getHeaderField("next-key");
            return result;
        } catch(UnknownHostException e) {
            throw new ApiException(stage(id)+" 실패: 키움 서버 주소를 찾지 못했습니다. 인터넷·DNS·VPN 설정을 확인해 주세요.",true);
        } catch(SocketTimeoutException e) {
            throw new ApiException(stage(id)+" 실패: 연결 시간이 초과됐습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.",true);
        } catch(javax.net.ssl.SSLException e) {
            throw new ApiException(stage(id)+" 실패: 보안 연결 오류입니다. 폰의 날짜·시간 자동 설정과 VPN 설정을 확인해 주세요.");
        } catch(org.json.JSONException e) {
            throw new ApiException(stage(id)+" 실패: 키움 응답을 해석하지 못했습니다. 앱 업데이트가 필요할 수 있습니다.");
        } catch(ApiException e) { throw e;
        } catch(IOException e) {
            throw new ApiException(stage(id)+" 실패: 통신 연결이 끊겼습니다. 인터넷 연결을 확인해 주세요.",true);
        } finally { c.disconnect(); }
    }
    private JSONArray pages(String id, String token, JSONObject request, String field, JSONObject summary) throws Exception {
        JSONArray rows=new JSONArray(); String next=""; Set<String> seen=new HashSet<>();
        for (int page=0; page<1000; page++) {
            Response response=call("/api/dostk/acnt", id, token, request, next);
            if (page==0 && summary!=null) {
                Iterator<String> keys=response.body.keys();
                while(keys.hasNext()) { String k=keys.next(); if(!k.equals(field)) summary.put(k,response.body.get(k)); }
            }
            if(!response.body.has(field) || !(response.body.get(field) instanceof JSONArray))
                throw new ApiException(stage(id)+" 실패: 응답 형식이 예상과 다릅니다. 저장된 내역은 유지됩니다.");
            JSONArray batch=response.body.getJSONArray(field);
            for(int i=0;i<batch.length();i++) rows.put(batch.getJSONObject(i));
            if(!"Y".equalsIgnoreCase(response.continuation)) return rows;
            next=response.next;
            if(next==null || next.isEmpty() || !seen.add(next)) throw new ApiException("연속조회가 완료되지 않았습니다. 저장된 내역은 유지됩니다.");
        }
        throw new ApiException("조회 페이지 한도를 초과했습니다. 기간을 줄여 주세요.");
    }
    public String authenticate(String key,String secret) throws Exception {
        JSONObject auth=new JSONObject().put("grant_type","client_credentials").put("appkey",key).put("secretkey",secret);
        String token=call("/oauth2/token","", "",auth, "").body.optString("token");
        if(token.trim().isEmpty()) throw new ApiException("인증·토큰 발급 실패: 키움 응답에 접근토큰이 없습니다.");
        return token;
    }
    public Snapshot sync(String key, String secret, String from, String to) throws Exception {
        return fetch(authenticate(key,secret),from,to);
    }
    /** 발급받은 토큰으로 한 구간(1년 이내)을 조회한다. 여러 구간을 이어 받을 때 토큰을 다시 발급하지 않는다. */
    public Snapshot fetch(String token, String from, String to) throws Exception {
        JSONObject dates=new JSONObject().put("strt_dt",from).put("end_dt",to);
        JSONObject ledger=new JSONObject(dates.toString()).put("tp","3").put("stk_cd", "").put("crnc_cd","KRW")
            .put("gds_tp","1").put("frgn_stex_code", "").put("dmst_stex_tp","%").put("qry_sort_tp","2");
        JSONArray trades=withoutBlankRows(pages("kt00015",token,ledger,"trst_ovrl_trde_prps_array",null),"trde_dt");
        List<JSONObject> pending=null; String pendingNotice="";
        try { pending=pendingFills(token,from,to,trades); }
        catch(ApiException|IllegalArgumentException|org.json.JSONException e) { pendingNotice="결제 전 체결을 가져오지 못했습니다. 이전에 받은 결제 전 체결은 유지됩니다."; }
        if(pending!=null) for(JSONObject p:pending) trades.put(p);
        JSONArray profits=withoutBlankRows(pages("ka10074",token,dates,"dt_rlzt_pl",null),"dt");
        JSONArray symbolProfits=withoutBlankRows(pages("ka10073",token,dates,"dt_stk_rlzt_pl",null),"dt");
        JSONObject balance=new JSONObject();
        JSONArray holdings=pages("kt00004",token,new JSONObject().put("qry_tp","0").put("dmst_stex_tp","KRX"),"stk_acnt_evlt_prst",balance);
        if(cancelled) throw new InterruptedException();
        Snapshot result=new Snapshot(trades,profits,holdings,balance,symbolProfits);
        result.pending=pending==null?0:pending.size(); result.pendingNotice=pendingNotice;
        return result;
    }
    /** 조회 결과가 없으면 키움은 빈 배열 대신 값이 모두 비거나 0인 행 하나를 준다. 날짜가 비고 나머지도 비거나 0인 행만 버린다. */
    static JSONArray withoutBlankRows(JSONArray rows,String dateField) throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<rows.length();i++) {
            JSONObject r=rows.getJSONObject(i); boolean blank=r.optString(dateField).trim().isEmpty();
            for(Iterator<String> keys=r.keys(); blank && keys.hasNext();) {
                String v=r.optString(keys.next()).trim();
                if(!v.isEmpty() && !v.matches("[-+]?0*(\\.0*)?%?")) blank=false;
            }
            if(!blank) out.put(r);
        }
        return out;
    }
    /** 현재가(ka10001). 키움은 전일 대비 방향을 부호로 붙이므로 절댓값을 쓴다. */
    public java.math.BigDecimal currentPrice(String token,String code) throws Exception {
        java.math.BigDecimal price=Numbers.parse(call("/api/dostk/stkinfo","ka10001",token,new JSONObject().put("stk_cd",code),"").body.optString("cur_prc")).abs();
        if(price.signum()==0) throw new ApiException(stage("ka10001")+" 실패: 현재가가 비어 있습니다.");
        return price;
    }
    /**
     * 하루치 체결된 주문(kt00007). kt00007에는 주문 시각만 있어, 계좌별주문체결현황(kt00009)의 체결시간 중
     * 그 주문의 마지막 체결 시각을 cntr_tm으로 붙인다. kt00009를 못 받으면 주문 시각만 남긴다.
     */
    public JSONArray orderFills(String token,String day) throws Exception {
        JSONObject request=new JSONObject().put("ord_dt",day).put("qry_tp","4").put("stk_bond_tp","1").put("sell_tp","0").put("stk_cd","").put("fr_ord_no","").put("dmst_stex_tp","%");
        JSONArray orders=pages("kt00007",token,request,"acnt_ord_cntr_prps_dtl",null);
        if(orders.length()==0) return orders;
        Map<String,String> last=new HashMap<>();
        try {
            JSONObject status=new JSONObject().put("ord_dt",day).put("stk_bond_tp","1").put("mrkt_tp","0").put("sell_tp","0").put("qry_tp","1").put("stk_cd","").put("fr_ord_no","").put("dmst_stex_tp","%");
            JSONArray fills=pages("kt00009",token,status,"acnt_ord_cntr_prst_array",null);
            for(int i=0;i<fills.length();i++) { JSONObject f=fills.getJSONObject(i); String no=f.optString("ord_no").trim().replaceFirst("^0+",""),tm=Records.time(f.optString("cntr_tm"));
                if(no.isEmpty() || tm.isEmpty() || Numbers.parse(f.optString("cntr_qty")).signum()<=0) continue;
                if(tm.compareTo(last.getOrDefault(no,""))>0) last.put(no,tm); }
        } catch(ApiException|IllegalArgumentException|org.json.JSONException e) { last.clear(); }
        for(int i=0;i<orders.length();i++) { JSONObject o=orders.getJSONObject(i); String tm=last.get(o.optString("ord_no").trim().replaceFirst("^0+","")); if(tm!=null) o.put("cntr_tm",tm); }
        return orders;
    }
    /** kt00015는 결제(T+2)된 거래만 준다. 마지막 결제 체결일 이후의 평일은 주문체결내역(kt00007)으로 채운다. */
    private List<JSONObject> pendingFills(String token,String from,String to,JSONArray settled) throws Exception {
        String last="";
        for(int i=0;i<settled.length();i++) { JSONObject r=settled.getJSONObject(i); String d=r.optString("cntr_dt"); if(!d.matches("\\d{8}")) d=r.optString("trde_dt"); if(d.compareTo(last)>0) last=d; }
        java.time.format.DateTimeFormatter fmt=java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
        java.time.LocalDate end=java.time.LocalDate.parse(to,fmt),day=java.time.LocalDate.parse(from,fmt);
        // 결제는 길어야 연휴를 포함해 열흘 안에 끝난다. 그보다 오래된 날은 조회하지 않는다.
        if(day.isBefore(end.minusDays(10))) day=end.minusDays(10);
        List<JSONObject> out=new ArrayList<>();
        for(;!day.isAfter(end);day=day.plusDays(1)) {
            String d=day.format(fmt);
            if(d.compareTo(last)<=0 || day.getDayOfWeek()==java.time.DayOfWeek.SATURDAY || day.getDayOfWeek()==java.time.DayOfWeek.SUNDAY) continue;
            JSONArray rows=orderFills(token,d); Map<String,Integer> seen=new HashMap<>();
            for(int i=0;i<rows.length();i++) { JSONObject p=Records.pendingTrade(d,rows.getJSONObject(i),seen); if(p!=null) out.add(p); }
        }
        return out;
    }
}
