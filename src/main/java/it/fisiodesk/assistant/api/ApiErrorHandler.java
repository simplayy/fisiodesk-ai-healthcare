package it.fisiodesk.assistant.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler({ MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<Map<String, String>> richiestaNonValida(Exception e) {
        String messaggio = e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null
                ? m.getBindingResult().getFieldError().getField() + ": " + m.getBindingResult().getFieldError().getDefaultMessage()
                : e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errore", messaggio == null ? "richiesta non valida" : messaggio));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> nonTrovato(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errore", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> errore(Exception e) {
        log.error("Errore non gestito", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("errore", "errore interno, vedi i log"));
    }
}
