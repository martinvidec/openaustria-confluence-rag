# Spec 08: Chat-Frontend

**Issues:** #13, #14
**Phase:** 5
**Abhängigkeiten:** #12 (REST API + SSE Streaming muss stehen)

---

## Ziel

Einfaches, funktionales Chat-Interface als SPA. Streaming-Antworten, Quellenangaben mit Links zu Confluence, Space-Filter.

---

## Technologie-Entscheidung

**React + Vite + TypeScript** als SPA, eingebettet in Spring Boot (`src/main/resources/static/`).

Begründung:
- Vite für schnellen Dev-Server
- React für Komponentenstruktur und State Management
- TypeScript für Typsicherheit
- Kein UI-Framework nötig — Custom CSS reicht für MVP
- Build-Output wird nach `src/main/resources/static/` kopiert

Alternative (falls kein JS-Build gewünscht): Vanilla JS + HTML. In dem Fall die Komponenten-Struktur unten als Orientierung für die HTML-Struktur verwenden.

### Projektstruktur (Frontend)

```
frontend/
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── api/
│   │   └── chatApi.ts          # API-Client + SSE-Handling
│   ├── components/
│   │   ├── ChatContainer.tsx   # Hauptcontainer
│   │   ├── MessageList.tsx     # Nachrichtenverlauf
│   │   ├── MessageBubble.tsx   # Einzelne Nachricht
│   │   ├── SourceList.tsx      # Quellenangaben
│   │   ├── ChatInput.tsx       # Eingabefeld
│   │   └── SpaceFilter.tsx     # Space-Auswahl
│   ├── types/
│   │   └── index.ts            # TypeScript Types
│   └── styles/
│       └── app.css
```

---

## Issue #13 — Chat UI Grundgerüst & Streaming

### TypeScript Types

```typescript
interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
  isStreaming?: boolean;
  timestamp: Date;
}

interface Source {
  title: string;
  url: string;
  spaceKey: string;
}

interface QueryRequest {
  question: string;
  spaceFilter?: string[];
}

interface SpaceInfo {
  key: string;
  name: string;
}
```

### API-Client (chatApi.ts)

```typescript
const API_BASE = '/api';  // Relativer Pfad, Spring Boot serviert beides

/**
 * Streaming-Chat via Server-Sent Events.
 * Ruft onToken für jedes Token, onSources am Ende, onDone wenn fertig.
 */
export async function streamChat(
  request: QueryRequest,
  callbacks: {
    onToken: (token: string) => void;
    onSources: (sources: Source[]) => void;
    onDone: () => void;
    onError: (error: string) => void;
  }
): Promise<void> {
  const response = await fetch(`${API_BASE}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.startsWith('event: ')) {
        // Event-Typ merken
      }
      if (line.startsWith('data: ')) {
        const data = JSON.parse(line.slice(6));
        // Je nach Event-Typ: onToken, onSources, onDone aufrufen
      }
    }
  }
}

/**
 * Verfügbare Spaces laden.
 */
export async function getSpaces(): Promise<SpaceInfo[]> {
  const res = await fetch(`${API_BASE}/spaces`);
  return res.json();
}
```

### App-Komponente (App.tsx)

```typescript
function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [spaces, setSpaces] = useState<SpaceInfo[]>([]);
  const [selectedSpaces, setSelectedSpaces] = useState<string[]>([]);

  // Spaces beim Start laden
  useEffect(() => { loadSpaces(); }, []);

  async function handleSend(question: string) {
    // 1. User-Message hinzufügen
    // 2. Leere Assistant-Message hinzufügen (isStreaming: true)
    // 3. streamChat aufrufen
    //    - onToken: Content der Assistant-Message erweitern
    //    - onSources: Sources der Assistant-Message setzen
    //    - onDone: isStreaming: false setzen
  }

  return (
    <div className="app">
      <header>Confluence RAG Chat</header>
      <SpaceFilter spaces={spaces} selected={selectedSpaces} onChange={setSelectedSpaces} />
      <MessageList messages={messages} />
      <ChatInput onSend={handleSend} isLoading={isLoading} />
    </div>
  );
}
```

### Layout & Styling

```
+--------------------------------------------------+
|  Confluence RAG Chat              [Space-Filter]  |
+--------------------------------------------------+
|                                                    |
|  [User]     Wie funktioniert die REST API?         |
|                                                    |
|  [Bot]      Die REST API unterstützt folgende...   |
|             ████████████░░░░  (streaming)          |
|                                                    |
|             📄 Quellen:                            |
|             • API-Dokumentation (DEV)              |
|             • REST Guidelines (ARCH)               |
|                                                    |
+--------------------------------------------------+
|  [Eingabefeld..................]  [Senden]         |
+--------------------------------------------------+
```

### Markdown-Rendering

Antworten des LLM enthalten oft Markdown. Verwende `react-markdown` zum Rendern:

```bash
npm install react-markdown
```

```tsx
import ReactMarkdown from 'react-markdown';

function MessageBubble({ message }: { message: Message }) {
  return (
    <div className={`message ${message.role}`}>
      <ReactMarkdown>{message.content}</ReactMarkdown>
      {message.sources && <SourceList sources={message.sources} />}
      {message.isStreaming && <span className="cursor">▊</span>}
    </div>
  );
}
```

---

## Issue #14 — Quellenangabe & Space-Filter

### SourceList-Komponente

```tsx
function SourceList({ sources }: { sources: Source[] }) {
  return (
    <div className="sources">
      <span className="sources-label">Quellen:</span>
      <ul>
        {sources.map((source, i) => (
          <li key={i}>
            <a href={source.url} target="_blank" rel="noopener noreferrer">
              {source.title}
            </a>
            <span className="space-badge">{source.spaceKey}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

### Space-Badge Styling

```css
.space-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 600;
  background-color: #e2e8f0;
  color: #475569;
  margin-left: 8px;
}
```

### SpaceFilter-Komponente

```tsx
function SpaceFilter({ spaces, selected, onChange }: {
  spaces: SpaceInfo[];
  selected: string[];
  onChange: (keys: string[]) => void;
}) {
  function toggle(key: string) {
    onChange(
      selected.includes(key)
        ? selected.filter(k => k !== key)
        : [...selected, key]
    );
  }

  return (
    <div className="space-filter">
      <span>Spaces:</span>
      <button
        className={selected.length === 0 ? 'active' : ''}
        onClick={() => onChange([])}
      >
        Alle
      </button>
      {spaces.map(space => (
        <button
          key={space.key}
          className={selected.includes(space.key) ? 'active' : ''}
          onClick={() => toggle(space.key)}
        >
          {space.key}
        </button>
      ))}
    </div>
  );
}
```

### Fehlerzustände

| Zustand | UI-Darstellung |
|---|---|
| API nicht erreichbar | Rote Banner-Meldung oben: "Backend nicht erreichbar" |
| Streaming-Fehler | Fehlermeldung in der Assistant-Message: "Fehler bei der Antwortgenerierung" |
| Keine Ergebnisse | LLM-Antwort enthält bereits Hinweis (via System-Prompt) |
| Leere Frage | Senden-Button deaktiviert |

### Loading-State

```tsx
function ChatInput({ onSend, isLoading }: { onSend: (q: string) => void; isLoading: boolean }) {
  const [input, setInput] = useState('');

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (input.trim() && !isLoading) {
      onSend(input.trim());
      setInput('');
    }
  }

  return (
    <form className="chat-input" onSubmit={handleSubmit}>
      <input
        value={input}
        onChange={e => setInput(e.target.value)}
        placeholder="Stelle eine Frage zur Confluence-Dokumentation..."
        disabled={isLoading}
      />
      <button type="submit" disabled={!input.trim() || isLoading}>
        {isLoading ? '...' : 'Senden'}
      </button>
    </form>
  );
}
```

### Build & Deployment

```json
// vite.config.ts
export default defineConfig({
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
});
```

Build-Prozess:

```bash
cd frontend && npm run build
# Output landet in src/main/resources/static/
# Spring Boot serviert automatisch unter /
```

---

## Akzeptanzkriterien

### Issue #13

- [ ] Chat-UI zeigt Nachrichtenverlauf korrekt an
- [ ] User kann Frage eingeben und absenden (Enter oder Button)
- [ ] Antwort wird gestreamt (Token für Token)
- [ ] Markdown in Antworten wird gerendert (Überschriften, Listen, Code)
- [ ] Streaming-Cursor (▊) wird während des Streamings angezeigt
- [ ] UI ist responsive (funktioniert auf Desktop und Tablet)

### Issue #14

- [ ] Quellen werden als klickbare Links unter der Antwort angezeigt
- [ ] Space-Badge zeigt den Space-Key an
- [ ] Space-Filter erlaubt Einschränkung auf bestimmte Spaces
- [ ] "Alle" Button setzt Filter zurück
- [ ] Loading-State: Input deaktiviert während Streaming
- [ ] Fehlermeldungen werden im Chat angezeigt
- [ ] `npm run build` erzeugt Output in `src/main/resources/static/`
