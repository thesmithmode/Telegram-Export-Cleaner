// The flow controller — mirrors ExportBot.java states exactly.
// States: START → AWAITING_CHAT → AWAITING_DATE_CHOICE → QUEUED → (cancelled | done)

const HELP_TEXT = `Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.

Отправьте одно из:
• username: @durov
• ссылка: https://t.me/durov

Команды: /cancel (отмена активного экспорта)`;

const timeNow = () => new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });

const BotFlow = () => {
  const [msgs, setMsgs] = React.useState([]);
  const [state, setState] = React.useState('start');
  const [chat, setChat] = React.useState('');
  const [taskId] = React.useState(() => Math.random().toString(16).slice(2, 10));
  const scrollRef = React.useRef(null);

  const push = (m) => setMsgs((cur) => [...cur, { ...m, id: cur.length, t: timeNow() }]);

  // init
  React.useEffect(() => {
    setTimeout(() => push({ kind: 'bot', text: HELP_TEXT, kbd: null }), 250);
  }, []);

  React.useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [msgs]);

  const reset = () => {
    setMsgs([]);
    setState('start');
    setChat('');
    setTimeout(() => push({ kind: 'bot', text: HELP_TEXT }), 200);
  };

  // ─── USER SENDS TEXT ──────────────────────────────────────────────
  const handleUserText = (text) => {
    push({ kind: 'user', text });
    if (text === '/start' || text === '/cancel') {
      setTimeout(() => push({ kind: 'bot', text: HELP_TEXT }), 350);
      setState('start');
      return;
    }
    // Try parse as chat
    const m = text.match(/^@([a-zA-Z][\w]{3,})$/) || text.match(/^https?:\/\/t\.me\/([a-zA-Z][\w]{3,})/);
    if (m) {
      const handle = '@' + m[1];
      setChat(handle);
      setState('await_date');
      setTimeout(() => push({
        kind: 'bot',
        text: `📋 Чат: ${handle}\n\nВыберите диапазон экспорта:`,
        kbd: 'date_choice',
      }), 400);
    } else {
      setTimeout(() => push({
        kind: 'bot',
        text: '❌ Неверный формат. Отправьте ссылку (https://t.me/channel) или @username.',
      }), 350);
    }
  };

  // ─── KEYBOARD ACTIONS ─────────────────────────────────────────────
  const enqueue = (rangeLabel, cache = false) => {
    setState('queued');
    const queueInfo = cache
      ? '\n\n⚡ Данные в кэше — результат будет быстро!'
      : '\n\n📋 Вы в очереди: позиция 2\nВпереди 1 задач(и)';
    const dateInfo = rangeLabel ? `\n📅 ${rangeLabel}` : '';
    setTimeout(() => push({
      kind: 'bot',
      text: `⏳ Задача принята!\n\nID: ${taskId}\nЧат: ${chat}${dateInfo}${queueInfo}`,
      kbd: 'cancel',
    }), 400);
  };

  const cancelTask = () => {
    push({ kind: 'bot', text: `✅ Экспорт ${taskId} отменён.` });
    setState('start');
  };

  const completeTask = () => {
    push({ kind: 'bot', text: `✅ Экспорт готов.`, attachment: 'output.txt' });
    setState('start');
  };

  // ─── KEYBOARD RENDERERS ───────────────────────────────────────────
  const dateChoiceKbd = (
    <InlineKeyboard rows={[
      [{ label: '📦 Весь чат', onClick: () => enqueue(null, true) }],
      [
        { label: '⏱ 24 часа', onClick: () => enqueue('От: ' + daysAgo(1) + ' — До: сегодня') },
        { label: '🗓 3 дня',  onClick: () => enqueue('От: ' + daysAgo(3) + ' — До: сегодня') },
      ],
      [
        { label: '🗓 7 дней',  onClick: () => enqueue('От: ' + daysAgo(7) + ' — До: сегодня') },
        { label: '🗓 30 дней', onClick: () => enqueue('От: ' + daysAgo(30) + ' — До: сегодня') },
      ],
      [{ label: '📅 Указать диапазон дат', onClick: () => enqueue('От: 01.01.2024 — До: 31.12.2024') }],
      [{ label: '◀️ Назад', onClick: reset }],
    ]}/>
  );

  const cancelKbd = (
    <InlineKeyboard rows={[
      [{ label: '❌ Отменить экспорт', onClick: cancelTask }],
      [{ label: '✓ Смоделировать завершение', onClick: completeTask }],
    ]}/>
  );

  // ─── SUGGESTIONS IN COMPOSER ──────────────────────────────────────
  let suggestion = '';
  if (state === 'start') suggestion = '@durov';
  else if (state === 'queued') suggestion = '/cancel';

  return (
    <TelegramPhone onReset={reset}>
      <div className="tg-chat" ref={scrollRef}>
        <DayDivider label="Сегодня" />
        {msgs.map((m, i) => {
          const prev = msgs[i - 1];
          const nextSameSide = msgs[i + 1]?.kind === m.kind;
          const tail = !nextSameSide;
          if (m.kind === 'user') {
            return <Bubble key={m.id} out tail={tail} meta={m.t}>{m.text}</Bubble>;
          }
          let kbd = null;
          if (m.kbd === 'date_choice' && state === 'await_date') kbd = dateChoiceKbd;
          if (m.kbd === 'cancel' && state === 'queued') kbd = cancelKbd;
          return (
            <Bubble key={m.id} tail={tail} meta={m.t} keyboard={kbd}>
              {renderRich(m.text)}
              {m.attachment && (
                <div style={{marginTop:8,padding:'8px 10px',background:'#f0f6fb',border:'1px solid #d9e4ed',borderRadius:8,display:'flex',alignItems:'center',gap:8,fontFamily:'var(--tg-mono)',fontSize:12}}>
                  <span style={{fontSize:20}}>📄</span>
                  <span>{m.attachment}</span>
                  <span style={{marginLeft:'auto',color:'var(--tg-muted)'}}>4.2 KB</span>
                </div>
              )}
            </Bubble>
          );
        })}
      </div>
      <Composer
        suggestion={suggestion}
        onSend={handleUserText}
        disabled={false}
      />
    </TelegramPhone>
  );
};

// Highlight @handles, t.me links, monospaced IDs
function renderRich(text) {
  const parts = [];
  let rest = text;
  const re = /(@[a-zA-Z][\w]{3,}|https?:\/\/t\.me\/\S+|ID: [a-f0-9]{6,})/g;
  let last = 0, m;
  let i = 0;
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    const token = m[0];
    if (token.startsWith('ID: ')) {
      parts.push(<React.Fragment key={i++}>ID: <code>{token.slice(4)}</code></React.Fragment>);
    } else {
      parts.push(<span key={i++} className="tg-link">{token}</span>);
    }
    last = m.index + token.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

function daysAgo(d) {
  const date = new Date(Date.now() - d * 86400000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(date.getDate())}.${pad(date.getMonth() + 1)}.${date.getFullYear()}`;
}

Object.assign(window, { BotFlow });
