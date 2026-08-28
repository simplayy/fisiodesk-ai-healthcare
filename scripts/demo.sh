#!/usr/bin/env bash
# Esegue la query target contro l'app e verifica che tornino esattamente i quattro casi positivi.
# Uso: scripts/demo.sh [http://localhost:8080]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
DOMANDA="Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento"

risposta=$(curl -sS -f -X POST "$BASE/api/ricerca" -H 'Content-Type: application/json' \
  -d "$(jq -cn --arg d "$DOMANDA" '{domanda: $d}')")

echo "Domanda:  $DOMANDA"
echo "Piano:    $(jq -c '.piano' <<<"$risposta")"
echo "Periodo:  $(jq -r '"\(.periodo.da) -> \(.periodo.a)"' <<<"$risposta")   modalità: $(jq -r .modalita <<<"$risposta")   tempi: $(jq -c .tempi <<<"$risposta")"
echo
jq -r '.risultati[] | ("\(.paziente.cognome) \(.paziente.nome), \(.paziente.eta) anni  -  ultimo appuntamento \(.ultimo_appuntamento.data[:10]) \(.ultimo_appuntamento.stato)"), (.evidenze[] | "    \(.data[:10])  \(.collezione|.[:6])  \(.andamento)  \(.sintesi)"), ""' <<<"$risposta"
echo
jq -r '.avvisi[]? | "Avviso: " + .' <<<"$risposta"

attesi="Bianchi Colombo Romano Rossi"
trovati=$(jq -r '[.risultati[].paziente.cognome] | sort | join(" ")' <<<"$risposta")
if [[ "$trovati" == "$attesi" ]]; then
  echo "OK: trovati esattamente i casi attesi ($attesi)"
else
  echo "ERRORE: attesi [$attesi], trovati [$trovati]" >&2
  exit 1
fi
