package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSubjectsRecoveryTest {
    static final String ISSUER="https://identity.example.org", ACTOR="registrar#trusted-fingerprint";
    static final Instant START=Instant.parse("2026-10-10T00:00:00Z");
    static class Time extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(START);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now.get();}
    }
    static class Fixture {
        final Time clock=new Time(); final JdbcTemplate db; final ProductSubjects service;
        final ProductSubjects.Registration original; final String key=UUID.randomUUID().toString();
        Fixture() throws Exception {
            var source=new JdbcDataSource();
            source.setURL("jdbc:h2:mem:recovery-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            try(var connection=source.getConnection()){
                for(String file:List.of("V032__product_identity.sql","V046__product_workload_subject.sql",
                        "V107__product_activation_recovery.sql"))
                    ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8",file)));
            }
            db=new JdbcTemplate(source); service=service(ISSUER);
            original=service.register("project-member",ACTOR); clock.now.set(START.plusSeconds(600));
        }
        ProductSubjects service(String issuer){return new ProductSubjects(db,
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
            new BCryptPasswordEncoder(4),clock,issuer,Duration.ofMinutes(10),16);}
        ProductSubjects.Recovery recover(){return service.recoverActivation(original.subjectId(),1,ACTOR,key);}
        long version(){return db.queryForObject("SELECT credential_version FROM id2_subject WHERE subject_id=?",Long.class,original.subjectId());}
        int receipts(){return db.queryForObject("SELECT COUNT(*) FROM id2_activation_recovery_receipt",Integer.class);}
        int events(){return db.queryForObject("SELECT COUNT(*) FROM id2_subject_event WHERE operation='REISSUE'",Integer.class);}
        void unchanged(){assertThat(version()).isEqualTo(1); assertThat(receipts()).isZero(); assertThat(events()).isZero();}
    }
    @Test void exactExpiryNewValueAndLostResponseNeverRedeliver() throws Exception {
        var f=new Fixture(); var result=f.recover();
        assertThat(result.credentialVersion()).isEqualTo(2);
        assertThat(Base64.getUrlDecoder().decode(result.oneTimeActivation())).hasSize(32);
        assertThat(result.expiresAt()).isEqualTo(START.plusSeconds(1200));
        assertThat(result.toString()).doesNotContain(result.oneTimeActivation());
        assertThatThrownBy(()->f.service.activate(f.original.subjectId(),f.original.oneTimeActivation(),"strong-human-password"))
            .isInstanceOf(ProductSubjects.InvalidActivationException.class);
        var replay=f.recover(); assertThat(replay.delivery()).isEqualTo("NOT_REDELIVERED");
        assertThat(replay.oneTimeActivation()).isNull(); assertThat(replay.expiresAt()).isEqualTo(result.expiresAt());
        assertThat(f.version()).isEqualTo(2); assertThat(f.receipts()).isEqualTo(1); assertThat(f.events()).isEqualTo(1);
        assertThat(f.db.queryForMap("SELECT * FROM id2_activation_recovery_receipt").toString()).doesNotContain(result.oneTimeActivation());
        f.service.activate(f.original.subjectId(),result.oneTimeActivation(),"strong-human-password");
        assertThat(f.recover().oneTimeActivation()).isNull(); assertThat(f.version()).isEqualTo(3);
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),3,ACTOR,UUID.randomUUID().toString()))
            .isInstanceOf(IllegalStateException.class);
        f.service.disable(f.original.subjectId(),ACTOR); assertThat(f.recover().delivery()).isEqualTo("NOT_REDELIVERED");
    }
    @Test void unexpiredAndStaleVersionNoEffects() throws Exception {
        var f=new Fixture(); f.clock.now.set(START.plusSeconds(599));
        assertThatThrownBy(f::recover).isInstanceOf(IllegalStateException.class); f.unchanged();
        f.clock.now.set(START.plusSeconds(600));
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),2,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        f.unchanged();
    }
    @Test void actorIssuerUnknownInvalidIdsAndVersionReject() throws Exception {
        var f=new Fixture();
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),1,"other",f.key)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.service("https://other.example.org").recoverActivation(f.original.subjectId(),1,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.service.recoverActivation(UUID.randomUUID().toString(),1,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        for(String key:List.of("bad",f.key.toUpperCase(Locale.ROOT)))
            assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),1,ACTOR,key)).isInstanceOf(IllegalArgumentException.class);
        for(long version:new long[]{0,-1,Long.MAX_VALUE})
            assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),version,ACTOR,f.key)).isInstanceOf(IllegalArgumentException.class);
        f.unchanged();
    }
    @Test void missingOrDuplicateRegisterCannotTakeOver() throws Exception {
        var f=new Fixture(); f.db.update("DELETE FROM id2_subject_event");
        assertThatThrownBy(f::recover).isInstanceOf(IllegalStateException.class); f.unchanged();
        for(String actor:List.of(ACTOR,"other")) f.db.update("INSERT INTO id2_subject_event VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),ISSUER,f.original.subjectId(),"REGISTER",0,1,actor,java.sql.Timestamp.from(START));
        assertThatThrownBy(f::recover).isInstanceOf(IllegalStateException.class); f.unchanged();
    }
    @Test void disabledAndServiceCannotRecover() throws Exception {
        var f=new Fixture(); f.service.disable(f.original.subjectId(),ACTOR);
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),2,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        var other=new Fixture(); other.db.update("UPDATE id2_subject SET subject_kind='SERVICE',status='ACTIVE',activation_sha=NULL,activation_expires_at=NULL");
        assertThatThrownBy(other::recover).isInstanceOf(IllegalStateException.class); other.unchanged();
    }
    @Test void receiptWrongPayloadOrOtherSubjectNoLeaks() throws Exception {
        var f=new Fixture(); f.recover();
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),2,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        var other=f.service.register("other-member",ACTOR); f.clock.now.set(START.plusSeconds(1200));
        assertThatThrownBy(()->f.service.recoverActivation(other.subjectId(),1,ACTOR,f.key)).isInstanceOf(IllegalStateException.class);
        assertThat(f.db.queryForObject("SELECT credential_version FROM id2_subject WHERE subject_id=?",Long.class,other.subjectId())).isEqualTo(1);
        assertThat(f.receipts()).isEqualTo(1);
    }
    @Test void newKeyOnlyAfterExpiryAndActivationAtExpiryFails() throws Exception {
        var f=new Fixture(); var first=f.recover();
        assertThatThrownBy(()->f.service.recoverActivation(f.original.subjectId(),2,ACTOR,UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        f.clock.now.set(first.expiresAt());
        assertThatThrownBy(()->f.service.activate(f.original.subjectId(),first.oneTimeActivation(),"strong-human-password")).isInstanceOf(ProductSubjects.InvalidActivationException.class);
        var second=f.service.recoverActivation(f.original.subjectId(),2,ACTOR,UUID.randomUUID().toString());
        assertThat(second.credentialVersion()).isEqualTo(3); assertThat(second.oneTimeActivation()).isNotEqualTo(first.oneTimeActivation());
        assertThat(f.recover().credentialVersion()).isEqualTo(2);
    }
    @Test void eventFailureRollsBackAll() throws Exception {
        var f=new Fixture(); f.db.execute("ALTER TABLE id2_subject_event ADD CONSTRAINT reject_reissue CHECK(operation<>'REISSUE')");
        assertThatThrownBy(f::recover).isInstanceOf(DataAccessException.class); f.unchanged();
    }
    @Test void receiptFailureRollsBackSubjectAndEvent() throws Exception {
        var f=new Fixture(); f.db.execute("ALTER TABLE id2_activation_recovery_receipt ADD CONSTRAINT reject_receipt CHECK(expected_version>10)");
        assertThatThrownBy(f::recover).isInstanceOf(DataAccessException.class); f.unchanged();
    }
    @Test void sameSubjectSameKeyCommitsOnce() throws Exception {race(true);}
    @Test void sameSubjectDifferentKeysCommitsOnce() throws Exception {race(false);}
    @Test void databaseTimestampPrecisionIsIdenticalForFirstAndReplay() throws Exception {
        var f=new Fixture(); f.clock.now.set(START.plusSeconds(600).plusNanos(123456789));
        var first=f.recover(); assertThat(f.recover().expiresAt()).isEqualTo(first.expiresAt());
        assertThat(f.db.queryForObject("SELECT activation_expires_at FROM id2_subject",java.sql.Timestamp.class).toInstant())
            .isEqualTo(first.expiresAt());
    }
    @Test void crossSubjectSameActorKeyUniqueRaceRollsBackLosingSubject() throws Exception {
        var f=new Fixture(); var other=f.service.register("race-other",ACTOR);
        f.clock.now.set(START.plusSeconds(1200)); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<String>> jobs=new ArrayList<>();
            for(String id:List.of(f.original.subjectId(),other.subjectId()))
                jobs.add(pool.submit(()->{start.await();try{return f.service.recoverActivation(id,1,ACTOR,f.key).delivery();}
                    catch(IllegalStateException conflict){return "CONFLICT";}}));
            start.countDown(); List<String> results=new ArrayList<>();
            for(var job:jobs) results.add(job.get(15,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("FIRST_RESPONSE","CONFLICT");
        }
        assertThat(f.db.queryForObject("SELECT SUM(credential_version) FROM id2_subject",Long.class)).isEqualTo(3);
        assertThat(f.receipts()).isEqualTo(1); assertThat(f.events()).isEqualTo(1);
    }
    private void race(boolean sameKey) throws Exception {
        var f=new Fixture(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<String>> jobs=new ArrayList<>();
            for(int i=0;i<2;i++){
                String key=sameKey?f.key:UUID.randomUUID().toString();
                jobs.add(pool.submit(()->{start.await(); try{return f.service.recoverActivation(f.original.subjectId(),1,ACTOR,key).delivery();}
                    catch(IllegalStateException conflict){return "CONFLICT";}}));
            }
            start.countDown(); List<String> results=new ArrayList<>();
            for(var job:jobs) results.add(job.get(15,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("FIRST_RESPONSE",sameKey?"NOT_REDELIVERED":"CONFLICT");
        }
        assertThat(f.version()).isEqualTo(2); assertThat(f.receipts()).isEqualTo(1); assertThat(f.events()).isEqualTo(1);
    }
}
