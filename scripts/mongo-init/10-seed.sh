#!/bin/bash
# Eseguito dall'immagine mongodb-atlas-local al primo avvio (e ad ogni avvio senza volume).
# Upsert per _id: rilanciarlo non duplica nulla.
set -e
for c in pazienti schede_valutazione diario_trattamenti eventi_calendario; do
  mongoimport --uri "$CONNECTION_STRING" --db fisiodesk --collection "$c" \
    --file "/seed/$c.json" --jsonArray --mode upsert --upsertFields _id
done
