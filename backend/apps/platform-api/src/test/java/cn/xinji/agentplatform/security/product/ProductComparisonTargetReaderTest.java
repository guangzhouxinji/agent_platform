package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Persisted-message/query guard C; authority faults are mechanical, never a real member request I. */
class ProductComparisonTargetReaderTest {
    private static final String TASK="11111111-1111-4111-8111-111111111111", MESSAGE="22222222-2222-4222-8222-222222222222";
    private final Instant now=Instant.parse("2026-10-04T12:00:00Z");
    private final Jwt jwt=Jwt.withTokenValue("mechanical-C-only").header("alg","RS256").subject("member-C").build();
    private final ProductTaskDirectory tasks=mock(ProductTaskDirectory.class);
    private final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    private JdbcTemplate db;
    private ProductComparisonTargetReader reader;
    @BeforeEach void setup() {
        db=new JdbcTemplate(ProductSourceScopeGrantTest.database());
        db.execute("CREATE TABLE con3_message(task_id VARCHAR,message_id VARCHAR,raw_text CLOB,content_sha VARCHAR,expires_at TIMESTAMP)");
        String raw="  Valve with pressure sensor.  ";
        db.update("INSERT INTO con3_message VALUES(?,?,?,?,?)",TASK,MESSAGE,raw,
                ProductCapturedArchive.digest(raw.getBytes(StandardCharsets.UTF_8)),Timestamp.from(now.plusSeconds(120)));
        when(tasks.find(TASK)).thenReturn(Optional.of(new ProductTaskDirectory.Task(TASK,"org-C","issuer-C","member-C",
                "IN_PROGRESS",3,1,"scope-C",2,now.plusSeconds(300))));
        when(access.permits(any(),any(),any())).thenReturn(true);
        reader=new ProductComparisonTargetReader(db,tasks,access,Clock.fixed(now,ZoneOffset.UTC),65536);
    }
    @Test void readsOnlyTheExplicitPersistedOriginalWithVersionAndExpiry() {
        var target=reader.readUserScheme(TASK,MESSAGE,3,jwt);
        assertThat(target.original().text()).isEqualTo("  Valve with pressure sensor.  ");
        assertThat(target.taskVersion()).isEqualTo(3); assertThat(target.expiresAt()).isEqualTo(now.plusSeconds(120));
        verify(access,times(2)).permits(eq(jwt),any(),eq(cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.Action.READ));
    }
    @Test void missingIdentityWrongTaskAndOldVersionReject() {
        status(()->reader.readUserScheme(TASK,MESSAGE,3,null),HttpStatus.UNAUTHORIZED);
        status(()->reader.readUserScheme(TASK,MESSAGE,2,jwt),HttpStatus.CONFLICT);
        db.update("UPDATE con3_message SET task_id='another-task-C'");
        status(()->reader.readUserScheme(TASK,MESSAGE,3,jwt),HttpStatus.NOT_FOUND);
    }
    @Test void expiredOrChangedOriginalRejects() {
        db.update("UPDATE con3_message SET expires_at=?",Timestamp.from(now));
        status(()->reader.readUserScheme(TASK,MESSAGE,3,jwt),HttpStatus.NOT_FOUND);
        db.update("UPDATE con3_message SET expires_at=?,raw_text='Altered original'",Timestamp.from(now.plusSeconds(120)));
        assertThatThrownBy(()->reader.readUserScheme(TASK,MESSAGE,3,jwt)).hasMessage("TECHNICAL_ORIGINAL_SHA_MISMATCH");
    }
    @Test void revokedPermissionBeforeReturnDoesNotReleaseText() {
        when(access.permits(any(),any(),any())).thenReturn(true,false);
        status(()->reader.readUserScheme(TASK,MESSAGE,3,jwt),HttpStatus.NOT_FOUND);
    }
    private static void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
