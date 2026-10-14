package cn.xinji.agentplatform.enforcement.penalty;

import java.security.MessageDigest;
import java.util.HexFormat;

final class PenaltyCommands {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private PenaltyCommands() {}
    static void require(String value,String field) {
        if(value==null||value.isBlank())throw new IllegalArgumentException(field+" required");
    }
    static void command(String actor,String key) {require(actor,"Actor");require(key,"Idempotency-Key");}
    static String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalArgumentException("Command payload invalid",failure);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
