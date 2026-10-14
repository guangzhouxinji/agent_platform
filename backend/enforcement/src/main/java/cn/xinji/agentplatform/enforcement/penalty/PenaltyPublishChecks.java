package cn.xinji.agentplatform.enforcement.penalty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Frozen ENF-15 publication checks. Does not publish, authorize, select a heavier rule or write a decision. */
public final class PenaltyPublishChecks {
    public record Basis(String ref,long revision,String reviewState,String bindingState,List<String> gaps) {}
    public record Boundary(String name,Map<String,PenaltyFormula.Amount> inputs,BigDecimal expected) {}
    public record Overlap(String ruleRef,long revision,String priorityRelationRef) {}
    public record Input(String organization,boolean procedureApplicable,String basisRef,long basisRevision,
            PenaltyFormula.Expression formula,PenaltyFormula.Semantics semantics,PenaltyFormula.Range range,
            List<Boundary> boundaries,List<Overlap> overlaps) {}
    public record Result(boolean publishable,List<String> gaps,List<PenaltyFormula.Result> trials) {}
    public interface Inputs {
        void assertCurrentAccess(String actor,Input input);
        Basis legalBasis(String actor,String organization,String ref,long revision);
    }
    private final Inputs inputs;
    private final PenaltyFormula calculator=new PenaltyFormula();
    public PenaltyPublishChecks(Inputs inputs){this.inputs=java.util.Objects.requireNonNull(inputs);}
    public Result validate(String actor,Input input){
        if(blank(actor)||input==null||blank(input.organization()))throw new IllegalArgumentException("Exact actor/organization/input required");
        inputs.assertCurrentAccess(actor,input);
        var gaps=new ArrayList<String>();var trials=new ArrayList<PenaltyFormula.Result>();
        if(!input.procedureApplicable())gaps.add("PROCEDURE_NOT_APPLICABLE");
        if(blank(input.basisRef())||input.basisRevision()<1)gaps.add("LEGAL_REFERENCE_REQUIRED");
        else{
            Basis basis=inputs.legalBasis(actor,input.organization(),input.basisRef(),input.basisRevision());
            if(basis==null)gaps.add("LEGAL_REFERENCE_MISSING");
            else if(!input.basisRef().equals(basis.ref())||input.basisRevision()!=basis.revision())gaps.add("LEGAL_REFERENCE_COORDINATE_MISMATCH");
            else if(!"CONFIRMED".equals(basis.reviewState())||!"ACTIVE".equals(basis.bindingState())||basis.gaps()==null||!basis.gaps().isEmpty())gaps.add("LEGAL_REFERENCE_REVIEW_REQUIRED");
        }
        var s=input.semantics();var range=input.range();
        if(s==null||blank(s.kind())||blank(s.currency())||blank(s.unit())||s.scale()==null||s.scale()<0||s.scale()>18||s.rounding()==null)gaps.add("AMOUNT_SEMANTICS_REQUIRED");
        if(range==null||range.minimum()==null||range.maximum()==null||range.minimum().compareTo(range.maximum())>0||blank(range.legalBasisRef())||range.legalBasisRevision()<1)gaps.add("STATUTORY_RANGE_REQUIRED");
        else if(!range.legalBasisRef().equals(input.basisRef())||range.legalBasisRevision()!=input.basisRevision())gaps.add("STATUTORY_BASIS_MISMATCH");
        if(input.formula()==null)gaps.add("TYPED_FORMULA_REQUIRED");
        if(input.overlaps()==null)gaps.add("OVERLAP_CHECK_REQUIRED");
        else for(Overlap overlap:input.overlaps())if(overlap==null||blank(overlap.ruleRef())||overlap.revision()<1||blank(overlap.priorityRelationRef()))gaps.add("OVERLAP_PRIORITY_REQUIRED");
        if(input.boundaries()==null||input.boundaries().isEmpty())gaps.add("BOUNDARY_TRIAL_REQUIRED");
        else if(gaps.isEmpty()){
            boolean lower=false,upper=false;
            for(Boundary boundary:input.boundaries()){
                if(boundary==null||blank(boundary.name())||boundary.inputs()==null||boundary.expected()==null){gaps.add("BOUNDARY_TRIAL_INPUT_REQUIRED");continue;}
                PenaltyFormula.Result trial;
                try{trial=calculator.trial(input.formula(),s,range,boundary.inputs());}
                catch(IllegalArgumentException bad){gaps.add("BOUNDARY_FORMULA_OR_INPUT_INVALID");continue;}
                trials.add(trial);
                if(!"TRIAL".equals(trial.status())||trial.amount()==null||trial.amount().compareTo(boundary.expected())!=0){gaps.add("BOUNDARY_TRIAL_MISMATCH");continue;}
                lower|=trial.amount().compareTo(range.minimum())==0;upper|=trial.amount().compareTo(range.maximum())==0;
            }
            if(!lower||!upper)gaps.add("STATUTORY_BOUNDARIES_NOT_VERIFIED");
        }
        inputs.assertCurrentAccess(actor,input);
        return new Result(gaps.isEmpty(),List.copyOf(gaps),List.copyOf(trials));
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
}
