package cn.xinji.agentplatform.enforcement.penalty;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.penalty.PenaltyFormula.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
/** Explicit project score arithmetic C; no asserted legal range, active rule or business approval. */
class PenaltyFormulaTest {
    private final PenaltyFormula calculator=new PenaltyFormula();
    private final Semantics semantics=new Semantics("PROJECT_SCORE","NONE","POINT",2,RoundingMode.HALF_UP);
    private final Range range=new Range(BigDecimal.ZERO,new BigDecimal("100"),"PROJECT_TEST_NOT_LEGAL",1);
    @Test void missingIsNotZero(){var r=calculator.trial(new Input("B"),semantics,range,Map.of());assertEquals("WAITING_INPUT",r.status());assertNull(r.amount());assertTrue(r.gaps().contains("MISSING_INPUT:B"));}
    @Test void confirmedZeroRemainsValid(){var r=calculator.trial(new Input("B"),semantics,range,Map.of("B",amount("0",true)));assertEquals(new BigDecimal("0.00"),r.amount());assertEquals("TRIAL",r.status());}
    @Test void unconfirmedNoAmount(){var r=calculator.trial(new Input("B"),semantics,range,Map.of("B",amount("10",false)));assertNull(r.amount());assertEquals("WAITING_INPUT",r.status());}
    @Test void multiplyIsRepeatableWithTrace(){var f=new Binary(Operator.MULTIPLY,new Input("B"),new Input("F"));var in=Map.of("B",amount("10.125",true),"F",amount("2",true));var a=calculator.trial(f,semantics,range,in);assertEquals(a,calculator.trial(f,semantics,range,in));assertEquals(new BigDecimal("20.25"),a.amount());assertEquals(4,a.steps().size());}
    @Test void outsideRangeDoesNotProduceEffectiveAmount(){var r=calculator.trial(new Input("B"),semantics,range,Map.of("B",amount("101",true)));assertEquals("REVIEW_REQUIRED",r.status());assertNull(r.amount());}
    @Test void missingSemanticsAndBasisReject(){assertThrows(IllegalArgumentException.class,()->calculator.trial(new Input("B"),new Semantics("PROJECT_SCORE","NONE","POINT",2,null),range,Map.of()));assertThrows(IllegalArgumentException.class,()->calculator.trial(new Input("B"),new Semantics("PROJECT_SCORE","NONE","POINT",null,RoundingMode.HALF_UP),range,Map.of()));assertThrows(IllegalArgumentException.class,()->calculator.trial(new Input("B"),semantics,new Range(BigDecimal.ZERO,BigDecimal.TEN,null,1),Map.of()));}
    @Test void unknownOperatorAndExpressionReject(){assertThrows(IllegalArgumentException.class,()->calculator.trial(new Binary(null,new Constant(BigDecimal.ONE,"PROJECT"),new Constant(BigDecimal.ZERO,"PROJECT")),semantics,range,Map.of()));assertThrows(IllegalArgumentException.class,()->calculator.trial(null,semantics,range,Map.of()));}
    @Test void bothBoundariesAreAcceptedWithoutClamp(){for(String value:new String[]{"0","100"})assertEquals("TRIAL",calculator.trial(new Input("B"),semantics,range,Map.of("B",amount(value,true))).status());}
    private Amount amount(String value,boolean confirmed){return new Amount(new BigDecimal(value),confirmed,"PROJECT_TEST_INPUT","1","inputs");}
}
