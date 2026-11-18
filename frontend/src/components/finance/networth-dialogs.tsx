"use client";

import { FileUp, RefreshCw, Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { api, ApiError } from "@/lib/api";
import { formatDate, inr, isoDay } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AllocationBucket, AssetClass, Holding, Liability, LiabilityType, ListedSecurity, MfScheme } from "@/types/api";
import { useInvalidateWealth } from "@/hooks/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";

const err = (e: unknown) => (e instanceof ApiError ? [e.message, ...e.details].join(". ") : "Something went wrong");
const num = (v: string) => v.replace(/[^0-9.]/g, "");

export const ASSET_CLASSES: { value: AssetClass; label: string; hint: string }[] = [
  { value: "MUTUAL_FUND", label: "Mutual fund", hint: "Value updates every evening from AMFI" },
  { value: "STOCK", label: "Stock", hint: "Prices update daily from NSE/BSE" },
  { value: "ETF", label: "ETF", hint: "NIFTYBEES, GOLDBEES… priced daily" },
  { value: "FIXED_DEPOSIT", label: "Fixed deposit", hint: "Grows with interest automatically" },
  { value: "PPF", label: "PPF", hint: "Enter the current balance" },
  { value: "EPF", label: "EPF / PF", hint: "Enter the balance from your passbook" },
  { value: "NPS", label: "NPS", hint: "Enter the current value" },
  { value: "GOLD", label: "Gold", hint: "Grams and today's rate" },
  { value: "SGB", label: "Sovereign Gold Bond", hint: "Units; priced daily from NSE" },
  { value: "RECURRING_DEPOSIT", label: "Recurring deposit", hint: "Enter the current balance" },
  { value: "BOND", label: "Bond", hint: "Units and price" },
  { value: "REAL_ESTATE", label: "Property", hint: "Home, land, plot" },
  { value: "VEHICLE", label: "Vehicle", hint: "Today's resale value" },
  { value: "CRYPTO", label: "Crypto", hint: "Units and price" },
  { value: "OTHER", label: "Something else", hint: "Any other asset" },
];

const MARKET: AssetClass[] = ["MUTUAL_FUND", "STOCK", "ETF", "GOLD", "SGB", "BOND", "CRYPTO"];
const FIXED: AssetClass[] = ["FIXED_DEPOSIT"];

export const BUCKET_LABEL: Record<AllocationBucket, string> = {
  EQUITY: "Equity", DEBT: "Debt", HYBRID: "Hybrid", GOLD: "Gold", REAL_ESTATE: "Real estate", CASH: "Cash & bank", OTHER: "Other",
};

function SchemeSearch({ onPick }: { onPick: (s: MfScheme) => void }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<MfScheme[] | null>(null);
  useEffect(() => {
    if (q.trim().length < 3) {
      setResults(null);
      return;
    }
    const id = setTimeout(() => {
      api.get<MfScheme[]>("/networth/mf-schemes", { q }).then(setResults).catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(id);
  }, [q]);
  return (
    <div>
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-3" />
        <Input className="pl-9" placeholder="Search fund name, e.g. parag parikh flexi direct" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search mutual funds" />
      </div>
      {results && (
        <ul className="mt-2 max-h-52 overflow-y-auto rounded-2xl border border-line">
          {results.length === 0 && (
            <li className="p-3 text-sm text-ink-2">No match. If the fund list is empty, use &ldquo;Refresh prices&rdquo; on the Net Worth page to load it from AMFI, or type the details below.</li>
          )}
          {results.map((s) => (
            <li key={s.schemeCode}>
              <button type="button" onClick={() => { onPick(s); setResults(null); setQ(s.name); }}
                className="flex w-full items-start justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2">
                <span><span className="block font-medium">{s.name}</span><span className="block text-xs text-ink-3">{s.category}</span></span>
                {s.nav && <span className="num shrink-0 text-right text-xs text-ink-2">NAV ₹{s.nav}<br />{s.navDate && formatDate(s.navDate, { day: "numeric", month: "short" })}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const LISTED: AssetClass[] = ["STOCK", "ETF", "SGB", "BOND"];

function SecuritySearch({ onPick, placeholder }: { onPick: (s: ListedSecurity) => void; placeholder: string }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<ListedSecurity[] | null>(null);
  useEffect(() => {
    if (q.trim().length < 2) {
      setResults(null);
      return;
    }
    const id = setTimeout(() => {
      api.get<ListedSecurity[]>("/networth/securities", { q }).then(setResults).catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(id);
  }, [q]);
  return (
    <div>
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-3" />
        <Input className="pl-9" placeholder={placeholder} value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search stocks and ETFs" />
      </div>
      {results && (
        <ul className="mt-2 max-h-52 overflow-y-auto rounded-2xl border border-line">
          {results.length === 0 && (
            <li className="p-3 text-sm text-ink-2">No match. If nothing shows up, use &ldquo;Refresh prices&rdquo; on the Net Worth page to load today&rsquo;s NSE/BSE list, or type the details below.</li>
          )}
          {results.map((s) => (
            <li key={s.isin}>
              <button type="button" onClick={() => { onPick(s); setResults(null); setQ(s.symbol); }}
                className="flex w-full items-start justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2">
                <span><span className="block font-medium">{s.symbol} <span className="text-xs font-normal text-ink-3">{s.exchange}</span></span><span className="block text-xs text-ink-3">{s.name}</span></span>
                <span className="num shrink-0 text-right text-xs text-ink-2">₹{s.close}<br />{formatDate(s.priceDate, { day: "numeric", month: "short" })}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function HoldingDialog({ holding, open, onOpenChange }: { holding?: Holding; open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const [cls, setCls] = useState<AssetClass | null>(holding?.assetClass ?? null);
  const [f, setF] = useState({
    name: holding?.name ?? "", schemeCode: holding?.schemeCode ?? "", isin: holding?.isin ?? "", symbol: holding?.symbol ?? "",
    platform: holding?.platform ?? "", units: holding?.units?.toString() ?? "", investedAmount: holding?.investedAmount?.toString() ?? "",
    currentPrice: holding?.currentPrice?.toString() ?? "", manualValue: holding?.manualValue?.toString() ?? "",
    interestRate: holding?.interestRate?.toString() ?? "", compoundingPerYear: holding?.compoundingPerYear?.toString() ?? "4",
    startDate: holding?.startDate ?? "", maturityDate: holding?.maturityDate ?? "", allocation: holding?.allocation ?? "",
  });
  const [picked, setPicked] = useState<MfScheme | null>(null);
  const [pickedStock, setPickedStock] = useState<ListedSecurity | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  const method = cls ? (MARKET.includes(cls) ? "MARKET_PRICE" : FIXED.includes(cls) ? "FIXED_RATE" : "MANUAL") : null;
  const effectiveMethod = holding?.valuationMethod ?? method;
  const preview = useMemo(() => {
    if (effectiveMethod === "MARKET_PRICE") {
      const price = Number(f.currentPrice || picked?.nav || pickedStock?.close || 0);
      return Number(f.units || 0) * price;
    }
    if (effectiveMethod === "MANUAL") return Number(f.manualValue || 0);
    return Number(f.investedAmount || 0);
  }, [effectiveMethod, f, picked, pickedStock]);

  async function save() {
    if (!cls) return;
    setBusy(true);
    const body: Record<string, unknown> = {
      assetClass: cls, name: f.name || picked?.name, symbol: f.symbol || null, isin: f.isin || null, platform: f.platform || null,
      schemeCode: f.schemeCode || null, valuationMethod: effectiveMethod, allocation: f.allocation || null,
      units: f.units || null, investedAmount: f.investedAmount || null, currentPrice: f.currentPrice || null, manualValue: f.manualValue || null,
      interestRate: f.interestRate || null, compoundingPerYear: effectiveMethod === "FIXED_RATE" ? Number(f.compoundingPerYear) : null,
      startDate: f.startDate || null, maturityDate: f.maturityDate || null, version: holding?.version,
    };
    try {
      if (holding) await api.put(`/networth/holdings/${holding.id}`, body);
      else await api.post("/networth/holdings", body);
      invalidate();
      toast.success(holding ? "Saved" : `Added ${body.name}`);
      onOpenChange(false);
    } catch (e) {
      toast.error(err(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={holding ? `Edit ${holding.name}` : cls ? `Add ${ASSET_CLASSES.find((a) => a.value === cls)?.label.toLowerCase()}` : "What do you want to add?"} wide>
        {!cls ? (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {ASSET_CLASSES.map((a) => (
              <button key={a.value} onClick={() => setCls(a.value)} className="rounded-2xl border border-line p-3 text-left hover:border-peacock-2/60 hover:bg-surface-2">
                <span className="block text-sm font-semibold">{a.label}</span>
                <span className="block text-xs text-ink-3">{a.hint}</span>
              </button>
            ))}
          </div>
        ) : (
          <div className="space-y-4">
            {cls === "MUTUAL_FUND" && !holding && (
              <Field label="Find your fund">
                <SchemeSearch onPick={(s) => { setPicked(s); set({ name: s.name, schemeCode: s.schemeCode, isin: s.isinGrowth ?? s.isinReinvest ?? "" }); }} />
              </Field>
            )}
            {cls && LISTED.includes(cls) && !holding && (
              <Field label={cls === "SGB" ? "Find your bond series" : "Find it on NSE/BSE"}>
                <SecuritySearch placeholder={cls === "SGB" ? "e.g. SGBJUN28 or gold bond" : cls === "ETF" ? "e.g. NIFTYBEES" : "Symbol or company, e.g. INFY or infosys"}
                  onPick={(s) => { setPickedStock(s); set({ name: s.name ?? s.symbol, symbol: s.symbol, isin: s.isin }); }} />
              </Field>
            )}
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Name" htmlFor="h-name">
                <Input id="h-name" value={f.name} onChange={(e) => set({ name: e.target.value })}
                  placeholder={cls === "STOCK" ? "INFY" : cls === "FIXED_DEPOSIT" ? "SBI FD 2 years" : cls === "REAL_ESTATE" ? "Flat in Pune" : ""} />
              </Field>
              <Field label="Held at (optional)" htmlFor="h-platform">
                <Input id="h-platform" value={f.platform} onChange={(e) => set({ platform: e.target.value })} placeholder="Zerodha, Groww, SBI…" />
              </Field>
              {effectiveMethod === "MARKET_PRICE" && (
                <>
                  <Field label={cls === "GOLD" ? "Grams" : cls === "MUTUAL_FUND" ? "Units" : "Quantity"} htmlFor="h-units">
                    <Input id="h-units" inputMode="decimal" className="num" value={f.units} onChange={(e) => set({ units: num(e.target.value) })} />
                  </Field>
                  <Field label={cls === "MUTUAL_FUND" ? "NAV (optional)" : cls === "GOLD" ? "Today's rate per gram" : LISTED.includes(cls) ? "Price (optional)" : "Current price"} htmlFor="h-price"
                    hint={cls === "MUTUAL_FUND" ? (picked?.nav ? `AMFI NAV ₹${picked.nav} will be used and updated every evening` : "Leave empty to use AMFI's NAV")
                      : LISTED.includes(cls) ? (pickedStock ? `${pickedStock.exchange} close ₹${pickedStock.close} will be used and refreshed every trading day` : "Leave empty to use the latest NSE/BSE close") : undefined}>
                    <Input id="h-price" inputMode="decimal" className="num" value={f.currentPrice} onChange={(e) => set({ currentPrice: num(e.target.value) })} />
                  </Field>
                  <Field label="Amount invested" htmlFor="h-invested" hint="Used to show your gain">
                    <Input id="h-invested" inputMode="decimal" className="num" value={f.investedAmount} onChange={(e) => set({ investedAmount: num(e.target.value) })} />
                  </Field>
                  {LISTED.includes(cls) && (
                    <Field label="Symbol (optional)" htmlFor="h-symbol"><Input id="h-symbol" value={f.symbol} onChange={(e) => set({ symbol: e.target.value.toUpperCase() })} /></Field>
                  )}
                </>
              )}
              {effectiveMethod === "MANUAL" && (
                <>
                  <Field label="Current value" htmlFor="h-value" hint="Update it whenever you check your passbook or statement">
                    <Input id="h-value" inputMode="decimal" className="num" value={f.manualValue} onChange={(e) => set({ manualValue: num(e.target.value) })} />
                  </Field>
                  <Field label="Amount invested / bought for (optional)" htmlFor="h-cost">
                    <Input id="h-cost" inputMode="decimal" className="num" value={f.investedAmount} onChange={(e) => set({ investedAmount: num(e.target.value) })} />
                  </Field>
                </>
              )}
              {effectiveMethod === "FIXED_RATE" && (
                <>
                  <Field label="Amount deposited" htmlFor="h-principal">
                    <Input id="h-principal" inputMode="decimal" className="num" value={f.investedAmount} onChange={(e) => set({ investedAmount: num(e.target.value) })} />
                  </Field>
                  <Field label="Interest rate (% a year)" htmlFor="h-rate">
                    <Input id="h-rate" inputMode="decimal" className="num" value={f.interestRate} onChange={(e) => set({ interestRate: num(e.target.value) })} />
                  </Field>
                  <Field label="Start date" htmlFor="h-start"><Input id="h-start" type="date" value={f.startDate} max={isoDay(new Date())} onChange={(e) => set({ startDate: e.target.value })} /></Field>
                  <Field label="Maturity date" htmlFor="h-maturity"><Input id="h-maturity" type="date" value={f.maturityDate} onChange={(e) => set({ maturityDate: e.target.value })} /></Field>
                  <Field label="Interest compounds" htmlFor="h-comp">
                    <Select id="h-comp" value={f.compoundingPerYear} onChange={(e) => set({ compoundingPerYear: e.target.value })}>
                      <option value="4">Quarterly (most bank FDs)</option>
                      <option value="12">Monthly</option>
                      <option value="2">Half-yearly</option>
                      <option value="1">Yearly</option>
                    </Select>
                  </Field>
                </>
              )}
              <Field label="Counts as" htmlFor="h-alloc" hint="Used for your asset allocation">
                <Select id="h-alloc" value={f.allocation} onChange={(e) => set({ allocation: e.target.value as AllocationBucket })}>
                  <option value="">Automatic</option>
                  {(Object.keys(BUCKET_LABEL) as AllocationBucket[]).filter((b) => b !== "CASH").map((b) => <option key={b} value={b}>{BUCKET_LABEL[b]}</option>)}
                </Select>
              </Field>
            </div>
            <div className="flex items-center justify-between rounded-2xl bg-surface-2 px-4 py-3 text-sm">
              <span className="text-ink-2">{effectiveMethod === "FIXED_RATE" ? "Grows from" : "Worth about"}</span>
              <span className="num text-lg font-semibold">{inr(preview)}</span>
            </div>
            <div className="flex gap-2">
              {!holding && <Button variant="ghost" onClick={() => setCls(null)}>Back</Button>}
              <Button className="flex-1" disabled={busy || !(f.name || picked)} onClick={save}>{holding ? "Save changes" : "Add to net worth"}</Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Funds and exchange-listed holdings can be priced from official data (AMFI NAV, NSE/BSE close). */
export function canFetchPrice(h: Holding) {
  return h.valuationMethod === "MARKET_PRICE" && (h.assetClass === "MUTUAL_FUND" || LISTED.includes(h.assetClass));
}

type LatestPrice = { holding: Holding; previousPrice: number | null; price: number; priceDate: string; source: string; message: string };

export function UpdateValueDialog({ holding, open, onOpenChange }: { holding: Holding; open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const manual = holding.valuationMethod === "MANUAL";
  const fetchable = canFetchPrice(holding);
  const fund = holding.assetClass === "MUTUAL_FUND";
  const [typing, setTyping] = useState(!fetchable);
  const [v, setV] = useState(String(manual ? holding.manualValue ?? "" : holding.currentPrice ?? ""));
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<LatestPrice | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function fetchLatest() {
    setBusy(true);
    setProblem(null);
    try {
      const r = await api.post<LatestPrice>(`/networth/holdings/${holding.id}/latest-price`);
      setResult(r);
      invalidate();
    } catch (e) {
      setProblem(err(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveTyped() {
    try {
      await api.post(`/networth/holdings/${holding.id}/price`, { price: v });
      invalidate();
      toast.success("Updated");
      onOpenChange(false);
    } catch (e) {
      toast.error(err(e));
    }
  }

  const priceWord = fund ? "NAV" : "price";
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={holding.name}
        description={fetchable ? `Value now: ${inr(holding.value)}${holding.priceAsOf ? `, priced ${formatDate(holding.priceAsOf, { day: "numeric", month: "short" })}` : ""}` : manual ? "Enter today's value." : `Enter today's price per ${holding.assetClass === "GOLD" ? "gram" : "unit"}.`}>
        {fetchable && (
          <div className="space-y-3">
            {result ? (
              <div className="rounded-2xl bg-surface-2 p-4">
                <p className="text-sm text-ink-2">{result.source} ₹{result.price} for {formatDate(result.priceDate, { day: "numeric", month: "short", year: "numeric" })}</p>
                <p className="num mt-1 text-2xl font-semibold">{inr(result.holding.value)}</p>
                {result.previousPrice != null && Number(result.previousPrice) !== Number(result.price) && (
                  <p className="mt-1 text-xs text-ink-3">{priceWord} was ₹{result.previousPrice}; now ₹{result.price}</p>
                )}
                {result.previousPrice != null && Number(result.previousPrice) === Number(result.price) && (
                  <p className="mt-1 text-xs text-ink-3">Already up to date. {fund ? "Funds publish one NAV a day, in the evening." : "Prices update after each trading day closes."}</p>
                )}
                <Button className="mt-4 w-full" onClick={() => onOpenChange(false)}>Done</Button>
              </div>
            ) : (
              <>
                <Button className="w-full" disabled={busy} onClick={fetchLatest}>
                  <RefreshCw className={busy ? "animate-spin" : undefined} /> {busy ? "Checking…" : `Get latest ${priceWord}`}
                </Button>
                <p className="text-xs text-ink-3">
                  {fund ? "From AMFI. Funds publish one NAV per working day, in the evening." : "From NSE/BSE. The closing price after each trading day."} This also happens automatically every evening.
                </p>
              </>
            )}
            {problem && <p role="alert" className="rounded-2xl bg-out/10 p-3 text-sm text-out">{problem}</p>}
            {!typing && !result && (
              <button type="button" className="text-sm font-medium text-peacock hover:underline" onClick={() => setTyping(true)}>Enter a price yourself</button>
            )}
          </div>
        )}
        {typing && !result && (
          <div className={fetchable ? "mt-4 border-t border-line pt-4" : undefined}>
            <Input inputMode="decimal" className="num text-lg" value={v} onChange={(e) => setV(num(e.target.value))} aria-label={manual ? "Value" : "Price per unit"} autoFocus={!fetchable} />
            {!manual && holding.units != null && <p className="mt-2 text-sm text-ink-2">Worth {inr(Number(v || 0) * holding.units)}</p>}
            {fetchable && <p className="mt-2 text-xs text-ink-3">A price you type is kept until the next official {priceWord} comes out.</p>}
            <Button className="mt-4 w-full" variant={fetchable ? "outline" : undefined} disabled={!v} onClick={saveTyped}>Save this price</Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

const LOAN_TYPES: { value: LiabilityType; label: string }[] = [
  { value: "HOME_LOAN", label: "Home loan" }, { value: "CAR_LOAN", label: "Car loan" }, { value: "PERSONAL_LOAN", label: "Personal loan" },
  { value: "EDUCATION_LOAN", label: "Education loan" }, { value: "GOLD_LOAN", label: "Gold loan" },
  { value: "LOAN_AGAINST_SECURITIES", label: "Loan against shares/MF" }, { value: "BORROWED_FROM_PERSON", label: "Borrowed from someone" },
  { value: "OTHER", label: "Other" },
];

export function LiabilityDialog({ liability, open, onOpenChange }: { liability?: Liability; open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const [f, setF] = useState({
    type: liability?.type ?? ("HOME_LOAN" as LiabilityType), name: liability?.name ?? "", lender: liability?.lender ?? "",
    principal: liability?.principal?.toString() ?? "", outstanding: liability?.outstanding?.toString() ?? "", interestRate: liability?.interestRate?.toString() ?? "",
    emi: liability?.emi?.toString() ?? "", endDate: liability?.endDate ?? "",
  });
  const set = (p: Partial<typeof f>) => setF({ ...f, ...p });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={liability ? `Edit ${liability.name}` : "Add a loan"} description="Credit card dues come from your card accounts automatically." wide>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Type" htmlFor="l-type">
            <Select id="l-type" value={f.type} onChange={(e) => set({ type: e.target.value as LiabilityType })}>
              {LOAN_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </Select>
          </Field>
          <Field label="Name" htmlFor="l-name"><Input id="l-name" value={f.name} onChange={(e) => set({ name: e.target.value })} placeholder="SBI home loan" /></Field>
          <Field label="Still owed today" htmlFor="l-out"><Input id="l-out" inputMode="decimal" className="num" value={f.outstanding} onChange={(e) => set({ outstanding: num(e.target.value) })} /></Field>
          <Field label="Original loan amount" htmlFor="l-principal"><Input id="l-principal" inputMode="decimal" className="num" value={f.principal} onChange={(e) => set({ principal: num(e.target.value) })} /></Field>
          <Field label="Lender" htmlFor="l-lender"><Input id="l-lender" value={f.lender} onChange={(e) => set({ lender: e.target.value })} /></Field>
          <Field label="Interest rate (%)" htmlFor="l-rate"><Input id="l-rate" inputMode="decimal" className="num" value={f.interestRate} onChange={(e) => set({ interestRate: num(e.target.value) })} /></Field>
          <Field label="EMI" htmlFor="l-emi"><Input id="l-emi" inputMode="decimal" className="num" value={f.emi} onChange={(e) => set({ emi: num(e.target.value) })} /></Field>
          <Field label="Last EMI date" htmlFor="l-end"><Input id="l-end" type="date" value={f.endDate} onChange={(e) => set({ endDate: e.target.value })} /></Field>
        </div>
        <Button className="mt-5 w-full" disabled={!f.name || !f.outstanding} onClick={async () => {
          const body = { ...f, principal: f.principal || null, interestRate: f.interestRate || null, emi: f.emi || null, endDate: f.endDate || null,
            lender: f.lender || null, version: liability?.version };
          try {
            if (liability) await api.put(`/networth/liabilities/${liability.id}`, body);
            else await api.post("/networth/liabilities", body);
            invalidate();
            toast.success(liability ? "Saved" : "Loan added");
            onOpenChange(false);
          } catch (e) {
            toast.error(err(e));
          }
        }}>{liability ? "Save changes" : "Add loan"}</Button>
      </DialogContent>
    </Dialog>
  );
}

const PLATFORMS = ["Zerodha", "Groww", "Coin", "Kuvera", "INDmoney", "Upstox", "Angel One", "ICICI Direct", "HDFC Securities", "Other"];

export function ImportHoldingsDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const invalidate = useInvalidateWealth();
  const [platform, setPlatform] = useState("Zerodha");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ rows: number; created: number; updated: number; linkedToAmfi: number; errors: string[] } | null>(null);
  const ref = useRef<HTMLInputElement>(null);
  async function upload(file?: File) {
    if (!file) return;
    setBusy(true);
    const fd = new FormData();
    fd.append("file", file);
    try {
      const r = await api.post<typeof result>(`/networth/holdings/import?platform=${encodeURIComponent(platform)}`, fd);
      setResult(r);
      invalidate();
      toast.success("Holdings imported");
    } catch (e) {
      toast.error(err(e));
    } finally {
      setBusy(false);
      if (ref.current) ref.current.value = "";
    }
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Import holdings" description="Download your holdings as CSV from your broker or fund app and upload it. Re-import each month: existing holdings are updated, not duplicated." wide>
        <ul className="mb-4 space-y-1 text-[13px] text-ink-2">
          <li><strong className="text-ink">Zerodha:</strong> Kite → Holdings → Download, or Console → Portfolio → Holdings → Download (Excel works; both the Equity and Mutual Funds sheets are read).</li>
          <li><strong className="text-ink">Groww:</strong> Stocks or Mutual Funds → Holdings → Download statement (Excel or CSV).</li>
          <li><strong className="text-ink">Others:</strong> any Excel or CSV file with a name/ISIN column and quantity/units, plus price, value or average cost.</li>
        </ul>
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-56"><Field label="From" htmlFor="imp-platform">
            <Select id="imp-platform" value={platform} onChange={(e) => setPlatform(e.target.value)}>
              {PLATFORMS.map((p) => <option key={p}>{p}</option>)}
            </Select>
          </Field></div>
          <input ref={ref} id="holdings-file" type="file" accept=".csv,.txt,.xlsx,.xls,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel" className="sr-only" onChange={(e) => upload(e.target.files?.[0])} />
          <Button asChild disabled={busy}><label htmlFor="holdings-file" className="cursor-pointer"><FileUp /> {busy ? "Importing…" : "Choose Excel or CSV file"}</label></Button>
        </div>
        {result && (
          <div className="mt-4 rounded-2xl bg-surface-2 p-4 text-sm">
            <p><span className="num font-semibold">{result.created}</span> added, <span className="num font-semibold">{result.updated}</span> updated
              {result.linkedToAmfi > 0 && <>, <span className="num font-semibold">{result.linkedToAmfi}</span> funds linked to AMFI for nightly NAVs</>}.</p>
            {result.errors.length > 0 && <ul className="mt-2 list-disc pl-5 text-xs text-out">{result.errors.slice(0, 10).map((e) => <li key={e}>{e}</li>)}</ul>}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

export function SourceBadge({ source, estimated }: { source: string; estimated: boolean }) {
  return (
    <span className={cn("inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium",
      estimated ? "bg-marigold-soft text-marigold-ink" : source.startsWith("AMFI") ? "bg-in-soft text-in" : "bg-surface-2 text-ink-2")}>
      {source}
    </span>
  );
}
