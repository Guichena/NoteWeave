/** NoteWeave 标志：两条交织的线，代表“来源”与“结论”被编织在一起。 */
export function BrandMark({ className = "", size = 28 }: { className?: string; size?: number }) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="32" height="32" rx="9" className="brand-mark-bg" />
      <path
        d="M7 11.5c4.5 0 4.5 9 9 9s4.5-9 9-9"
        className="brand-mark-thread is-a"
        fill="none"
        strokeWidth="2.6"
        strokeLinecap="round"
      />
      <path
        d="M7 20.5c4.5 0 4.5-9 9-9s4.5 9 9 9"
        className="brand-mark-thread is-b"
        fill="none"
        strokeWidth="2.6"
        strokeLinecap="round"
      />
    </svg>
  );
}
