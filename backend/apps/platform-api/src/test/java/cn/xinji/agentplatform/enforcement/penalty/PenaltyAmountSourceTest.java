package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure representation checks; never counted as actual product/domain I. */
class PenaltyAmountSourceTest {
 private final ObjectMapper json=new ObjectMapper();
 private PenaltyFormula.Amount amount(String value){return new PenaltyFormula.Amount(new BigDecimal(value),true,"CASE","1","amounts.B");}
 private com.fasterxml.jackson.databind.JsonNode original() throws Exception{return json.readTree("{\"value\":12,\"confirmed\":true,\"sourceRef\":\"CASE\",\"sourceVersion\":\"1\",\"locator\":\"amounts.B\"}");}
 @Test void equalJsonNumbersDoNotInventAnAmountSourceGap() throws Exception {
  assertTrue(PenaltyProductInputs.sameAmountSource(json,amount("12"),original()));
  assertTrue(PenaltyProductInputs.sameAmountSource(json,amount("12.00"),original()));
  var decimal=(com.fasterxml.jackson.databind.node.ObjectNode)original();decimal.put("value",new BigDecimal("12.000"));assertTrue(PenaltyProductInputs.sameAmountSource(json,amount("12"),decimal));
 }
 @Test void changedAmountAndNonNumericSourceAreRejected() throws Exception {
  assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12.01"),original()));
  var text=(com.fasterxml.jackson.databind.node.ObjectNode)original();text.put("value","12");assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12"),text));
 }
 @Test void everyProvenanceAndConfirmationFieldRemainsExact() throws Exception {
  for(String field:java.util.List.of("sourceRef","sourceVersion","locator")){var changed=(com.fasterxml.jackson.databind.node.ObjectNode)original();changed.put(field,"DIFFERENT");assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12"),changed));}
  var flags=(com.fasterxml.jackson.databind.node.ObjectNode)original();flags.put("confirmed",false);assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12"),flags));
 }
 @Test void missingAndExtraSourceFieldsCannotBeNormalizedAway() throws Exception {
  var missing=(com.fasterxml.jackson.databind.node.ObjectNode)original();missing.remove("sourceVersion");assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12"),missing));
  var extra=(com.fasterxml.jackson.databind.node.ObjectNode)original();extra.put("unapprovedExtra",true);assertFalse(PenaltyProductInputs.sameAmountSource(json,amount("12"),extra));
  assertFalse(PenaltyProductInputs.sameAmountSource(json,null,original()));
 }
}
