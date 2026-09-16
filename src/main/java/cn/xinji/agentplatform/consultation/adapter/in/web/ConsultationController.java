package cn.xinji.agentplatform.consultation.adapter.in.web;

import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v0/consultations")
public class ConsultationController {
    private final ConsultationApplicationService service;
    public ConsultationController(ConsultationApplicationService service) { this.service = service; }

    @PostMapping
    public ConsultationResponse submit(@RequestHeader("X-V0-Requester") String requester,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @RequestBody SubmitConsultationRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new InvalidRequestException();
        }
        return ConsultationResponse.from(service.submit(requester, request.question(), request.purpose(), key));
    }

    @PostMapping("/{consultationId}/inputs")
    public ConsultationResponse supplement(@RequestHeader("X-V0-Requester") String requester,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @PathVariable String consultationId,
                                           @RequestBody SupplementConsultationInputRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) throw new InvalidRequestException();
        return ConsultationResponse.from(service.supplement(requester, consultationId, request.content(), key));
    }

    @GetMapping("/{consultationId}/answers/{answerId}")
    public ConsultationResponse readAnswer(@RequestHeader("X-V0-Requester") String requester, @PathVariable String consultationId, @PathVariable String answerId) {
        return ConsultationResponse.from(service.readAnswer(requester, consultationId, answerId));
    }

    @GetMapping("/{consultationId}")
    public ConsultationResponse read(@RequestHeader("X-V0-Requester") String requester, @PathVariable String consultationId) {
        return ConsultationResponse.from(service.read(requester, consultationId));
    }

    @GetMapping("/{consultationId}/source-links/{sourceLinkId}")
    public SourceLinkResponse readSourceLink(@RequestHeader("X-V0-Requester") String requester, @PathVariable String consultationId, @PathVariable String sourceLinkId) {
        return SourceLinkResponse.from(service.readSourceLink(requester, consultationId, sourceLinkId));
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ConsultationApplicationService.ConflictException.class)
    void conflict() {}

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ConsultationApplicationService.InvalidSupplementException.class)
    void invalidSupplement() {}

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidRequestException.class)
    void invalidRequest() {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ConsultationApplicationService.SourceLinkUnavailableException.class)
    void sourceLinkUnavailable() {}

    public static class InvalidRequestException extends RuntimeException {}
}
