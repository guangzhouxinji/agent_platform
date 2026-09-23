package cn.xinji.agentplatform.consultation.intent.adapter.in.web;

import cn.xinji.agentplatform.consultation.intent.application.ConsultationIntentParserService;
import cn.xinji.agentplatform.consultation.intent.application.ConsultationIntentParserService.IntentException;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseCommand;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@RequestMapping("/api/v0/consultation-intents")
public final class ConsultationIntentController {
    private final ConsultationIntentParserService service;

    public ConsultationIntentController(ConsultationIntentParserService service) { this.service = service; }

    @PostMapping("/parse")
    IntentParseResult parse(@RequestHeader("X-V0-Requester") String requester, @RequestBody IntentParseCommand command) {
        try { return service.parse(requester, command); }
        catch (IllegalArgumentException exception) { throw new IntentException("INVALID_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST); }
    }

    @ExceptionHandler(IntentException.class)
    ResponseEntity<ErrorResponse> error(IntentException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "请求内容无效"));
    }

    record ErrorResponse(String code, String message) {}
}
