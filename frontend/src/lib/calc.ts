/**
 * Works out a simple sum typed into an amount box, like "450+120*2" or "(1200-200)/4". Supports + − × ÷ and
 * brackets (× ÷ x * / all work). No eval: a tiny parser, so nothing else can run.
 * Returns null while the sum is incomplete or invalid (e.g. "450+" or "5/0").
 */
export function evaluate(input: string): number | null {
  const src = input.replace(/,/g, "").replace(/[×xX]/g, "*").replace(/÷/g, "/").replace(/[−–]/g, "-").replace(/\s+/g, "");
  if (!src) return null;
  let i = 0;
  const peek = () => src[i];

  function number(): number | null {
    const m = /^\d*\.?\d+|^\d+\.?/.exec(src.slice(i));
    if (!m) return null;
    i += m[0].length;
    return Number(m[0]);
  }
  function factor(): number | null {
    if (peek() === "-") { i++; const v = factor(); return v === null ? null : -v; }
    if (peek() === "+") { i++; return factor(); }
    if (peek() === "(") {
      i++;
      const v = expr();
      if (v === null || peek() !== ")") return null;
      i++;
      return v;
    }
    return number();
  }
  function term(): number | null {
    let v = factor();
    while (v !== null && (peek() === "*" || peek() === "/")) {
      const op = src[i++];
      const r = factor();
      if (r === null) return null;
      if (op === "/" && r === 0) return null;
      v = op === "*" ? v * r : v / r;
    }
    return v;
  }
  function expr(): number | null {
    let v = term();
    while (v !== null && (peek() === "+" || peek() === "-")) {
      const op = src[i++];
      const r = term();
      if (r === null) return null;
      v = op === "+" ? v + r : v - r;
    }
    return v;
  }

  const v = expr();
  if (v === null || i !== src.length || !Number.isFinite(v)) return null;
  return Math.round(v * 100) / 100;
}

/** True when the text is a sum to work out rather than a plain number. */
export function isExpression(input: string): boolean {
  return /\d\s*[-+*/×÷xX−–]\s*[\d(.]|\(/.test(input.replace(/,/g, "").replace(/^\s*[-−]/, ""));
}

/** A plain amount string for the form: "1240.5", no trailing zeros. */
export function amountString(n: number): string {
  return String(Math.round(n * 100) / 100);
}
