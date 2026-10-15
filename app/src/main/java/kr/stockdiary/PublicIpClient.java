package kr.stockdiary;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** A separate unauthenticated request: brokerage credentials are never sent. */
final class PublicIpClient {
    private final String endpoint;
    PublicIpClient(){this("https://api.ipify.org?format=json");}
    PublicIpClient(String endpoint){this.endpoint=endpoint;}
    String fetch() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        try {
            if(c.getResponseCode()!=200)throw new IOException("IP 조회 서비스 연결 실패");
            StringBuilder json=new StringBuilder();
            try(Reader r=new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)) {
                char[] buf=new char[256];int n;while((n=r.read(buf))!=-1){json.append(buf,0,n);if(json.length()>4096)throw new IOException("IP 응답 크기 오류");}
            }
            String ip=new JSONObject(json.toString()).getString("ip").trim();
            if(!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))throw new IOException("IPv4 응답 형식 오류");
            for(String part:ip.split("\\."))if(Integer.parseInt(part)>255)throw new IOException("IPv4 응답 범위 오류");
            return ip;
        } finally {c.disconnect();}
    }
}
