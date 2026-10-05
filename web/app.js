'use strict';
const $ = s => document.querySelector(s);
let me, csrf, page = 1, historyPage = 1, usersPage = 1;
const date = value => new Date(value).toLocaleString('pt-BR');
function notice(message, error = false) { $('#notice').textContent = message; $('#notice').classList.toggle('error', error); $('#notice').hidden = false; }
async function api(path, method = 'GET', data) {
  const response = await fetch('/api' + path, {method, headers: {'Content-Type':'application/x-www-form-urlencoded', ...(csrf ? {'X-CSRF-Token':csrf} : {})}, body: data ? new URLSearchParams(data) : undefined});
  const result = await response.json();
  if (!response.ok) { if (response.status === 401 && me) showAuth(); throw new Error(result.message || 'Não foi possível concluir.'); }
  return result;
}
function values(form) { return Object.fromEntries(new FormData(form)); }
function bindForm(id, action) {
  $(id).addEventListener('submit', async e => { e.preventDefault(); const button = e.target.querySelector('button'); button.disabled = true;
    try { await action(e.target); } catch (err) { notice(err.message, true); } finally { button.disabled = false; }
  });
}
function action(button, callback) {
  button.addEventListener('click', async () => { button.disabled = true; try { await callback(); } catch (e) { notice(e.message,true); } finally { button.disabled = false; } });
}
function cell(row, text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
function button(parent, text, callback, danger = false) { const b = document.createElement('button'); b.textContent = text; b.className = 'small ' + (danger ? 'danger' : 'secondary'); parent.append(b); action(b,callback); }
function showAuth() { me = null; csrf = null; $('#workspace').hidden = true; $('#auth').hidden = false; $('#logout').hidden = true; $('#identity').textContent = ''; }
async function showWorkspace(result) {
  me = result.user; csrf = result.csrf; $('#auth').hidden = true; $('#workspace').hidden = false; $('#logout').hidden = false;
  $('#identity').textContent = me.name + ' · ' + (me.role === 'ADMINISTRADOR' ? 'Administrador' : 'Operador');
  $('#users-tab').hidden = me.role !== 'ADMINISTRADOR';
  $('#account-form').elements.name.value = me.name; $('#account-form').elements.email.value = me.email;
  await tab('products');
}
async function tab(name) {
  document.querySelectorAll('.tab').forEach(e => e.hidden = e.id !== name);
  document.querySelectorAll('[data-tab]').forEach(e => e.classList.toggle('active',e.dataset.tab === name));
  if (name === 'products') await loadProducts(); if (name === 'movements') await loadMovements(); if (name === 'users') await loadUsers();
}
function paginate(result, info, previous, next) {
  $(info).textContent = `Página ${result.page} de ${Math.max(1,Math.ceil(result.total/result.size))} · ${result.total} registros`;
  $(previous).disabled = result.page <= 1; $(next).disabled = result.page * result.size >= result.total;
}
async function loadProducts() {
  const result = await api('/products?' + new URLSearchParams({...values($('#filters')),page,size:10}));
  if (page > 1 && result.items.length === 0) { page--; return loadProducts(); }
  const rows = $('#product-rows'); rows.replaceChildren();
  $('#product-select').replaceChildren(new Option('Selecione',''));
  for (const p of result.items) {
    $('#product-select').add(new Option(p.name + ' · saldo ' + p.balance, p.id));
    const row = document.createElement('tr'); cell(row,p.name); cell(row,p.unit); cell(row,p.balance); cell(row,date(p.created)); const a = cell(row,'');
    button(a,'Movimentar',() => { $('#movement-form').elements.productId.value = p.id; $('#movement-form').elements.quantity.focus(); });
    button(a,'Editar',() => { const form = $('#product-form'); form.elements.id.value = p.id; form.elements.name.value = p.name; form.elements.unit.value = p.unit; $('#product-title').textContent = 'Editar produto'; $('#cancel-edit').hidden = false; form.elements.name.focus(); });
    if (me.role === 'ADMINISTRADOR') button(a,'Excluir',async () => { if (!confirm('Excluir ' + p.name + '? O saldo deve ser zero.')) return; await api('/products/' + p.id,'DELETE'); await loadProducts(); notice('Produto excluído.'); },true);
    rows.append(row);
  }
  if (!result.items.length) { const row = document.createElement('tr'); const td = cell(row,'Nenhum produto encontrado.'); td.colSpan = 5; rows.append(row); }
  paginate(result,'#page-info','#previous','#next');
}
async function loadMovements() {
  const result = await api('/movements?page=' + historyPage + '&size=10'); const rows = $('#movement-rows'); rows.replaceChildren();
  for (const m of result.items) {
    const row = document.createElement('tr'); [date(m.timestamp),m.productName,m.type,m.quantity,m.before,m.after,m.userName].forEach(t => cell(row,t)); const a = cell(row,'');
    if (m.reversedId) a.textContent = 'Estorno de #' + m.reversedId;
    else if (me.role === 'ADMINISTRADOR') button(a,'Estornar',async () => { if (!confirm('Estornar esta movimentação? O saldo será recalculado e o histórico preservado.')) return; await api('/movements/' + m.id,'DELETE'); await loadMovements(); notice('Estorno registrado.'); },true);
    rows.append(row);
  }
  if (!result.items.length) { const row = document.createElement('tr'); const td = cell(row,'Nenhuma movimentação registrada.'); td.colSpan = 8; rows.append(row); }
  paginate(result,'#history-info','#history-previous','#history-next');
}
async function loadUsers() {
  const result = await api('/users?page=' + usersPage + '&size=10'); const rows = $('#user-rows'); rows.replaceChildren();
  for (const u of result.items) {
    const row = document.createElement('tr'); [u.name,u.email,u.role].forEach(t => cell(row,t)); const a = cell(row,'');
    button(a,'Editar',() => { const f = $('#user-form'); f.reset(); for (const key of ['id','name','email','role']) f.elements[key].value = u[key]; f.elements.password.required = false; $('#user-title').textContent = 'Editar usuário (senha vazia mantém a atual)'; $('#cancel-user').hidden = false; f.elements.name.focus(); });
    if (u.id !== me.id) button(a,'Excluir',async () => { if (!confirm('Excluir a conta de ' + u.name + '?')) return; await api('/users/' + u.id,'DELETE'); await loadUsers(); notice('Usuário excluído.'); },true);
    rows.append(row);
  }
  paginate(result,'#users-info','#users-previous','#users-next');
}
bindForm('#login',async form => { const result = await api('/login','POST',values(form)); form.reset(); $('#notice').hidden = true; page = 1; await showWorkspace(result); });
bindForm('#register',async form => { await api('/register','POST',values(form)); form.reset(); $('#register').hidden = true; $('#login').hidden = false; notice('Conta criada. Entre com seu e-mail e senha.'); });
action($('#show-register'),() => { $('#login').hidden = true; $('#register').hidden = false; });
action($('#show-login'),() => { $('#register').hidden = true; $('#login').hidden = false; });
action($('#logout'),async () => { await api('/logout','POST'); showAuth(); notice('Sessão encerrada.'); });
document.querySelectorAll('[data-tab]').forEach(b => action(b,() => tab(b.dataset.tab)));
bindForm('#filters',async () => { page = 1; await loadProducts(); });
action($('#clear-filters'),async () => { $('#filters').reset(); page = 1; await loadProducts(); });
bindForm('#product-form',async form => { const data = values(form); await api('/products' + (data.id ? '/' + data.id : ''),data.id ? 'PUT' : 'POST',data); form.reset(); $('#product-title').textContent = 'Cadastrar produto'; $('#cancel-edit').hidden = true; await loadProducts(); notice('Produto salvo.'); });
action($('#cancel-edit'),() => { $('#product-form').reset(); $('#product-title').textContent = 'Cadastrar produto'; $('#cancel-edit').hidden = true; });
bindForm('#movement-form',async form => { await api('/movements','POST',values(form)); form.reset(); await loadProducts(); notice('Movimentação registrada e estoque atualizado.'); });
action($('#previous'),async () => { page--; await loadProducts(); }); action($('#next'),async () => { page++; await loadProducts(); });
action($('#reload-history'),loadMovements); action($('#history-previous'),async () => { historyPage--; await loadMovements(); }); action($('#history-next'),async () => { historyPage++; await loadMovements(); });
bindForm('#user-form',async form => { const data = values(form); await api('/users' + (data.id ? '/' + data.id : ''),data.id ? 'PUT' : 'POST',data); const self = Number(data.id) === me.id; form.reset(); form.elements.password.required = true; $('#user-title').textContent = 'Cadastrar usuário'; $('#cancel-user').hidden = true; if (self) { showAuth(); notice('Dados salvos. Entre novamente.'); } else { await loadUsers(); notice('Usuário salvo.'); } });
action($('#cancel-user'),() => { $('#user-form').reset(); $('#user-form').elements.password.required = true; $('#user-title').textContent = 'Cadastrar usuário'; $('#cancel-user').hidden = true; });
action($('#users-previous'),async () => { usersPage--; await loadUsers(); }); action($('#users-next'),async () => { usersPage++; await loadUsers(); });
bindForm('#account-form',async form => { await api('/users/' + me.id,'PUT',values(form)); form.reset(); showAuth(); notice('Dados atualizados. Entre novamente.'); });
api('/me').then(showWorkspace).catch(e => { showAuth(); if (e.message !== 'Entre para acessar o sistema.') notice(e.message,true); });
