// Phone frame + Telegram chat shell
const TelegramPhone = ({ children, onReset }) => {
  const [now, setNow] = React.useState(() => new Date());
  React.useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(t);
  }, []);
  const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  return (
    <div className="tg-phone">
      <div className="tg-status">
        <span>{time}</span>
        <span>●●●● 5G · 87%</span>
      </div>
      <ChatHeader onReset={onReset} />
      {children}
    </div>
  );
};

const ChatHeader = ({ onReset }) => (
  <div className="tg-header">
    <span className="tg-header__back">‹</span>
    <div className="tg-header__avatar">EC</div>
    <div className="tg-header__meta">
      <span className="tg-header__name">Export Cleaner</span>
      <span className="tg-header__status">bot</span>
    </div>
    <span className="tg-header__more">⋮</span>
    <button className="tg-reset" onClick={onReset}>↺ reset</button>
  </div>
);

const DayDivider = ({ label }) => <div className="tg-day">{label}</div>;

Object.assign(window, { TelegramPhone, ChatHeader, DayDivider });
