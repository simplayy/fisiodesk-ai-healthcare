(() => {
  const $ = (id) => document.getElementById(id);
  const domanda = $('domanda'), professionista = $('professionista'), riferimento = $('riferimento');
  const cerca = $('cerca'), esito = $('esito'), titolo = $('titolo'), avvisi = $('avvisi');
  const piano = $('piano'), mostraPiano = $('mostra-piano'), risultati = $('risultati');
  const stato = $('stato'), storia = $('storia'), storiaTitolo = $('storia-titolo'), storiaCorpo = $('storia-corpo');

  const ETICHETTE = {
    stato: { no_show: 'non presentato', completato: 'completato', prenotato: 'prenotato', cancellato: 'cancellato' },
    andamento: { miglioramento: 'miglioramento', peggioramento: 'peggioramento', stazionario: 'stazionario', non_determinabile: 'n.d.' },
    collezione: { schede_valutazione: 'scheda', diario_trattamenti: 'seduta' },
    origine: { cache: 'piano già in cache', modello: 'piano generato dal modello', regole: 'piano generato dal parser a regole' },
  };

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const data = (iso) => iso ? new Date(iso).toLocaleDateString('it-IT', { day: '2-digit', month: '2-digit', year: 'numeric' }) : '';
  const tag = (tipo, valore) => `<span class="tag ${esc(valore)}">${esc(ETICHETTE[tipo][valore] ?? valore)}</span>`;
  const vas = (v) => v && v.length ? `VAS ${v.join(' → ')}` : '';

  async function api(url, opts) {
    const r = await fetch(url, opts);
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(body.errore || `HTTP ${r.status}`);
    return body;
  }

  async function caricaProfessionisti() {
    try {
      for (const p of await api('/api/professionisti')) {
        const o = document.createElement('option');
        o.value = p.id;
        o.textContent = `Professionista …${p.id.slice(-4)} (${p.pazienti} pazienti)`;
        professionista.appendChild(o);
      }
    } catch (e) { /* la select resta su "Tutti" */ }
  }

  async function aggiornaStato() {
    try {
      const s = await api('/api/annotazioni/stato');
      const modello = s.modello_chat ? s.modello_chat.split('/').pop() : 'solo regole';
      const dettaglio = s.completo
        ? `${s.annotate} note analizzate · ${modello}`
        : `analisi note ${s.da_modello}/${s.note} col modello · ${modello}`;
      stato.innerHTML = `<span class="pallino ${s.completo ? 'ok' : ''}"></span><span class="testo">${esc(dettaglio)}</span>`;
      if (!s.completo) setTimeout(aggiornaStato, 5000);
    } catch (e) {
      stato.textContent = 'stato non disponibile';
    }
  }

  function renderPiano(p, r) {
    const finestra = p.finestra_mesi ? `ultimi ${p.finestra_mesi} mesi (${data(r.periodo.da)} → ${data(r.periodo.a)})` : `nessuna, fino al ${data(r.periodo.a)}`;
    piano.innerHTML = `<dl>
      <dt>Condizione</dt><dd>${p.condizione ? esc(p.condizione) : '<span class="muted">nessuna</span>'} → regione <code>${esc(p.regione)}</code></dd>
      <dt>Andamento</dt><dd><code>${esc(p.andamento)}</code></dd>
      <dt>Finestra</dt><dd>${esc(finestra)}</dd>
      <dt>Appuntamenti</dt><dd><code>${esc(p.appuntamento)}</code></dd>
      <dt>Origine</dt><dd>${esc(ETICHETTE.origine[p.origine] ?? p.origine)} · ricerca ${esc(r.modalita)}</dd>
      <dt>Tempi</dt><dd>piano ${r.tempi.piano_ms} ms · MongoDB ${r.tempi.ricerca_ms} ms · totale ${r.tempi.totale_ms} ms</dd>
    </dl>`;
  }

  function renderPaziente(p) {
    const a = p.paziente;
    const app = p.ultimo_appuntamento;
    const evidenze = p.evidenze.map((e) => `<li>
        <span class="data">${data(e.data)}</span>
        <span class="tipo">${esc(ETICHETTE.collezione[e.collezione] ?? e.collezione)}</span>
        <span>${tag('andamento', e.andamento)} ${esc(e.sintesi)}</span>
        <span class="vas">${esc(vas(e.vas))}</span>
      </li>`).join('');
    return `<article class="card paziente">
      <div class="testa">
        <div>
          <div class="nome"><button type="button" data-id="${esc(a.id)}">${esc(a.cognome)} ${esc(a.nome)}</button>${p.punteggio != null ? ` <span class="tag">affinità ${p.punteggio.toFixed(2)}</span>` : ''}</div>
          <div class="dettagli">${a.eta ? a.eta + ' anni · ' : ''}${esc(a.telefono ?? '')}${a.email ? ' · ' + esc(a.email) : ''} · professionista …${esc((p.professionista_id || '').slice(-4))}</div>
        </div>
        <div class="appuntamento">
          ${app ? `<div>ultimo appuntamento ${tag('stato', app.stato)}</div><div class="quando">${data(app.data)}${app.note ? ' · ' + esc(app.note) : ''}</div>` : '<span class="muted">nessun appuntamento</span>'}
        </div>
      </div>
      <ul class="evidenze">${evidenze}</ul>
    </article>`;
  }

  async function esegui() {
    const q = domanda.value.trim();
    if (!q) return;
    cerca.disabled = true;
    try {
      const r = await api('/api/ricerca', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ domanda: q, professionista_id: professionista.value || null, data_riferimento: riferimento.value || null }),
      });
      esito.hidden = false;
      const n = r.risultati.length;
      titolo.innerHTML = `${n === 1 ? '1 paziente' : n + ' pazienti'}<small>${r.tempi.totale_ms} ms</small>`;
      avvisi.innerHTML = r.avvisi.map((a) => `<div class="avviso">${esc(a)}</div>`).join('');
      renderPiano(r.piano, r);
      risultati.innerHTML = n ? r.risultati.map(renderPaziente).join('') : '<div class="card vuoto">Nessun paziente corrisponde a questi criteri nel periodo indicato.</div>';
      if (!riferimento.value) riferimento.value = r.periodo.a;
    } catch (e) {
      esito.hidden = false;
      titolo.textContent = 'Errore';
      avvisi.innerHTML = `<div class="avviso errore">${esc(e.message)}</div>`;
      risultati.innerHTML = '';
      piano.hidden = true;
    } finally {
      cerca.disabled = false;
    }
  }

  async function apriStoria(id) {
    try {
      const s = await api(`/api/pazienti/${id}/storia`);
      const p = s.paziente;
      storiaTitolo.textContent = `${p.cognome} ${p.nome}`;
      const note = s.note.map((n) => `<div class="nota">
          <div class="meta"><span>${data(n.data)}</span><span>${esc(ETICHETTE.collezione[n.collezione] ?? n.collezione)}</span>
            ${n.problemi.map((pr) => `${tag('andamento', pr.andamento)} <span>${esc(pr.regione)} · ${esc(pr.condizione)}${pr.vas.length ? ' · ' + vas(pr.vas) : ''}</span>`).join(' ')}
            ${n.fonte ? `<span class="muted">(${n.fonte === 'llm' ? esc((n.modello || '').split('/').pop()) : 'regole'})</span>` : ''}</div>
          <div>${esc(n.testo)}</div>
          ${n.sintesi ? `<div class="sintesi">Sintesi: ${esc(n.sintesi)}</div>` : ''}
        </div>`).join('');
      const eventi = s.eventi.map((e) => `<div class="evento"><span class="muted">${data(e.data)}</span>${tag('stato', e.stato)}<span class="muted">${esc(e.note ?? '')}</span></div>`).join('');
      storiaCorpo.innerHTML = `<div class="storia">
        <div class="muted">${p.eta ? p.eta + ' anni · ' : ''}${esc(p.telefono ?? '')} · ${esc(p.email ?? '')} · stato ${esc(p.stato ?? '')}</div>
        <h4>Note cliniche (${s.note.length})</h4>${note || '<div class="muted">nessuna</div>'}
        <h4>Calendario (${s.eventi.length})</h4><div class="eventi">${eventi || '<div class="muted">nessun evento</div>'}</div>
      </div>`;
      storia.hidden = false;
    } catch (e) {
      alert(e.message);
    }
  }

  cerca.addEventListener('click', esegui);
  domanda.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); esegui(); } });
  $('esempi').addEventListener('click', (e) => { if (e.target.dataset.q) { domanda.value = e.target.dataset.q; esegui(); } });
  mostraPiano.addEventListener('click', () => { piano.hidden = !piano.hidden; mostraPiano.setAttribute('aria-expanded', String(!piano.hidden)); });
  risultati.addEventListener('click', (e) => { const b = e.target.closest('button[data-id]'); if (b) apriStoria(b.dataset.id); });
  $('chiudi').addEventListener('click', () => { storia.hidden = true; });
  storia.addEventListener('click', (e) => { if (e.target === storia) storia.hidden = true; });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') storia.hidden = true; });

  caricaProfessionisti();
  aggiornaStato();

  // Link diretti: ?q=<domanda> esegue subito la ricerca, ?paziente=<id> apre la storia clinica.
  const parametri = new URLSearchParams(location.search);
  if (parametri.get('q')) { domanda.value = parametri.get('q'); esegui(); }
  if (parametri.get('paziente')) apriStoria(parametri.get('paziente'));
})();
