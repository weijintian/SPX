package com.spx.chat;
import java.math.BigDecimal;
import java.math.RoundingMode;
final class MallMoney {
    private static final BigDecimal USD_CNY = new BigDecimal("6.7");
    // Presentation only: never use rounded labels for requests or calculations.
    static String show(String raw){try{return new BigDecimal(raw==null||raw.isEmpty()?"0":raw).setScale(2,RoundingMode.HALF_UP).toPlainString();}catch(Exception e){return "—";}}
    static String price(String raw){
        try {
            BigDecimal value=new BigDecimal(raw==null||raw.isEmpty()?"0":raw);
            if(value.signum()>0 && value.compareTo(new BigDecimal("0.01"))<0)return "<0.01";
            return show(raw);
        }catch(Exception e){return "—";}
    }
    static String total(String price,int count){try{return show(new BigDecimal(price).multiply(BigDecimal.valueOf(count)).toPlainString());}catch(Exception e){return "—";}}
    static String referenceCny(String amount, String spxUsdt, String usdtCny) {
        try {
            BigDecimal quantity = new BigDecimal(amount);
            BigDecimal tokenPrice = new BigDecimal(spxUsdt);
            BigDecimal rate = new BigDecimal(usdtCny);
            if (quantity.signum() < 0 || tokenPrice.signum() <= 0 || rate.signum() <= 0) return "";
            return quantity.multiply(tokenPrice).multiply(rate).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) { return ""; }
    }
    static String spxFromCny(String cny, String spxUsd, String usdCny) {
        try {
            BigDecimal cnyValue=new BigDecimal(cny);
            BigDecimal usdValue=new BigDecimal(usdCny);
            BigDecimal tokenPrice=new BigDecimal(spxUsd);
            if(cnyValue.signum()<=0||usdValue.compareTo(USD_CNY)!=0||tokenPrice.signum()<=0)return "";
            BigDecimal amount=cnyValue.divide(USD_CNY.multiply(tokenPrice),8,RoundingMode.DOWN);
            if(amount.signum()<=0||amount.compareTo(new BigDecimal("9999999999999999.99999999"))>0)return "";
            return amount.toPlainString();
        } catch(Exception e) { return ""; }
    }
}
