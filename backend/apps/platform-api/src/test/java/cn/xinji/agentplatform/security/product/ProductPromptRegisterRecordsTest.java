package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRegisterRecords.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptRegisterRecordsTest {
    private static final String WORKSPACE="10000000-0000-0000-0000-000000000001";
    private static final String ORGANIZATION="20000000-0000-0000-0000-000000000002";
    private static final String REQUEST="30000000-0000-0000-0000-000000000003";
    private static final String RECEIPT="40000000-0000-0000-0000-000000000004";
    private static final String ZERO="0".repeat(64);
    private static final Instant AT=Instant.parse("2026-10-14T00:00:00.123Z");
    private static ProductComparisonJson json(){return new ProductComparisonJson(new ObjectMapper().registerModule(new JavaTimeModule()));}

    @Test void readyChangesOnlyStateAndKeepsAllOriginalDigestsAndDeadlines()throws Exception{
        var request=fixed();request.requireDigest(json());var ready=request.ready();ready.requireDigest(json());
        var before=request.columns();var after=ready.columns();before.remove("state");after.remove("state");
        assertThat(after).isEqualTo(before);assertThat(ready.state()).isEqualTo("READY");
        assertThat(ready.receipt()).isSameAs(request.receipt());
        assertThat(ready.evidence().approvedReceiptUntil()).isAfter(ready.receiptUntil());
        assertThat(ready.key().operation()).isEqualTo("REGISTER");
    }

    @Test void allFourDigestsRejectIndependentScalarTampering()throws Exception{
        var original=fixed();var receipt=original.receipt();
        for(String field:List.of("requestSha","workspaceSha","resultSha","preparedSha")){
            var changed=new Receipt(receipt.requestId(),receipt.receiptId(),receipt.key(),receipt.operatorOrganization(),receipt.maintainerVersion(),
                    field.equals("requestSha")?ZERO:receipt.requestSha(),field.equals("workspaceSha")?ZERO:receipt.workspaceSha(),
                    field.equals("resultSha")?ZERO:receipt.resultSha(),receipt.preparedAt(),receipt.receiptUntil());
            var request=new Request(original.target(),original.evidence(),changed,original.intentUntil(),
                    field.equals("preparedSha")?ZERO:original.preparedSha(),original.state());
            assertThatThrownBy(()->request.requireDigest(json())).as(field).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void twoServerIdsAndOriginalFixedTimeAreAuthenticated()throws Exception{
        var original=fixed();var r=original.receipt();
        var changed=new Receipt(UUID.randomUUID().toString(),r.receiptId(),r.key(),r.operatorOrganization(),r.maintainerVersion(),
                r.requestSha(),r.workspaceSha(),r.resultSha(),r.preparedAt(),r.receiptUntil());
        var request=new Request(original.target(),original.evidence(),changed,original.intentUntil(),original.preparedSha(),"PENDING");
        assertThatThrownBy(()->request.requireDigest(json())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new Receipt(r.receiptId(),r.receiptId(),r.key(),r.operatorOrganization(),1,r.requestSha(),r.workspaceSha(),
                r.resultSha(),AT,r.receiptUntil())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void approvedAndActualReceiptCutoffsAreSeparateAuthenticatedScalars()throws Exception{
        var original=fixed();var e=original.evidence();var r=original.receipt();var json=json();
        var evidence=new Evidence(e.operatorSubject(),e.operatorOrganization(),e.maintainerVersion(),e.certificateSha(),
                e.primaryDatabaseIdentitySha(),e.authorityDatabaseIdentitySha(),e.approvalRef(),e.approvalSha(),e.approvedReceiptUntil().plusSeconds(1));
        var command=canonical("commandCanonical",new Class<?>[]{ProductPromptRecords.Workspace.class,Evidence.class},original.target(),evidence);
        assertThat(json.sha(command)).isNotEqualTo(original.requestSha());
        assertThatThrownBy(()->new Request(original.target(),evidence,r,original.intentUntil(),original.preparedSha(),"PENDING").requireDigest(json))
                .isInstanceOf(IllegalStateException.class);
        var changed=new Receipt(r.requestId(),r.receiptId(),r.key(),r.operatorOrganization(),r.maintainerVersion(),r.requestSha(),r.workspaceSha(),
                r.resultSha(),r.preparedAt(),r.receiptUntil().minusSeconds(1));
        assertThatThrownBy(()->new Request(original.target(),e,changed,original.intentUntil(),original.preparedSha(),"PENDING").requireDigest(json))
                .isInstanceOf(IllegalStateException.class);
        assertThat(changed.requestSha()).isEqualTo(r.requestSha());
    }

    @ParameterizedTest @ValueSource(strings={"tooLongSubject","tooLongPolicy","tooLongApproval","sameDatabase","fractionalTime","badState","lateIntent","lateReceipt"})
    void finiteRecordContractRejectsInvalidCoordinates(String failure)throws Exception{
        var original=fixed();var t=original.target();var e=original.evidence();var r=original.receipt();
        assertThatThrownBy(()->{
            switch(failure){
                case "tooLongSubject" -> new Key(WORKSPACE,"s".repeat(201),ZERO,ZERO);
                case "tooLongPolicy" -> new Request(new ProductPromptRecords.Workspace(t.id(),t.organization(),1,"ACTIVE","p".repeat(201),
                        t.policySha(),t.registrySha(),t.validUntil()),e,r,original.intentUntil(),original.preparedSha(),"PENDING");
                case "tooLongApproval" -> new Evidence(e.operatorSubject(),ORGANIZATION,1,ZERO,"1".repeat(64),"2".repeat(64),"a".repeat(501),ZERO,e.approvedReceiptUntil());
                case "sameDatabase" -> new Evidence(e.operatorSubject(),ORGANIZATION,1,ZERO,ZERO,ZERO,"approval.json",ZERO,e.approvedReceiptUntil());
                case "fractionalTime" -> new Receipt(REQUEST,RECEIPT,r.key(),ORGANIZATION,1,r.requestSha(),r.workspaceSha(),r.resultSha(),AT.plusNanos(1),r.receiptUntil());
                case "badState" -> new Request(t,e,r,original.intentUntil(),original.preparedSha(),"CLOSED");
                case "lateIntent" -> new Request(t,e,r,AT.plusSeconds(301),original.preparedSha(),"PENDING");
                case "lateReceipt" -> new Receipt(REQUEST,RECEIPT,r.key(),ORGANIZATION,1,r.requestSha(),r.workspaceSha(),r.resultSha(),AT,AT.plusSeconds(180L*86400+1));
                default -> throw new AssertionError(failure);
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void eightIndependentJvmsProduceIdenticalRegisterDigests()throws Exception{
        var results=new HashSet<String>();var processes=new HashSet<Long>();
        for(int run=0;run<8;run++){
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",
                    System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx128m","-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    ProductPromptRegisterRecordsTest.class.getName(),"canonical-probe").redirectErrorStream(true).start();
            processes.add(child.pid());
            try{
                assertThat(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(child.exitValue()).as(output).isZero();
                var lines=output.lines().filter(line->line.startsWith("REGISTER_CANONICAL_PROBE ")).toList();
                assertThat(lines).hasSize(1);String result=lines.getFirst().substring("REGISTER_CANONICAL_PROBE ".length());
                assertThat(result).matches("[a-f0-9]{64}( [a-f0-9]{64}){3}");results.add(result);
                System.out.println("REGISTER_CANONICAL_JVM "+run+" "+result);
            }finally{if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}}
        }
        assertThat(processes).hasSize(8);assertThat(results).hasSize(1);
    }

    public static void main(String[] args)throws Exception{
        if(args.length!=1||!"canonical-probe".equals(args[0]))throw new IllegalArgumentException("test probe argument");
        var request=fixed();request.requireDigest(json());request.ready().requireDigest(json());
        System.out.println("REGISTER_CANONICAL_PROBE "+request.workspaceSha()+" "+request.requestSha()+" "+request.receipt().resultSha()+" "+request.preparedSha());
    }
    private static Request fixed()throws Exception{
        var json=json();var target=new ProductPromptRecords.Workspace(WORKSPACE,ORGANIZATION,7,"ACTIVE","policy.json","3".repeat(64),"4".repeat(64),AT.plusSeconds(600));
        var evidence=new Evidence("maintainer",ORGANIZATION,9,"5".repeat(64),"1".repeat(64),"2".repeat(64),"approval.json","6".repeat(64),AT.plusSeconds(1200));
        String workspace=json.sha(canonical("workspaceCanonical",new Class<?>[]{ProductPromptRecords.Workspace.class},target));
        String command=json.sha(canonical("commandCanonical",new Class<?>[]{ProductPromptRecords.Workspace.class,Evidence.class},target,evidence));
        var unsigned=new Receipt(REQUEST,RECEIPT,new Key(WORKSPACE,"maintainer",evidence.certificateSha(),"7".repeat(64)),ORGANIZATION,9,command,workspace,ZERO,AT,AT.plusSeconds(600));
        var receipt=new Receipt(REQUEST,RECEIPT,unsigned.key(),ORGANIZATION,9,command,workspace,json.sha(canonical("resultCanonical",new Class<?>[]{Receipt.class},unsigned)),AT,AT.plusSeconds(600));
        var pending=new Request(target,evidence,receipt,AT.plusSeconds(300),ZERO,"PENDING");
        return new Request(target,evidence,receipt,pending.intentUntil(),json.sha(canonical("preparedCanonical",new Class<?>[]{Request.class},pending)),"PENDING");
    }
    private static Object canonical(String name,Class<?>[] types,Object...values)throws Exception{
        var method=ProductPromptRegisterRecords.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,values);
    }
}
