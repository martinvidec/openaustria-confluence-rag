const API_BASE = '/api';

let selectedSpaces = [];
let isLoading = false;

const messageList = document.getElementById('message-list');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const sendBtn = document.getElementById('send-btn');
const spaceFilterContainer = document.getElementById('space-filter');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadSpaces();
    chatForm.addEventListener('submit', handleSubmit);
});

async function loadSpaces() {
    try {
        const res = await fetch(`${API_BASE}/spaces`);
        const spaces = await res.json();
        renderSpaceFilter(spaces);
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
            // "Alle" button
            btn.classList.toggle('active', selectedSpaces.length === 0);
        } else {
            btn.classList.toggle('active', selectedSpaces.includes(btn.dataset.key));
        }
    });
}

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

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
            if (line.startsWith('event:')) {
                currentEvent = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                const dataStr = line.slice(5).trim();
                if (!dataStr) continue;

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
    }

    // Remove cursor, render final content
    renderMarkdown(contentEl, fullText);

    // Add sources
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
    // Remove cursor from content
    const cursor = messageEl.querySelector('.cursor');
    if (cursor) cursor.remove();

    const sourcesDiv = document.createElement('div');
    sourcesDiv.className = 'sources';
    sourcesDiv.innerHTML = `
        <span class="sources-label">Quellen:</span>
        <ul>
            ${sources.map(s => `
                <li>
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
