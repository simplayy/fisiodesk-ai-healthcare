package it.fisiodesk.assistant.api;

import org.bson.types.ObjectId;

final class Ids {

    private Ids() {
    }

    static ObjectId objectId(String hex, String campo) {
        if (!ObjectId.isValid(hex)) {
            throw new IllegalArgumentException(campo + " non è un ObjectId valido: " + hex);
        }
        return new ObjectId(hex);
    }
}
