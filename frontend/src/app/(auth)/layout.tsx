export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.1fr_1fr]">
      <div className="relative hidden overflow-hidden bg-peacock p-12 text-white lg:flex lg:flex-col lg:justify-between dark:bg-[#0e3b3d]">
        <div className="flex items-center gap-2.5">
          <svg viewBox="0 0 32 32" className="size-9" aria-hidden>
            <rect width="32" height="32" rx="10" fill="#fff" fillOpacity=".14" />
            <path d="M9 22.5 15.2 9h1.6L23 22.5h-3l-1.3-3h-5.4l-1.3 3H9Zm5.3-5.6h3.4L16 12.6l-1.7 4.3Z" fill="#fff" />
            <circle cx="23.5" cy="9.5" r="2.5" fill="#e8a317" />
          </svg>
          <span className="font-display text-2xl font-semibold">Arth</span>
        </div>
        <div className="max-w-md">
          <p className="font-display text-[40px] font-semibold leading-[1.1] tracking-tight">Every rupee, accounted for.</p>
          <p className="mt-4 text-base leading-relaxed text-white/80">
            Arth reads your bank alerts and statements, asks you where each payment belongs, and learns your answers. PhonePe, Google Pay
            and card spends all land in one place.
          </p>
        </div>
        <ul className="space-y-2 text-sm text-white/75">
          <li>We never ask for your UPI PIN, bank password or OTPs.</li>
          <li>Nothing is categorised without your approval unless you turn it on.</li>
        </ul>
        <svg className="pointer-events-none absolute -right-24 -top-24 size-[420px] opacity-[0.07]" viewBox="0 0 200 200" aria-hidden>
          <circle cx="100" cy="100" r="98" fill="none" stroke="#fff" strokeWidth="4" />
          <circle cx="100" cy="100" r="70" fill="none" stroke="#fff" strokeWidth="4" />
          <circle cx="100" cy="100" r="42" fill="none" stroke="#fff" strokeWidth="4" />
        </svg>
      </div>
      <div className="flex items-center justify-center p-6 sm:p-10">{children}</div>
    </div>
  );
}
