package kr.stockdiary;

import org.junit.*;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;

public class PublicIpClientTest {
    private MockWebServer server;
    @Before public void setup() throws Exception {server=new MockWebServer();server.start();}
    @After public void close() throws Exception {server.shutdown();}
    @Test public void requestsIpv4WithoutBrokerCredentials() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ip\":\"203.0.113.25\"}"));
        assertEquals("203.0.113.25",new PublicIpClient(server.url("/?format=json").toString()).fetch());
        RecordedRequest request=server.takeRequest();assertEquals("GET",request.getMethod());assertNull(request.getHeader("authorization"));assertNull(request.getHeader("api-id"));assertEquals(0,request.getBodySize());
    }
    @Test public void rejectsIpv6AndInvalidOctets() throws Exception {
        for(String value:new String[]{"2001:db8::1","999.1.1.1","<html>error</html>"}) {
            server.enqueue(new MockResponse().setBody("{\"ip\":\""+value+"\"}"));
            try{new PublicIpClient(server.url("/").toString()).fetch();fail("Invalid IPv4 accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void failedResponseIsNotTreatedAsAnAddress() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("private-response"));
        try{new PublicIpClient(server.url("/").toString()).fetch();fail("HTTP failure accepted");}
        catch(java.io.IOException e){assertFalse(e.getMessage().contains("private-response"));}
    }
}
