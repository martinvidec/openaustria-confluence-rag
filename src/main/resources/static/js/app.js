const API_BASE = '/api';

let selectedSpaces = [];
let allSpaces = [];
let isLoading = false;
let isSyncing = false;

const messageList = document.getElementById('message-list');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const sendBtn = document.getElementById('send-btn');
const spaceFilterContainer = document.getElementById('space-filter');
const syncBtn = document.getElementById('sync-btn');
const adminToggle = document.getElementById('admin-toggle');
const adminPanel = document.getElementById('admin-panel');
const adminClose = document.getElementById('admin-close');
const adminSpaces = document.getElementById('admin-spaces');
const syncAllBtn = document.getElementById('sync-all-btn');
const ingestAllBtn = document.getElementById('ingest-all-btn');

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', () => {
    loadSpaces();
    loadSyncStatus();
    chatForm.addEventListener('submit', handleSubmit);
    syncBtn.addEventListener('click', () => triggerSync());
    adminToggle.addEventListener('click', toggleAdminPanel);
    adminClose.addEventListener('click', () => adminPanel.classList.add('hidden'));
    syncAllBtn.addEventListener('click', () => triggerSync());
    ingestAllBtn.addEventListener('click', () => triggerIngest());
});

// ==================== Spaces ====================

async function loadSpaces() {
    try {
        const res = await fetch(`${API_BASE}/spaces`);
        allSpaces = await res.json();
        renderSpaceFilter(allSpaces);
    } catch (e) {
        console.error('Spaces konnten nicht geladen werden:', e);
    }
}

function renderSpaceFilter(spaces) {
    if (!spaces || spaces.length === 0) return;

    const allBtn = document.createElement('button');
    allBtn.type = 'button';
    allBtn.className = 'space-filter-btn active';
    allBtn.textContent = 'Alle';
    allBtn.addEventListener('click', () => {
        selectedSpaces = [];
        updateFilterButtons();
    });
    spaceFilterContainer.appendChild(allBtn);

    spaces.forEach(space => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'space-filter-btn';
        btn.textContent = space.key;
        btn.dataset.key = space.key;
        btn.addEventListener('click', () => toggleSpace(space.key));
        spaceFilterContainer.appendChild(btn);
    });
}

function toggleSpace(key) {
    const idx = selectedSpaces.indexOf(key);
    if (idx >= 0) {
        selectedSpaces.splice(idx, 1);
    } else {
        selectedSpaces.push(key);
    }
    updateFilterButtons();
}

function updateFilterButtons() {
    const buttons = spaceFilterContainer.querySelectorAll('.space-filter-btn');
    buttons.forEach(btn => {
        if (!btn.dataset.key) {
            btn.classList.toggle('active', selectedSpaces.length === 0);
        } else {
            btn.classList.toggle('active', selectedSpaces.includes(btn.dataset.key));
        }
    });
}

// ==================== Admin Panel ====================

function toggleAdminPanel() {
    adminPanel.classList.toggle('hidden');
    if (!adminPanel.classList.contains('hidden')) {
        loadSyncStatus();
    }
}

async function loadSyncStatus() {
    try {
        const res = await fetch(`${API_BASE}/admin/sync/status`);
        const statusMap = await res.json();
        renderAdminSpaces(statusMap);
    } catch (e) {
        console.error('Sync-Status konnte nicht geladen werden:', e);
    }
}

function renderAdminSpaces(statusMap) {
    adminSpaces.innerHTML = '';

    if (allSpaces.length === 0) {
        adminSpaces.innerHTML = '<div class="admin-empty">Keine Spaces konfiguriert</div>';
        return;
    }

    allSpaces.forEach(space => {
        const state = statusMap[space.key];
        const lastSync = state && state.lastSync
            ? new Date(state.lastSync).toLocaleString('de-AT')
            : 'Noch nie';
        const pageCount = state && state.knownPageIds
            ? state.knownPageIds.length
            : 0;

        const div = document.createElement('div');
        div.className = 'admin-space-row';
        div.innerHTML = `
            <div class="admin-space-info">
                <span class="admin-space-key">${escapeHtml(space.key)}</span>
                <span class="admin-space-detail">Letzter Sync: ${escapeHtml(lastSync)}</span>
                <span class="admin-space-detail">${pageCount} Seiten indexiert</span>
            </div>
            <div class="admin-space-actions">
                <button class="admin-btn admin-btn-sm" data-action="sync" data-space="${escapeHtml(space.key)}">Sync</button>
                <button class="admin-btn admin-btn-sm danger-btn" data-action="ingest" data-space="${escapeHtml(space.key)}">Voll-Ingest</button>
            </div>
        `;
        adminSpaces.appendChild(div);
    });

    adminSpaces.querySelectorAll('[data-action="sync"]').forEach(btn => {
        btn.addEventListener('click', () => triggerSpaceSync(btn.dataset.space));
    });
    adminSpaces.querySelectorAll('[data-action="ingest"]').forEach(btn => {
        btn.addEventListener('click', () => triggerSpaceIngest(btn.dataset.space));
    });
}

// ==================== Sync & Ingest ====================

async function triggerSync() {
    if (isSyncing) return;
    setSyncing(true);
    try {
        const res = await fetch(`${API_BASE}/admin/sync`, { method: 'POST' });
        const result = await res.json();
        if (res.ok) {
            showToast(`Sync abgeschlossen: ${result.pagesUpdated} aktualisiert, ${result.pagesDeleted} gelöscht, ${result.chunksCreated} Chunks (${formatDuration(result.duration)})`, 'success');
        } else {
            showToast(`Sync fehlgeschlagen: ${result.message || 'Unbekannter Fehler'}`, 'error');
        }
    } catch (e) {
        showToast('Sync fehlgeschlagen: ' + e.message, 'error');
    } finally {
        setSyncing(false);
        loadSyncStatus();
    }
}

async function triggerSpaceSync(spaceKey) {
    if (isSyncing) return;
    setSyncing(true);
    try {
        const res = await fetch(`${API_BASE}/admin/sync/${spaceKey}`, { method: 'POST' });
        const result = await res.json();
        if (res.ok) {
            showToast(`Space ${spaceKey}: ${result.pagesUpdated} aktualisiert, ${result.pagesDeleted} gelöscht, ${result.chunksCreated} Chunks`, 'success');
        } else {
            showToast(`Sync ${spaceKey} fehlgeschlagen: ${result.message || 'Fehler'}`, 'error');
        }
    } catch (e) {
        showToast(`Sync ${spaceKey} fehlgeschlagen: ` + e.message, 'error');
    } finally {
        setSyncing(false);
        loadSyncStatus();
    }
}

async function triggerIngest() {
    if (isSyncing) return;
    if (!confirm('Alle Spaces komplett neu ingesten? Das kann mehrere Minuten dauern und ersetzt alle bestehenden Daten.')) return;
    setSyncing(true);
    try {
        const res = await fetch(`${API_BASE}/admin/ingest`, { method: 'POST' });
        const result = await res.json();
        if (res.ok) {
            showToast(`Ingest abgeschlossen: ${result.pagesProcessed} Seiten, ${result.chunksStored} Chunks (${formatDuration(result.duration)})`, 'success');
        } else {
            showToast(`Ingest fehlgeschlagen: ${result.message || 'Fehler'}`, 'error');
        }
    } catch (e) {
        showToast('Ingest fehlgeschlagen: ' + e.message, 'error');
    } finally {
        setSyncing(false);
        loadSyncStatus();
    }
}

async function triggerSpaceIngest(spaceKey) {
    if (isSyncing) return;
    if (!confirm(`Space "${spaceKey}" komplett neu ingesten?`)) return;
    setSyncing(true);
    try {
        const res = await fetch(`${API_BASE}/admin/ingest/${spaceKey}`, { method: 'POST' });
        const result = await res.json();
        if (res.ok) {
            showToast(`Space ${spaceKey}: ${result.pagesProcessed} Seiten, ${result.chunksStored} Chunks`, 'success');
        } else {
            showToast(`Ingest ${spaceKey} fehlgeschlagen: ${result.message || 'Fehler'}`, 'error');
        }
    } catch (e) {
        showToast(`Ingest ${spaceKey} fehlgeschlagen: ` + e.message, 'error');
    } finally {
        setSyncing(false);
        loadSyncStatus();
    }
}

function setSyncing(syncing) {
    isSyncing = syncing;
    syncBtn.disabled = syncing;
    syncBtn.textContent = syncing ? 'Sync...' : 'Sync';
    syncBtn.classList.toggle('syncing', syncing);
    syncAllBtn.disabled = syncing;
    ingestAllBtn.disabled = syncing;
    adminSpaces.querySelectorAll('.admin-btn').forEach(btn => btn.disabled = syncing);
}

function formatDuration(duration) {
    if (!duration) return '?';
    const seconds = typeof duration === 'number' ? duration : duration.seconds || 0;
    return seconds < 60 ? `${Math.round(seconds)}s` : `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
}

// ==================== Toast ====================

function showToast(message, type) {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    const icon = type === 'success' ? '\u2705' : type === 'error' ? '\u26A0\uFE0F' : '\u2139\uFE0F';
    toast.textContent = `${icon} ${message}`;
    toast.addEventListener('click', () => toast.remove());
    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('fade-out');
        setTimeout(() => toast.remove(), 400);
    }, 6000);
}

// ==================== Chat ====================

async function handleSubmit(e) {
    e.preventDefault();
    const question = chatInput.value.trim();
    if (!question || isLoading) return;

    setLoading(true);
    chatInput.value = '';

    addMessage('user', question);
    const assistantEl = addMessage('assistant', '');
    const contentEl = assistantEl.querySelector('.message-content');
    contentEl.innerHTML = '<span class="cursor">\u2588</span>';

    try {
        await streamChat(question, contentEl, assistantEl);
    } catch (err) {
        contentEl.innerHTML = '';
        addMessage('error', 'Fehler: ' + (err.message || 'Verbindung fehlgeschlagen'));
    } finally {
        setLoading(false);
        scrollToBottom();
    }
}

async function streamChat(question, contentEl, assistantEl) {
    const body = { question };
    if (selectedSpaces.length > 0) {
        body.spaceFilter = selectedSpaces;
    }

    const response = await fetch(`${API_BASE}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let fullText = '';
    let sources = [];
    let currentEvent = '';

    function handleLine(line) {
        if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim();
            if (!dataStr) return;
            try {
                const data = JSON.parse(dataStr);
                if (currentEvent === 'token' && data.token) {
                    fullText += data.token;
                    renderMarkdown(contentEl, fullText);
                } else if (currentEvent === 'sources' && data.sources) {
                    sources = data.sources;
                }
            } catch (parseErr) {
                // Ignore parse errors for partial data
            }
        }
    }

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            if (buffer.trim()) {
                buffer.split('\n').forEach(handleLine);
            }
            break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        lines.forEach(handleLine);
    }

    // Final render without cursor
    if (typeof marked !== 'undefined') {
        contentEl.innerHTML = marked.parse(fullText);
    } else {
        contentEl.innerHTML = escapeHtml(fullText);
    }

    if (sources.length > 0) {
        renderSources(assistantEl, sources);
    }
}

function renderMarkdown(el, text) {
    if (typeof marked !== 'undefined') {
        el.innerHTML = marked.parse(text) + '<span class="cursor">\u2588</span>';
    } else {
        el.innerHTML = escapeHtml(text) + '<span class="cursor">\u2588</span>';
    }
    scrollToBottom();
}

function renderSources(messageEl, sources) {
    const cursor = messageEl.querySelector('.cursor');
    if (cursor) cursor.remove();

    const sourcesDiv = document.createElement('div');
    sourcesDiv.className = 'sources';
    sourcesDiv.innerHTML = `
        <span class="sources-label">Quellen:</span>
        <ul>
            ${sources.map(s => `
                <li>
                    <span class="source-icon">\u2197</span>
                    <a href="${escapeHtml(s.url)}" target="_blank" rel="noopener noreferrer">
                        ${escapeHtml(s.title)}
                    </a>
                    <span class="space-badge">${escapeHtml(s.spaceKey)}</span>
                </li>
            `).join('')}
        </ul>
    `;
    messageEl.appendChild(sourcesDiv);
}

function addMessage(role, content) {
    const div = document.createElement('div');
    div.className = `message ${role}`;

    if (role === 'user') {
        div.innerHTML = `<div class="message-content">${escapeHtml(content)}</div>`;
    } else if (role === 'error') {
        div.innerHTML = `<div class="message-content">${escapeHtml(content)}</div>`;
    } else {
        div.innerHTML = `<div class="message-content">${content}</div>`;
    }

    messageList.appendChild(div);
    scrollToBottom();
    return div;
}

function setLoading(loading) {
    isLoading = loading;
    chatInput.disabled = loading;
    sendBtn.disabled = loading;
    sendBtn.textContent = loading ? '...' : 'Senden';
    if (!loading) chatInput.focus();
}

function scrollToBottom() {
    messageList.scrollTop = messageList.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
