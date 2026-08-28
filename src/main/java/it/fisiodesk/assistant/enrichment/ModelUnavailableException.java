package it.fisiodesk.assistant.enrichment;

/** Il modello non ha risposto (non ancora scaricato, servizio giù, timeout, risposta non conforme). */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
