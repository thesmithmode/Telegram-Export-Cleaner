// Bubble — incoming bot message or outgoing user message
const Bubble = ({ out = false, tail = true, children, meta, keyboard }) => {
  const cls = [
    'tg-bubble',
    out ? 'out' : '',
    tail ? (out ? 'tail-out' : 'tail-in') : '',
  ].join(' ').trim();
  return (
    <>
      <div className={`tg-bubble-row ${out ? 'out' : ''}`}>
        <div className={cls}>
          {children}
          {meta && (
            <span className="tg-bubble__meta">
              {meta}
              {out && <span className="check">✓✓</span>}
            </span>
          )}
        </div>
      </div>
      {keyboard && (
        <div className={`tg-bubble-row ${out ? 'out' : ''}`}>
          {keyboard}
        </div>
      )}
    </>
  );
};

const TypingBubble = () => (
  <div className="tg-bubble-row">
    <div className="tg-bubble tail-in">
      <div className="tg-typing"><span/><span/><span/></div>
    </div>
  </div>
);

Object.assign(window, { Bubble, TypingBubble });
