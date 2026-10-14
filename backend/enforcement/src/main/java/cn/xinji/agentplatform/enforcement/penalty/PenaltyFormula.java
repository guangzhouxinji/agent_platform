package cn.xinji.agentplatform.enforcement.penalty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed deterministic trial calculator. It cannot publish rules, choose an option, or write an official decision. */
public final class PenaltyFormula {
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,property="type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=Input.class,name="INPUT"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=Constant.class,name="CONSTANT"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=Binary.class,name="BINARY")})
    public sealed interface Expression permits Input,Constant,Binary {}
    public record Input(String name) implements Expression {}
    public record Constant(BigDecimal value,String basisRef) implements Expression {}
    public enum Operator { MULTIPLY }
    public record Binary(Operator operator,Expression left,Expression right) implements Expression {}
    public record Amount(BigDecimal value,boolean confirmed,String sourceRef,String sourceVersion,String locator) {}
    public record Semantics(String kind,String currency,String unit,Integer scale,RoundingMode rounding) {}
    public record Range(BigDecimal minimum,BigDecimal maximum,String legalBasisRef,long legalBasisRevision) {}
    public record Step(String operation,BigDecimal left,BigDecimal right,BigDecimal result,String sourceRef) {}
    public record Result(String status,BigDecimal amount,List<Step> steps,List<String> gaps) {}
    public Result trial(Expression formula,Semantics semantics,Range range,Map<String,Amount> inputs) {
        validate(semantics,range);
        var gaps=new ArrayList<String>();var steps=new ArrayList<Step>();
        BigDecimal value=evaluate(formula,semantics,inputs,steps,gaps,0);
        if(!gaps.isEmpty())return new Result("WAITING_INPUT",null,List.copyOf(steps),List.copyOf(gaps));
        BigDecimal rounded=value.setScale(semantics.scale(),semantics.rounding());
        if(rounded.compareTo(range.minimum())<0||rounded.compareTo(range.maximum())>0)
            return new Result("REVIEW_REQUIRED",null,List.copyOf(steps),List.of("OUTSIDE_STATUTORY_RANGE"));
        steps.add(new Step("ROUND",value,null,rounded,semantics.unit()));
        return new Result("TRIAL",rounded,List.copyOf(steps),List.of());
    }
    private BigDecimal evaluate(Expression expression,Semantics semantics,Map<String,Amount> inputs,
            List<Step> steps,List<String> gaps,int depth) {
        if(expression==null||depth>32)throw new IllegalArgumentException("Typed bounded formula required");
        if(expression instanceof Input input) {
            if(blank(input.name()))throw new IllegalArgumentException("Input name required");
            Amount amount=inputs.get(input.name());
            if(amount==null||amount.value()==null){gaps.add("MISSING_INPUT:"+input.name());return null;}
            if(!amount.confirmed()){gaps.add("UNCONFIRMED_INPUT:"+input.name());return null;}
            if(blank(amount.sourceRef())||blank(amount.sourceVersion())||blank(amount.locator()))
                throw new IllegalArgumentException("Exact amount provenance required");
            steps.add(new Step("INPUT:"+input.name(),null,null,amount.value(),amount.sourceRef()+"@"+amount.sourceVersion()+"#"+amount.locator()));
            return amount.value();
        }
        if(expression instanceof Constant constant) {
            if(constant.value()==null||blank(constant.basisRef()))throw new IllegalArgumentException("Constant basis required");
            steps.add(new Step("CONSTANT",null,null,constant.value(),constant.basisRef()));return constant.value();
        }
        Binary binary=(Binary)expression;
        if(binary.operator()==null)throw new IllegalArgumentException("Typed operator required");
        BigDecimal left=evaluate(binary.left(),semantics,inputs,steps,gaps,depth+1);
        BigDecimal right=evaluate(binary.right(),semantics,inputs,steps,gaps,depth+1);
        if(left==null||right==null)return null;
        BigDecimal result=switch(binary.operator()) {
            case MULTIPLY->left.multiply(right);
        };
        steps.add(new Step(binary.operator().name(),left,right,result,null));return result;
    }
    private void validate(Semantics value,Range range) {
        if(value==null||blank(value.kind())||blank(value.currency())||blank(value.unit())||value.scale()==null||value.scale()<0||value.scale()>18||value.rounding()==null)
            throw new IllegalArgumentException("Complete amount semantics required");
        if(range==null||range.minimum()==null||range.maximum()==null||range.minimum().compareTo(range.maximum())>0
            ||blank(range.legalBasisRef())||range.legalBasisRevision()<1)throw new IllegalArgumentException("Exact statutory range basis required");
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
}
