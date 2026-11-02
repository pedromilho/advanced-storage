const $ = (selector) => document.querySelector(selector);
const ui = Object.fromEntries(['targets', 'progress', 'save-status', 'error', 'search', 'category', 'catalog-count', 'result-count', 'item-grid', 'empty-search', 'load-more', 'recipe-icon', 'recipe-title', 'recipe-id', 'recipe-form', 'output-quantity', 'time-seconds', 'bench', 'ingredients', 'ingredient-count', 'empty-recipe', 'notes', 'ready-button', 'clear-button', 'export-button', 'import-button', 'import-file', 'toast'].map(id => [id, $(`#${id}`)]));
let items = [], targets = [], lookup = new Map(), recipes = {}, revision = 0, current = '';
let filtered = [], limit = 72, timer, toastTimer, saving = false, dirty = false, serial = 0, conflict = false;
const draftKey = 'advanced-storage-craft-unsaved-v1';
const normalize = value => String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const format = value => Number(value).toLocaleString('pt-BR');
const emptyRecipe = () => ({ingredients: [], outputQuantity: 1, timeSeconds: 1, bench: '', notes: '', status: 'draft'});
const recipe = () => recipes[current] || emptyRecipe();

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function icon(item, size = 44) {
  const img = element('img');
  img.src = item.icon;
  img.alt = '';
  img.width = size; img.height = size;
  img.loading = 'lazy';
  return img;
}
function status(text, state = '') { ui['save-status'].textContent = text; ui['save-status'].dataset.state = state; }
function error(message) { ui.error.textContent = message; ui.error.hidden = !message; }
function toast(message) {
  ui.toast.textContent = message; ui.toast.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => ui.toast.hidden = true, 2200);
}
async function request(url, options) {
  const response = await fetch(url, options);
  const body = await response.json();
  if (!response.ok) { const failure = new Error(body.error || 'Não foi possível concluir a operação.'); failure.status = response.status; throw failure; }
  return body;
}

function renderTargets() {
  const fragment = document.createDocumentFragment();
  let done = 0;
  for (const target of targets) {
    const r = recipes[target.id];
    if (r?.status === 'ready') done++;
    const button = element('button', 'target');
    button.type = 'button';
    button.dataset.id = target.id;
    button.setAttribute('aria-current', String(target.id === current));
    button.append(icon(target, 42));
    const label = element('span', 'target-label');
    label.append(element('strong', '', target.name));
    label.append(element('small', '', r?.status === 'ready' ? 'Definida' : r?.ingredients.length ? 'Em elaboração' : 'Sem receita'));
    button.append(label);
    if (r?.status === 'ready') button.append(element('span', 'done', '✓'));
    button.onclick = () => selectRecipe(target.id);
    fragment.append(button);
  }
  ui.targets.replaceChildren(fragment);
  ui.progress.textContent = `${done}/${targets.length}`;
}
function selectRecipe(id) {
  if (!ui['recipe-form'].reportValidity()) return;
  current = id;
  try { localStorage.setItem('advanced-storage-craft-selected', id); } catch {}
  renderTargets(); renderRecipe(); renderItems();
}
function renderRecipe() {
  const item = lookup.get(current), r = recipe();
  ui['recipe-icon'].src = item.icon;
  ui['recipe-title'].textContent = item.name;
  ui['recipe-id'].textContent = item.id;
  ui['output-quantity'].value = r.outputQuantity;
  ui['time-seconds'].value = r.timeSeconds;
  // Preserve an imported/custom bench instead of silently replacing it.
  if (r.bench && !Array.from(ui.bench.options).some(o => o.value === r.bench)) ui.bench.add(new Option(r.bench, r.bench));
  ui.bench.value = r.bench;
  ui.notes.value = r.notes;
  renderIngredients();
}
function renderIngredients() {
  const r = recipe(), fragment = document.createDocumentFragment();
  for (const ingredient of r.ingredients) {
    const item = lookup.get(ingredient.itemId);
    const row = element('li', 'ingredient');
    row.append(icon(item, 38));
    const label = element('div', 'ingredient-label');
    label.append(element('strong', '', item.name));
    label.append(element('small', '', item.namePt !== item.name ? item.namePt : item.group));
    row.append(label);
    const quantity = element('input');
    Object.assign(quantity, {type: 'number', min: '1', max: '9999', step: '1', value: String(ingredient.quantity)});
    quantity.setAttribute('aria-label', `Quantidade de ${item.name}`);
    quantity.oninput = () => {
      if (!quantity.checkValidity()) return;
      ingredient.quantity = Number(quantity.value); changed(); renderItems();
    };
    quantity.onblur = () => { if (!quantity.checkValidity()) quantity.value = ingredient.quantity; };
    const remove = element('button', 'remove', '×');
    remove.type = 'button'; remove.title = `Remover ${item.name}`; remove.setAttribute('aria-label', remove.title);
    remove.onclick = () => { recipes[current].ingredients = r.ingredients.filter(i => i.itemId !== item.id); changed(); renderIngredients(); renderItems(); };
    row.append(quantity, remove); fragment.append(row);
  }
  ui.ingredients.replaceChildren(fragment);
  ui['empty-recipe'].hidden = r.ingredients.length > 0;
  ui['ingredient-count'].textContent = `${r.ingredients.length} ${r.ingredients.length === 1 ? 'item' : 'itens'}`;
  ui['ready-button'].textContent = r.status === 'ready' ? 'Definida ✓ · Voltar a rascunho' : 'Marcar como definida';
  ui['ready-button'].disabled = !r.ingredients.length || !r.bench;
  ui['clear-button'].disabled = !recipes[current];
}
function filterItems() {
  const terms = normalize(ui.search.value.trim()).split(/\s+/).filter(Boolean), group = ui.category.value;
  filtered = items.filter(item => (!group || item.group === group) && terms.every(t => item.search.includes(t)));
  limit = 72; renderItems();
}
function renderItems() {
  const selected = new Map(recipe().ingredients.map(i => [i.itemId, i.quantity]));
  const fragment = document.createDocumentFragment();
  for (const item of filtered.slice(0, limit)) {
    const button = element('button', `item${selected.has(item.id) ? ' is-selected' : ''}`);
    button.type = 'button';
    button.title = `${item.name}\n${item.namePt}\n${item.id}`;
    button.setAttribute('aria-label', `Adicionar ${item.name}`);
    button.append(icon(item));
    const label = element('span');
    label.append(element('strong', '', item.name));
    label.append(element('small', '', item.namePt !== item.name ? item.namePt : item.group));
    button.append(label);
    if (selected.has(item.id)) button.append(element('span', 'selected-count', format(selected.get(item.id))));
    button.disabled = item.id === current;
    button.onclick = () => addIngredient(item);
    fragment.append(button);
  }
  ui['item-grid'].replaceChildren(fragment);
  ui['result-count'].textContent = `${format(filtered.length)} ${filtered.length === 1 ? 'item encontrado' : 'itens encontrados'}`;
  ui['empty-search'].hidden = filtered.length > 0;
  ui['load-more'].hidden = filtered.length <= limit;
}
function addIngredient(item) {
  if (!ui['recipe-form'].reportValidity()) return;
  if (!recipes[current]) recipes[current] = emptyRecipe();
  const r = recipes[current], existing = r.ingredients.find(i => i.itemId === item.id);
  if (existing) {
    if (existing.quantity >= 9999) return toast('Limite de 9999 unidades por ingrediente.');
    existing.quantity++;
  } else {
    if (r.ingredients.length >= 64) return toast('Limite de 64 ingredientes por receita.');
    r.ingredients.push({itemId: item.id, quantity: 1});
  }
  changed(); renderIngredients(); renderItems();
  toast(`${item.name} adicionado`);
}
function backup() {
  try { localStorage.setItem(draftKey, JSON.stringify({baseRevision: revision, recipes})); } catch {}
}
function changed(demote = true) {
  if (demote && recipes[current]) recipes[current].status = 'draft';
  ui['ready-button'].textContent = recipe().status === 'ready' ? 'Definida ✓ · Voltar a rascunho' : 'Marcar como definida';
  dirty = true; serial++; backup(); renderTargets();
  status('Alterações pendentes…');
  clearTimeout(timer); timer = setTimeout(save, 450);
}
async function save() {
  if (saving || !dirty || conflict) return;
  saving = true;
  const sentSerial = serial;
  status('Salvando…');
  try {
    const body = await request('/api/recipes', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({revision, recipes})});
    revision = body.revision;
    if (serial === sentSerial) {
      dirty = false;
      try { localStorage.removeItem(draftKey); } catch {}
      status('Salvo no projeto', 'saved');
    } else backup();
    error('');
  } catch (failure) {
    status('Não salvo', 'error'); error(failure.message);
    if (failure.status === 409) conflict = true;
  } finally {
    saving = false;
    if (dirty && !conflict) timer = setTimeout(save, 1800);
  }
}
function updateField(key, value) {
  if (!recipes[current]) recipes[current] = emptyRecipe();
  recipes[current][key] = value;
  changed();
  ui['ready-button'].disabled = !recipe().ingredients.length || !recipe().bench;
  ui['ready-button'].textContent = 'Marcar como definida';
  ui['clear-button'].disabled = false;
}
function exportFile() {
  const data = {version: 1, revision, recipes};
  const a = element('a');
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)+'\n'], {type: 'application/json'}));
  a.href = url; a.download = 'advanced-storage-crafts.json'; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function validateImport(data) {
  if (data.version !== 1 || !data.recipes || Array.isArray(data.recipes) || typeof data.recipes !== 'object') throw new Error('Formato de arquivo incompatível. Importe um JSON exportado por este editor.');
  const ids = new Set(targets.map(item => item.id));
  for (const [id, r] of Object.entries(data.recipes)) {
    if (!ids.has(id) || !r || !Array.isArray(r.ingredients) || r.ingredients.length > 64) throw new Error('A receita contém um resultado inválido.');
    const seen = new Set();
    for (const i of r.ingredients) {
      if (!i || !lookup.has(i.itemId) || i.itemId === id || seen.has(i.itemId) || !Number.isInteger(i.quantity) || i.quantity < 1 || i.quantity > 9999) throw new Error('A receita contém ingredientes ou quantidades inválidos.');
      seen.add(i.itemId);
    }
    if (!Number.isInteger(r.outputQuantity) || r.outputQuantity < 1 || r.outputQuantity > 9999 || typeof r.timeSeconds !== 'number' || !Number.isFinite(r.timeSeconds) || r.timeSeconds < 0 || r.timeSeconds > 86400 || typeof r.bench !== 'string' || r.bench.length > 120 || typeof r.notes !== 'string' || r.notes.length > 2000 || !['draft', 'ready'].includes(r.status)) throw new Error('Confira a bancada, o tempo e a quantidade de saída das receitas.');
    if (r.status === 'ready' && (!r.bench || !r.ingredients.length)) throw new Error('Uma receita definida precisa de ingredientes e bancada.');
  }
}

async function init() {
  try {
    const [catalog, saved] = await Promise.all([request('/api/catalog'), request('/api/recipes')]);
    items = catalog.items;
    items.forEach(item => item.search = normalize(`${item.name} ${item.namePt} ${item.id}`));
    lookup = new Map(items.map(item => [item.id, item])); targets = catalog.targets;
    if (!targets.length) throw new Error('Nenhum item do mod foi encontrado. Confira a pasta pack.');
    recipes = saved.recipes; revision = saved.revision;
    for (const group of [...new Set(items.map(item => item.group))].sort((a, b) => a.localeCompare(b, 'pt-BR'))) ui.category.add(new Option(group, group));
    for (const bench of catalog.benches) ui.bench.add(new Option(bench.replaceAll('_', ' '), bench));
    let lastSelected;
    try { lastSelected = localStorage.getItem('advanced-storage-craft-selected'); } catch {}
    current = targets.some(t => t.id === lastSelected) ? lastSelected : targets[0].id;
    let recovered = false;
    try {
      const pending = JSON.parse(localStorage.getItem(draftKey));
      if (pending && pending.baseRevision === revision) {
        validateImport({version: 1, recipes: pending.recipes});
        recipes = pending.recipes; dirty = true; recovered = true;
      } else if (pending) error('Há um rascunho antigo neste navegador. As receitas do projeto foram mantidas.');
    } catch {}
    ui['catalog-count'].textContent = `${format(items.length)} itens`;
    renderTargets(); renderRecipe(); filterItems();
    status('Salvo no projeto', 'saved');
    if (recovered) { toast('Alterações pendentes recuperadas.'); save(); }
    ui.search.oninput = filterItems; ui.category.onchange = filterItems;
    ui['load-more'].onclick = () => { limit += 72; renderItems(); };
    ui['recipe-form'].onsubmit = event => event.preventDefault();
    for (const [id, key] of [['output-quantity', 'outputQuantity'], ['time-seconds', 'timeSeconds']]) {
      ui[id].oninput = () => { if (ui[id].checkValidity()) updateField(key, Number(ui[id].value)); };
      ui[id].onblur = () => { if (!ui[id].checkValidity()) ui[id].value = recipe()[key]; };
    }
    ui.bench.onchange = () => updateField('bench', ui.bench.value);
    ui.notes.oninput = () => updateField('notes', ui.notes.value);
    ui['ready-button'].onclick = () => {
      if (!ui['recipe-form'].reportValidity()) return;
      recipe().status = recipe().status === 'ready' ? 'draft' : 'ready';
      changed(false); renderIngredients();
    };
    ui['clear-button'].onclick = () => {
      if (!confirm(`Limpar a receita de ${lookup.get(current).name}?`)) return;
      delete recipes[current]; changed(); renderRecipe(); renderItems();
    };
    ui['export-button'].onclick = exportFile;
    ui['import-button'].onclick = () => ui['import-file'].click();
    ui['import-file'].onchange = async () => {
      const file = ui['import-file'].files[0];
      if (!file) return;
      try {
        if (file.size > 300000) throw new Error('O arquivo é grande demais. Limite: 300 KB.');
        const data = JSON.parse(await file.text()); validateImport(data);
        if (Object.keys(recipes).length && !confirm('Substituir as receitas atuais pelas receitas do arquivo?')) return;
        recipes = structuredClone(data.recipes); changed(false); renderTargets(); renderRecipe(); renderItems(); toast('Receitas importadas.');
      } catch (failure) { error(`Não foi possível importar: ${failure.message}`); }
      finally { ui['import-file'].value = ''; }
    };
    document.addEventListener('keydown', event => {
      if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) { event.preventDefault(); ui.search.focus(); }
      if ((event.ctrlKey || event.metaKey) && event.key === 's') { event.preventDefault(); save(); }
    });
    window.addEventListener('beforeunload', event => { if (dirty) { backup(); event.preventDefault(); event.returnValue = ''; } });
  } catch (failure) { error(`Não foi possível carregar o editor: ${failure.message}`); status('Falha ao carregar', 'error'); }
}
init();
