package kr.stockdiary;

import java.math.BigDecimal;

/** Used only by the sequential quote worker. Credentials and tokens stay in memory. */
class QuoteClient {
    private KiwoomApi api;
    private final KiwoomApi testApi;
    private String key,secret,token;
    private boolean mock;
    QuoteClient() { testApi=null; }
    QuoteClient(KiwoomApi api) { testApi=api; }
    BigDecimal price(String key,String secret,boolean mock,String code) throws Exception {
        if(api==null || !key.equals(this.key) || !secret.equals(this.secret) || mock!=this.mock) {
            api=testApi==null?new KiwoomApi(mock):testApi;
            this.key=key;this.secret=secret;this.mock=mock;token=null;
        }
        if(token==null)token=api.authenticate(key,secret);
        try { return api.currentPrice(token,code); }
        catch(KiwoomApi.ApiException e) {
            if(e.code!=8005 && e.code!=8031 && e.code!=8103 && e.code!=8010)throw e;
            // A changed connection can invalidate the token's IP binding as well.
            token=null;
            token=api.authenticate(key,secret);
            return api.currentPrice(token,code);
        }
    }
}
