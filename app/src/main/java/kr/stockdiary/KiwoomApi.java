package kr.stockdiary;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only authentication and the four account query TRs are available. */
public final class KiwoomApi {
    private final String base;
    private long lastCall;
    private volatile boolean cancelled;
    public KiwoomApi(boolean mock) { this(mock ? "https://mockapi.kiwoom.com" : "https://api.kiwoom.com"); }
    KiwoomApi(String base) { this.base = base; }
    public void cancel() { cancelled = true; }
    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }
    public static final class Snapshot {
        public final JSONArray trades, profits, holdings, symbolProfits;
        public final JSONObject balance;
        public Snapshot(JSONArray t, JSONArray p, JSONArray h, JSONObject b) { this(t,p,h,b,new JSONArray()); }
        public Snapshot(JSONArray t, JSONArray p, JSONArray h, JSONObject b, JSONArray sp) { trades=t; profits=p; holdings=h; balance=b; symbolProfits=sp; }
    }
    private static final class Response {
        JSONObject body; String continuation, next;
    }
    private static String stage(String id) {
        switch(id) {
            case "kt00015": return "거래 내역 조회";
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
    private Response call(String path, String id, String token, JSONObject body, String next) throws Exception {
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
            if (status < 200 || status >= 300) throw new ApiException(stage(id)+" 실패 (HTTP " + status + "). 키움 서버에 요청이 거절되었습니다. 등록 IP와 API 사용 상태를 확인해 주세요.");
            StringBuilder raw=new StringBuilder();
            try (BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line=r.readLine())!=null) { raw.append(line); if(raw.length()>8_000_000) throw new ApiException("응답 크기가 너무 큽니다. 조회 기간을 줄여 주세요."); }
            }
            Response result=new Response(); result.body=new JSONObject(raw.toString());
            boolean tokenWithoutCode=id.isEmpty() && !result.body.optString("token").trim().isEmpty() && !result.body.has("return_code");
            if (!tokenWithoutCode && (!result.body.has("return_code") || result.body.getInt("return_code")!=0)) {
                // Do not expose server text that might contain credentials or account details.
                throw new ApiException(errorDescription(id,result.body.optInt("return_code",-1),result.body.optString("return_msg")));
            }
            result.continuation=c.getHeaderField("cont-yn"); result.next=c.getHeaderField("next-key");
            return result;
        } catch(UnknownHostException e) {
            throw new ApiException(stage(id)+" 실패: 키움 서버 주소를 찾지 못했습니다. 인터넷·DNS·VPN 설정을 확인해 주세요.");
        } catch(SocketTimeoutException e) {
            throw new ApiException(stage(id)+" 실패: 연결 시간이 초과됐습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.");
        } catch(javax.net.ssl.SSLException e) {
            throw new ApiException(stage(id)+" 실패: 보안 연결 오류입니다. 폰의 날짜·시간 자동 설정과 VPN 설정을 확인해 주세요.");
        } catch(org.json.JSONException e) {
            throw new ApiException(stage(id)+" 실패: 키움 응답을 해석하지 못했습니다. 앱 업데이트가 필요할 수 있습니다.");
        } catch(ApiException e) { throw e;
        } catch(IOException e) {
            throw new ApiException(stage(id)+" 실패: 통신 연결이 끊겼습니다. 인터넷 연결을 확인해 주세요.");
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
        String token=authenticate(key,secret);
        JSONObject dates=new JSONObject().put("strt_dt",from).put("end_dt",to);
        JSONObject ledger=new JSONObject(dates.toString()).put("tp","3").put("stk_cd", "").put("crnc_cd","KRW")
            .put("gds_tp","1").put("frgn_stex_code", "").put("dmst_stex_tp","%").put("qry_sort_tp","2");
        JSONArray trades=pages("kt00015",token,ledger,"trst_ovrl_trde_prps_array",null);
        JSONArray profits=pages("ka10074",token,dates,"dt_rlzt_pl",null);
        JSONArray symbolProfits=pages("ka10073",token,dates,"dt_stk_rlzt_pl",null);
        JSONObject balance=new JSONObject();
        JSONArray holdings=pages("kt00004",token,new JSONObject().put("qry_tp","0").put("dmst_stex_tp","KRX"),"stk_acnt_evlt_prst",balance);
        if(cancelled) throw new InterruptedException();
        return new Snapshot(trades,profits,holdings,balance,symbolProfits);
    }
}
