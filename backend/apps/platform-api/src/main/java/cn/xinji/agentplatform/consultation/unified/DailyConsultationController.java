package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.intent.application.ConsultationIntentParserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/consultations/daily")
public class DailyConsultationController {
    private final DailyConsultationService service;

    public DailyConsultationController(DailyConsultationService service) {
        this.service = service;
    }

    @PostMapping
    public DailyConsultationResponse submit(
            @RequestHeader("X-V0-Requester") String requester,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DailyConsultationRequest request) {
        return service.submit(requester, key, request);
    }

    @ExceptionHandler(DailyConsultationService.InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(DailyConsultationService.InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(ConsultationIntentParserService.IntentException.class)
    ResponseEntity<ErrorResponse> intent(ConsultationIntentParserService.IntentException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {}
}
