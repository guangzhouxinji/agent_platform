package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorization.Lease;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Internal first-CREATE/COPY scalars for SAVE provenance. Never reads revision text. */
final class ProductPromptCreationOrigin {
    record Origin(String createdBy,Instant createdAt) { }
    private final ProductPromptStore main;
    private final ProductPromptAuthorityStore authority;
    private final Clock clock;
    ProductPromptCreationOrigin(ProductPromptStore main,ProductPromptAuthorityStore authority,Clock clock){
        this.main=Objects.requireNonNull(main);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        main.jdbc.requireSeparate(authority.jdbc);
    }
    Origin requireCurrent(Lease lease,Head expectedBefore,Pending ownPending){
        Objects.requireNonNull(lease);Objects.requireNonNull(expectedBefore);lease.requireCurrent();
        if(lease.action()!=ProductPromptWorkspaceEvidence.Action.SAVE)throw unavailable();
        main.jdbc.requireTransaction();authority.jdbc.requireNoManagedTransaction();
        Workspace workspace=lease.workspace(expectedBefore.workspaceId());
        if(!expectedBefore.equals(main.head(expectedBefore.promptId(),true)))throw unavailable();
        main.requireClosure(expectedBefore);authority.requireWorkspace(workspace);
        Control control=control(workspace,expectedBefore,ownPending);

        var deadlines=main.jdbc.db.query("SELECT audit_until FROM conp_revision WHERE prompt_id=? AND revision_number=1",
                (rs,n)->instant(rs,1),expectedBefore.promptId());
        if(deadlines.size()!=1)throw unavailable();current(deadlines.getFirst());
        var headers=main.jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                        +"FROM conp_revision WHERE prompt_id=? AND revision_number=1 AND audit_until>?",
                ProductPromptStore.HEADER,expectedBefore.promptId(),ts(clock.instant()));
        if(headers.size()!=1)throw unavailable();Header first=headers.getFirst();
        current(first.auditUntil());
        if(!first.auditUntil().equals(deadlines.getFirst())||!("PRESENT".equals(first.bodyState())||"CLEARED".equals(first.bodyState())))throw unavailable();
        var receipts=main.jdbc.db.query("SELECT receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,"
                        +"content_sha,result_sha,submitted_at,audit_until FROM conp_receipt WHERE prompt_id=? AND generation=1 AND audit_until>?",
                ProductPromptStore.RECEIPT,expectedBefore.promptId(),ts(clock.instant()));
        if(receipts.size()!=1)throw unavailable();Receipt original=receipts.getFirst();
        if((original.operation()!=Operation.CREATE&&original.operation()!=Operation.COPY)
                ||!original.workspaceId().equals(workspace.id())||!original.issuer().equals(lease.issuer())
                ||!original.promptId().equals(first.promptId())||!original.revisionId().equals(first.revisionId())
                ||!original.contentSha().equals(first.contentSha())||!original.submittedAt().equals(first.submittedAt())
                ||!original.auditUntil().equals(first.auditUntil()))throw unavailable();
        text(original.subject());time(original.submittedAt());current(original.auditUntil());
        Long events=main.jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_event WHERE event_id=? AND prompt_id=? AND revision_id=? "
                        +"AND generation=1 AND operation=? AND issuer=? AND subject_id=? AND submitted_at=? "
                        +"AND request_sha=? AND result_sha=? AND audit_until=? AND audit_until>?",Long.class,
                original.id(),original.promptId(),original.revisionId(),original.operation().name(),original.issuer(),original.subject(),ts(original.submittedAt()),
                original.requestSha(),original.resultSha(),ts(original.auditUntil()),ts(clock.instant()));
        if(events!=1)throw unavailable();
        witness(first,original);
        if("CLEARED".equals(first.bodyState()))new ProductPromptBodyClearHistory(authority,clock)
                .requireOriginCleared(main,workspace,expectedBefore,first,original,control,ownPending);
        if(!expectedBefore.equals(main.head(expectedBefore.promptId(),false))||!control.equals(control(workspace,expectedBefore,ownPending)))
            throw unavailable();
        main.requireClosure(expectedBefore);lease.requireCurrent();current(first.auditUntil());
        return new Origin(original.subject(),original.submittedAt());
    }
    private Control control(Workspace workspace,Head before,Pending pending){
        authority.requireWorkspace(workspace);Control value=authority.control(before.promptId());
        if(value==null||value.cleanupRequestId()!=null||!value.promptId().equals(before.promptId())||!value.workspaceId().equals(workspace.id())
                ||value.kind()!=before.kind()||value.generation()!=before.generation()||!Objects.equals(value.revisionId(),before.revisionId())
                ||!value.scalarSha().equals(before.scalarSha()))throw unavailable();
        current(before.controlUntil());current(value.controlUntil());
        if(pending==null){
            if(!"READY".equals(value.state())||value.intentId()!=null||value.operation()!=null||value.keySha()!=null||value.requestSha()!=null
                    ||value.beforeSha()!=null||value.afterSha()!=null||value.targetGeneration()!=null||value.targetRevisionId()!=null
                    ||value.intentUntil()!=null||!value.containerUntil().equals(before.containerUntil())||!value.controlUntil().equals(before.controlUntil()))
                throw unavailable();
        }else{
            if(pending.operation()!=Operation.SAVE||!pending.promptId().equals(before.promptId())||!pending.workspaceId().equals(workspace.id())
                    ||pending.kind()!=before.kind()||pending.expectedGeneration()!=before.generation()
                    ||!Objects.equals(pending.expectedRevisionId(),before.revisionId())||!pending.beforeSha().equals(before.scalarSha())
                    ||pending.containerUntil().isBefore(before.containerUntil())||pending.controlUntil().isBefore(before.controlUntil())
                    ||!"PENDING".equals(value.state())||!Objects.equals(value.intentId(),pending.intentId())||value.operation()!=Operation.SAVE
                    ||!Objects.equals(value.keySha(),pending.keySha())||!Objects.equals(value.requestSha(),pending.requestSha())
                    ||!Objects.equals(value.beforeSha(),pending.beforeSha())||!Objects.equals(value.afterSha(),pending.afterSha())
                    ||!Objects.equals(value.targetGeneration(),pending.expectedGeneration()+1)||!Objects.equals(value.targetRevisionId(),pending.targetRevisionId())
                    ||!Objects.equals(value.intentUntil(),pending.intentUntil())||!value.containerUntil().equals(pending.containerUntil())
                    ||!value.controlUntil().equals(pending.controlUntil()))throw unavailable();
            current(pending.intentUntil());
        }
        return value;
    }
    private void witness(Header header,Receipt receipt){
        var deadlines=authority.jdbc.db.query("SELECT audit_until FROM idp_revision_witness WHERE revision_id=? OR (prompt_id=? AND revision_number=1)",
                (rs,n)->instant(rs,1),header.revisionId(),header.promptId());
        if(deadlines.size()!=1)throw unavailable();current(deadlines.getFirst());
        if(!deadlines.getFirst().equals(header.auditUntil()))throw unavailable();
        Long matches=authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness WHERE revision_id=? AND prompt_id=? AND revision_number=1 "
                        +"AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state=? "
                        +"AND result_sha=? AND key_sha=? AND request_sha=? AND issuer=? AND subject_id=? AND audit_until>?",Long.class,
                header.revisionId(),header.promptId(),header.recordSha(),header.contentSha(),ts(header.submittedAt()),ts(header.bodyUntil()),
                ts(header.auditUntil()),header.bodyState(),receipt.resultSha(),receipt.keySha(),receipt.requestSha(),receipt.issuer(),receipt.subject(),ts(clock.instant()));
        if(matches!=1)throw unavailable();current(header.auditUntil());
    }
    private void current(Instant until){if(until==null||!clock.instant().isBefore(until))throw unavailable();}
}
