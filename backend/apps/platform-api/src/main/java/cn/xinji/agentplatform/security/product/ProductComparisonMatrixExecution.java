package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonCandidateExecution.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact server calculation. Request contains coordinates, never a client-supplied result. */
final class ProductComparisonMatrixExecution {
    private final ProductComparisonCandidateExecution leases;private final ProductComparisonWorkerMaterials materials;
    private final ProductComparisonModelAdmission admission;private final ProductComparisonMatrixCalculation calculation;
    private final ProductComparisonMatrixStore store;private final ModelGateway gateway;private final Clock clock;
    ProductComparisonMatrixExecution(ProductComparisonCandidateExecution leases,ProductComparisonWorkerMaterials materials,
            ProductComparisonModelAdmission admission,ProductComparisonMatrixCalculation calculation,
            ProductComparisonMatrixStore store,ModelGateway gateway,Clock clock) {
        this.leases=leases;this.materials=materials;this.admission=admission;this.calculation=calculation;this.store=store;this.gateway=gateway;this.clock=clock;
    }
    void authorizeClaim(HttpServletRequest request,Scope scope,Duration duration) {
        if(duration==null||duration.isNegative()||duration.isZero())throw new IllegalArgumentException("Finite claim window required");
        var loaded=consume(request,scope);var permit=admission.require(loaded);
        requireDispatchWindow(clock.instant(),permit.approval().configuration().timeout(),clock.instant().plus(duration),permit.expiresAt(),loaded.expiresAt());
        store.requireAvailableBudget(permit);
        if(!permit.equals(admission.require(consume(request,scope))))throw changed();
    }    ProductComparisonMatrixResults.Receipt execute(HttpServletRequest request,Scope scope,Lease lease) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("MODEL_DISPATCH_OUTSIDE_TRANSACTION_REQUIRED");
        var loaded=consume(request,scope);var permit=admission.require(loaded);
        var prior=store.byAttempt(scope.task(),scope.comparison(),lease.attemptId());
        if(prior!=null){leases.requireCommitted(request,scope,lease);admission.require(consume(request,scope));return prior.receipt();}
        leases.require(request,scope,lease);var config=permit.approval().configuration();
        String exact=calculation.request(loaded,config);
        if(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>config.maxInputBytes())throw new IllegalArgumentException("MATRIX_MODEL_INPUT_LIMIT");
        Runnable current=()->{leases.require(request,scope,lease);var next=admission.require(consume(request,scope));if(!permit.equals(next))throw changed();};
        String requestSha=ProductComparisonJson.hash(exact);
        var captured=store.capture(loaded,config,requestSha);
        var reserved=store.reserve(scope.task(),lease.attemptId(),scope.candidate(),requestSha,permit,captured,current);
        String raw;
        if(reserved==null){
            current.run();
            try{requireDispatchWindow(clock.instant(),config.timeout(),lease.until(),permit.expiresAt(),loaded.expiresAt());}
            catch(IllegalStateException insufficient){store.rejectBeforeDispatch(scope.task(),lease.attemptId(),current,()->leases.fail(request,scope,lease,"EXECUTION_WINDOW_INSUFFICIENT"));throw insufficient;}
            var result=gateway.invoke(new ModelGateway.Call(config.id(),"PATENT_COMPARISON","APPROVED_COMPARISON_ORIGINALS",
                    "PATENT_FEATURE_MATRIX_V1/prompt-1","PATENT_FEATURE_MATRIX_V1",lease.attemptId(),exact));
            if(!config.version().equals(result.configurationVersion()) || result.status()<200 || result.status()>=300
                    || result.rawResponse()==null || result.rawResponse().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>config.maxResponseBytes())throw changed();
            raw=result.rawResponse();store.receive(scope.task(),lease.attemptId(),raw,config.version(),current);
        }else raw=reserved.raw();
        ProductComparisonMatrixCalculation.Validated validated;
        try{validated=calculation.validate(consume(request,scope),raw);}catch(RuntimeException invalid){
            store.reject(scope.task(),lease.attemptId(),current,()->leases.fail(request,scope,lease,"MATRIX_RESPONSE_INVALID"));throw invalid;
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS),until=loaded.input().input().expiresAt();
        if(loaded.expiresAt().isBefore(until))until=loaded.expiresAt();
        var result=new ProductComparisonMatrixResults.Package(scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),lease.attemptId(),lease.token(),
                UUID.randomUUID().toString(),lease.token(),"PATENT_FEATURE_MATRIX_V1",loaded.actor().serviceSubject(),config.version(),permit.evidenceSha(),validated.rawSha(),now,until,validated.rows());
        return store.commit(result,current,()->{leases.requireCommitted(request,scope,lease);if(!permit.equals(admission.require(consume(request,scope))))throw changed();});
    }
    static void requireDispatchWindow(Instant now,Duration timeout,Instant... deadlines) {
        if(now==null || timeout==null || timeout.isNegative() || timeout.isZero() || deadlines==null || deadlines.length==0)
            throw new IllegalStateException("COMPARISON_EXECUTION_WINDOW_INSUFFICIENT");
        Instant end;try{end=now.plus(timeout);}catch(java.time.DateTimeException|ArithmeticException invalid){throw new IllegalStateException("COMPARISON_EXECUTION_WINDOW_INSUFFICIENT");}
        for(var until:deadlines)if(until==null || !until.isAfter(end))throw new IllegalStateException("COMPARISON_EXECUTION_WINDOW_INSUFFICIENT");
    }
    private ProductComparisonWorkerMaterials.Loaded consume(HttpServletRequest request,Scope scope){return materials.consume(request,scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),scope.delegation());}
    private static IllegalStateException changed(){return new IllegalStateException("COMPARISON_MODEL_CONFIGURATION_OR_ADMISSION_CHANGED");}
}

