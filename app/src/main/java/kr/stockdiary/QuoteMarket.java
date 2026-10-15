package kr.stockdiary;

enum QuoteMarket {
    KRX("KRX", ""), NXT("NXT", "_NX"), SOR("통합(SOR)", "_AL");
    final String label,suffix;
    QuoteMarket(String label,String suffix){this.label=label;this.suffix=suffix;}
    String requestCode(String code){return baseCode(code)+suffix;}
    static String baseCode(String raw) {
        String code=raw==null?"":raw.trim();
        if(code.endsWith("_NX") || code.endsWith("_AL"))code=code.substring(0,code.length()-3);
        code=Pairing.code(code);
        if(!code.matches("[0-9A-Z]{6}"))throw new IllegalArgumentException("현재가 조회 실패: 종목코드 형식이 올바르지 않습니다.");
        return code;
    }
    static String normalizeRequest(String raw) {
        String value=raw==null?"":raw.trim();
        String suffix=value.endsWith("_NX")?"_NX":value.endsWith("_AL")?"_AL":"";
        return baseCode(value)+suffix;
    }
    static QuoteMarket saved(String name){try{return valueOf(name);}catch(Exception e){return KRX;}}
}
