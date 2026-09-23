package cn.xinji.agentplatform.consultation.trademarksearch.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class TrademarkSearchApplicationService {
    private final TrademarkSearchTaskRepository repository; private final TrademarkCatalogPort catalog; private final ConsultationAuthorizationPort authorization; private final Executor executor; private final Clock clock;
    public TrademarkSearchApplicationService(TrademarkSearchTaskRepository repository, TrademarkCatalogPort catalog, ConsultationAuthorizationPort authorization, @Qualifier("patentSearchExecutor") Executor executor, Clock clock) { this.repository=repository;this.catalog=catalog;this.authorization=authorization;this.executor=executor;this.clock=clock; }
    @PostConstruct void recoverInterruptedTasks() { repository.failInterruptedTasks(clock.instant()); }
    public synchronized TrademarkSearchTask create(String requester,String key,TrademarkSearchCommand command){ return create(requester,key,command,true); }
    public synchronized TrademarkSearchTask createImmediate(String requester,String key,TrademarkSearchCommand command){ return create(requester,key,command,false); }
    private TrademarkSearchTask create(String requester,String key,TrademarkSearchCommand command,boolean asynchronous){ requireAccess(requester);if(key==null||key.isBlank())throw new InvalidRequestException("Idempotency-Key is required");String fingerprint=fingerprint(command);var existing=repository.findByIdempotency(requester,key);if(existing.isPresent()){if(!existing.get().requestFingerprint().equals(fingerprint))throw new IdempotencyConflictException();return existing.get();}var descriptor=catalog.descriptor();TrademarkSearchTask task=TrademarkSearchTask.queued(UUID.randomUUID().toString(),requester,key,fingerprint,command,descriptor.dataMode(),descriptor.rankingVersion(),descriptor.limitation(),clock.instant());repository.save(task);if(asynchronous)executor.execute(()->execute(task.taskId()));else execute(task.taskId());return repository.findById(task.taskId()).orElse(task); }
    public TrademarkSearchTask read(String requester,String id){requireAccess(requester);return owned(requester,id);} public List<TrademarkSearchTask> history(String requester){requireAccess(requester);return repository.findByRequester(requester);}
    public TrademarkSearchTask cancel(String requester,String id){requireAccess(requester);owned(requester,id);return repository.update(id,current->{if(current.status()==TrademarkSearchTask.Status.CANCELLED)return current;if(current.terminal())throw new InvalidStateException("completed task cannot be cancelled");return current.cancelled(clock.instant());});}
    public TrademarkSearchTask retry(String requester,String id){requireAccess(requester);if(owned(requester,id).status()!=TrademarkSearchTask.Status.FAILED)throw new InvalidStateException("only failed tasks can be retried");TrademarkSearchTask task=repository.update(id,current->current.requeued(clock.instant()));executor.execute(()->execute(id));return repository.findById(id).orElse(task);}
    private void execute(String id){TrademarkSearchTask processing=repository.update(id,current->current.status()==TrademarkSearchTask.Status.QUEUED?current.processing(clock.instant()):current);if(processing.status()!=TrademarkSearchTask.Status.PROCESSING)return;try{var result=catalog.search(processing.inputSnapshot());repository.update(id,current->current.status()==TrademarkSearchTask.Status.CANCELLED?current:current.succeeded(result.actualScope(),result.totalCandidates(),result.truncated(),result.candidates(),result.limitation(),result.partial(),clock.instant()));}catch(RuntimeException e){repository.update(id,current->current.status()==TrademarkSearchTask.Status.CANCELLED?current:current.failed("TRADEMARK_CATALOG_FAILURE",clock.instant()));}}
    private TrademarkSearchTask owned(String requester,String id){TrademarkSearchTask task=repository.findById(id).orElseThrow(NotFoundException::new);if(!task.requester().equals(requester))throw new NotFoundException();return task;} private void requireAccess(String requester){if(!authorization.maySearch(requester))throw new AccessDeniedException();}
    private static String fingerprint(TrademarkSearchCommand command){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static class InvalidRequestException extends RuntimeException{public InvalidRequestException(String message){super(message);}} public static class IdempotencyConflictException extends RuntimeException{} public static class InvalidStateException extends RuntimeException{public InvalidStateException(String message){super(message);}} public static class AccessDeniedException extends RuntimeException{} public static class NotFoundException extends RuntimeException{}
}
