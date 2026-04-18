// Inline keyboard — rows of tappable buttons attached under a bot message.
// Accepts a 2D array: rows of { label, onClick }.
const InlineKeyboard = ({ rows }) => (
  <div className="tg-kbd">
    {rows.map((row, i) => (
      <div className="tg-kbd-row" key={i}>
        {row.map((btn, j) => (
          <button key={j} onClick={btn.onClick}>{btn.label}</button>
        ))}
      </div>
    ))}
  </div>
);

// Composer (non-functional — tap to send next hard-coded reply)
const Composer = ({ placeholder = "Message", suggestion, onSend, disabled }) => {
  const [val, setVal] = React.useState('');
  const submit = () => {
    const text = val.trim() || suggestion;
    if (!text || disabled) return;
    onSend(text);
    setVal('');
  };
  return (
    <div className="tg-composer">
      <span className="tg-composer__icon">📎</span>
      <input
        className="tg-composer__input"
        value={val}
        placeholder={suggestion ? `${suggestion}` : placeholder}
        onChange={(e) => setVal(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') submit(); }}
        disabled={disabled}
      />
      <button className="tg-send-btn" onClick={submit} disabled={disabled}>➤</button>
    </div>
  );
};

Object.assign(window, { InlineKeyboard, Composer });
