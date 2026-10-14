package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Exercises the product reader with read-only ports; no database, DDL or model execution. */
class ProductComparisonMatrixTerminalReadTest {
    final Instant start=Instant.parse("2026-10-11T00:00:00Z");
    final AtomicReference<Instant> now=new AtomicReference<>(start);
    final AtomicBoolean allowed=new AtomicBoolean(true);
    final AtomicInteger originalReads=new AtomicInteger();
    final ProductTaskDirectory tasks=mock(ProductTaskDirectory.class);
    final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    final ProductComparisonInputStore inputs=mock(ProductComparisonInputStore.class);
    final ProductComparisonDocuments documents=mock(ProductComparisonDocuments.class);
    final ProductComparisonTargetReader targets=mock(ProductComparisonTargetReader.class);
    final ProductSourceAvailability sources=mock(ProductSourceAvailability.class);
    final ProductComparisonMatrixStore store=mock(ProductComparisonMatrixStore.class);
    final ProductComparisonMatrixCalculation calculation=mock(ProductComparisonMatrixCalculation.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final Clock clock=mock(Clock.class);
    final ProductComparisonJson json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final Jwt jwt=Jwt.withTokenValue("component-only").header("alg","RS256").subject("member").build();
    final String task="11111111-1111-4111-8111-111111111111";
    final String message="22222222-2222-4222-8222-222222222222";
    ProductComparisonMatrixReader reader;
    ProductComparisonMatrixResults.Report report;
    Runnable onFinalOriginal=()->{};

    @BeforeEach void setup() {
        when(clock.instant()).thenAnswer(i->now.get());
        when(tasks.find(task)).thenReturn(Optional.of(new ProductTaskDirectory.Task(task,"org","issuer","member",
                "IN_PROGRESS",1,1,"scope",1,start.plusSeconds(3600))));
        when(access.permits(any(),any(),any())).thenAnswer(i->allowed.get());
        var ref=new ProductComparisonContracts.DocumentRef("source","document","version",List.of(1),null,null,null,null);
        String candidate=ProductComparisonJson.hash("source\ndocument\nversion");
        var command=new ProductComparisonContracts.Prepare(1,"goal",1,"input","TECHNICAL_SIMILARITY",
                new ProductComparisonContracts.Target(message,null),List.of(ref));
        var features=mock(ProductComparisonContracts.FeatureView.class,RETURNS_DEEP_STUBS);
        when(features.revision().features()).thenReturn(List.of());
        var envelope=new ProductComparisonInputs.Envelope("comparison",task,1,null,null,features,"preparation","material",
                json.canonical(Map.of("command",command)),"issuer","member","request","request-sha",start,start.plusSeconds(60),
                List.of(new ProductComparisonInputs.Candidate(candidate,0)));
        var view=new ProductComparisonInputs.View(envelope,"input-sha",List.of(new ProductComparisonInputs.Progress(candidate,"COMMITTED")));
        when(inputs.read(task,"comparison")).thenReturn(view);
        var loaded=mock(ProductComparisonDocuments.Loaded.class,RETURNS_DEEP_STUBS);
        when(loaded.reference()).thenReturn(ref);
        when(loaded.material().key()).thenReturn(candidate);
        var activeSource=loaded.authority().source();
        when(sources.require("source","CLAIMS","SAVE")).thenReturn(activeSource);
        when(documents.read(eq(task),eq(ref),eq(jwt),eq(true))).thenAnswer(i->{
            originalReads.incrementAndGet();
            return loaded;
        });
        String text="A valve with a pressure sensor.";
        var original=new ProductTechnicalSchemeOriginal(task,message,text,ProductComparisonJson.hash(text),1024);
        var target=new ProductComparisonTargetReader.Target(task,1,message,start.plusSeconds(3600),original);
        when(targets.readUserScheme(task,message,1,jwt)).thenAnswer(i->{
            if(originalReads.get()==2)onFinalOriginal.run();
            return target;
        });
        when(db.queryForList(anyString(),eq(String.class),eq(task),eq("comparison"),eq("report"))).thenReturn(List.of(candidate));
        when(db.queryForList(anyString(),eq(String.class),eq(task),eq("comparison"),eq(candidate))).thenReturn(List.of("report"));
        var value=new ProductComparisonMatrixResults.Package(task,"comparison",candidate,"input-sha","attempt",1,"report",1,
                "method","worker","configuration","approval","response",start,start.plusSeconds(30),List.of());
        report=new ProductComparisonMatrixResults.Report(value,"report-sha",null);
        when(store.read(task,"comparison","report")).thenReturn(report);
        when(store.rawResponse("attempt")).thenReturn("retained-response");
        when(calculation.validate(any(),eq("retained-response"))).thenReturn(new ProductComparisonMatrixCalculation.Validated(List.of(),"response"));
        reader=new ProductComparisonMatrixReader(tasks,access,inputs,documents,targets,sources,store,calculation,json,db,clock);
    }
    @Test void overviewRejectsInputExpiryDuringFinalOriginalRead() {
        onFinalOriginal=()->now.set(start.plusSeconds(60));
        assertThatThrownBy(()->reader.results(task,"comparison",jwt)).hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
    }
    @Test void reportRejectsInputExpiryDuringFinalOriginalRead() {
        onFinalOriginal=()->now.set(start.plusSeconds(60));
        assertThatThrownBy(()->reader.report(task,"comparison","report",jwt)).hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
    }
    @Test void overviewRejectsReadRevocationDuringFinalOriginalRead() {
        onFinalOriginal=()->allowed.set(false);
        assertThatThrownBy(()->reader.results(task,"comparison",jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void reportRejectsReadRevocationDuringFinalOriginalRead() {
        onFinalOriginal=()->allowed.set(false);
        assertThatThrownBy(()->reader.report(task,"comparison","report",jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void reportRejectsItsOwnExpiryWhileInputRemainsValid() {
        onFinalOriginal=()->now.set(start.plusSeconds(30));
        assertThatThrownBy(()->reader.report(task,"comparison","report",jwt)).hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
    }
    @Test void validOverviewReturnsRetainedCandidateWithoutWrites() {
        assertThat(reader.results(task,"comparison",jwt).candidates().getFirst().reportIds()).containsExactly("report");
        assertThat(originalReads).hasValue(2);
        assertReadOnly();
    }
    @Test void validReportReturnsSameRetainedObjectWithoutWrites() {
        assertThat(reader.report(task,"comparison","report",jwt)).isSameAs(report);
        assertThat(originalReads).hasValue(2);
        assertReadOnly();
    }
    private void assertReadOnly() {
        assertThat(mockingDetails(db).getInvocations()).allMatch(i->i.getMethod().getName().startsWith("query"));
        assertThat(mockingDetails(store).getInvocations()).allMatch(i->Set.of("read","rawResponse").contains(i.getMethod().getName()));
    }
}
