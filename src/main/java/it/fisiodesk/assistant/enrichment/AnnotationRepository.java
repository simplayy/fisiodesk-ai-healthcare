package it.fisiodesk.assistant.enrichment;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AnnotationRepository extends MongoRepository<Annotation, String> {

    long countByFonte(String fonte);

    long countByEmbeddingIsNull();

    List<Annotation> findByPazienteIdOrderByDataDesc(ObjectId pazienteId);
}
