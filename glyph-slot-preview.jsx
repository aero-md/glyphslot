import { useEffect, useRef, useState } from "react";

/* ---------- Glyph Matrix : constantes ---------- */
const SIZE = 25;          // matrice 25×25
const CTR = 12;           // centre
const RADIUS = 12.5;      // masque circulaire (~489 LEDs)
const COLS = [1, 9, 17];  // x de départ des 3 rouleaux (7 px chacun)
const PAY_TOP = 9, PAY_BOT = 15; // fenêtre payline (7 lignes)
const SYM_H = 9;          // 7 px symbole + 2 px de blanc
const STRIP_LEN = 45;     // 5 symboles × 9
const STOPS = [2.6, 3.8, 4.9]; // arrêts des rouleaux (s) — total ≈ 5 s
const DECEL = 1.3;        // durée de décélération (s)
const V = 34;             // vitesse plein régime (lignes/s)
const T_PULL = 0.45;      // recul lent (ressort qu'on arme)
const T_LAUNCH = 0.75;    // fin de la détente → vitesse V atteinte
const PULL = 5;           // amplitude du recul (lignes, sens inverse)

/* ---------- Fond : gris très sombre bleuté + trame de points, façon Nothing ----------
   SVG plutôt que radial-gradient : le point reste un carré de 2 × 2 px net,
   sans antialiasing, quel que soit le zoom. */
const BG = "#0C0D10";
const DOT_GAP = 132; // écart entre points, en px
const DOTS =
  'url("data:image/svg+xml,' +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${DOT_GAP}" height="${DOT_GAP}">` +
      `<rect width="2" height="2" fill="#9AA3C8" fill-opacity=".22"/></svg>`
  ) +
  '")';

/* ---------- Rendu LED : une cellule = un nombre entier de pixels écran ----------
   Si le canvas est redimensionné par le navigateur avec un ratio fractionnaire,
   une colonne sur n gagne un pixel et la trame devient irrégulière. On calcule
   donc la grille à partir du devicePixelRatio et on cale la taille du téléphone
   dessus, plutôt que d'étirer un canvas de taille fixe.
   Exact pour dpr 1 / 1.5 / 2 / 3 ; approché (mais bien meilleur qu'un downscale
   libre) pour les dpr en quarts. */
const DPR = (typeof window !== "undefined" && window.devicePixelRatio) || 1;
const CELL = Math.max(3, Math.round(6 * DPR)); // px de canvas par LED
const LED = Math.max(2, Math.round((CELL * 2) / 3)); // côté du carré allumé
const CANVAS = SIZE * CELL;   // résolution du canvas
const MX_PX = SIZE * 6;       // diamètre rendu de la matrice, en px CSS (150)

/* Sprites 7×7 — '.'=éteint, '1'=faible, '2'=plein */
const SPR = {
  seven: [
    "2222222",
    "......2",
    ".....2.",
    "....2..",
    "...2...",
    "..2....",
    "..2....",
  ],
  cherry: [
    "...1...",
    "..1.1..",
    ".1...1.",
    ".2...2.",
    "222.222",
    "222.222",
    ".2...2.",
  ],
  bar: [
    "2222222",
    "2222222",
    ".......",
    "2222222",
    "2222222",
    ".......",
    "2222222",
  ],
  diamond: [
    "...2...",
    "..222..",
    ".22222.",
    "2222222",
    ".22222.",
    "..222..",
    "...2...",
  ],
  bell: [
    "...2...",
    "..222..",
    ".22222.",
    ".22222.",
    ".22222.",
    "2222222",
    "...2...",
  ],
};
const STRIP = ["seven", "cherry", "bar", "diamond", "bell"];
const LVL = { ".": 0, "1": 0.45, "2": 1 };

/* Police 5×7 pour le bandeau JACKPOT */
const JK = {
  J: ["22222", "...2.", "...2.", "...2.", "2..2.", "2..2.", ".22.."],
  A: [".222.", "2...2", "2...2", "22222", "2...2", "2...2", "2...2"],
  C: [".2222", "2....", "2....", "2....", "2....", "2....", ".2222"],
  K: ["2...2", "2..2.", "2.2..", "22...", "2.2..", "2..2.", "2...2"],
  P: ["2222.", "2...2", "2...2", "2222.", "2....", "2....", "2...."],
  O: [".222.", "2...2", "2...2", "2...2", "2...2", "2...2", ".222."],
  T: ["22222", "..2..", "..2..", "..2..", "..2..", "..2..", "..2.."],
};
const BANNER = Array.from({ length: 7 }, (_, r) =>
  "JACKPOT".split("").map((c) => JK[c][r]).join(".")
);
const BANNER_W = BANNER[0].length; // 41 colonnes

const mod = (a, n) => ((a % n) + n) % n;
/* Ordre des symboles par rouleau : le rouleau i avance de i+1 (mod 5) —
   les voisins d'un symbole aligné diffèrent donc d'un rouleau à l'autre */
const ORDER = [0, 1, 2].map((i) =>
  Array.from({ length: 5 }, (_, j) => (j * (i + 1)) % 5)
);
const SLOT = ORDER.map((ord) => {
  const inv = [];
  ord.forEach((k, slot) => (inv[k] = slot));
  return inv;
});
const targetOffset = (reel, k) => mod(-SYM_H * SLOT[reel][k], STRIP_LEN);

/* Hermite : tangentes contrôlées aux deux extrémités */
function herm(u, p0, p1, m0, m1 = 0) {
  const u2 = u * u, u3 = u2 * u;
  return (
    p0 * (2 * u3 - 3 * u2 + 1) +
    m0 * (u3 - 2 * u2 + u) +
    p1 * (-2 * u3 + 3 * u2) +
    m1 * (u3 - u2)
  );
}

function makePlan(reel, off0, k, tStop) {
  const t1 = tStop - DECEL;
  const o1 = off0 + V * t1;
  const tm = targetOffset(reel, k);
  const d = 30 + mod(tm - (o1 + 30), STRIP_LEN); // distance de décel ∈ [30,75)
  return { off0, t1, tStop, o1, oF: o1 + d };
}
function offsetAt(p, t) {
  if (t <= 0) return p.off0;
  /* effet ressort : recul lent à l'envers… */
  if (t < T_PULL) return p.off0 - PULL * Math.sin((t / T_PULL) * Math.PI * 0.5);
  /* …puis détente violente qui rejoint la trajectoire linéaire à T_LAUNCH */
  if (t < T_LAUNCH) {
    const u = (t - T_PULL) / (T_LAUNCH - T_PULL);
    return herm(u, p.off0 - PULL, p.off0 + V * T_LAUNCH, 0, V * (T_LAUNCH - T_PULL));
  }
  if (t < p.t1) return p.off0 + V * t;
  if (t < p.tStop) return herm((t - p.t1) / DECEL, p.o1, p.oF, V * DECEL);
  return p.oF;
}

/* ---------- Dos du Phone (3) : géométrie de la photo ----------
   docs/phone3-back.webp = photo rognée au corps du téléphone puis aux 62 %
   supérieurs (704 × 913 px) — les 40 % du bas ne sont pas rendus.
   Positions relevées sur la photo, en % de ce cadre (d = % de la largeur). */
const PH_W = 704, PH_H = 913;
const MX = { x: 79.53, y: 15.36, d: 26.04 }; // disque de la Glyph Matrix
const BT = { x: 84.53, y: 74.82, d: 15.86 }; // bouton Glyph
/* largeur du téléphone déduite du diamètre voulu pour la matrice, pour que
   MX.d % de cette largeur retombe exactement sur MX_PX (576 px) */
const PHONE_W = `min(${Math.round(MX_PX / (MX.d / 100))}px, 100%)`;
const YEL = "255,229,0"; // jaune Nothing, en RGB pour moduler l'alpha
/* surchargeable (window.GLYPH_PHONE_SRC) pour les pages qui inlinent la photo */
const PHONE_SRC =
  (typeof window !== "undefined" && window.GLYPH_PHONE_SRC) || "phone3-back.webp";

/* masque circulaire pré-calculé */
const MASK = [];
for (let y = 0; y < SIZE; y++)
  for (let x = 0; x < SIZE; x++)
    if (Math.hypot(x - CTR, y - CTR) <= RADIUS) MASK.push([x, y]);

export default function GlyphSlot() {
  const canvasRef = useRef(null);
  const barRef = useRef(null);
  const pressT = useRef(null);
  const hintT = useRef(null);
  const down = useRef(false);   // un appui est en cours
  const fired = useRef(false);  // …et il a duré assez pour lancer
  const [status, setStatus] = useState("Appui long sur le bouton Glyph pour lancer");
  const [busy, setBusy] = useState(false);
  const [pressed, setPressed] = useState(false);
  const [hint, setHint] = useState(false);

  const A = useRef({
    mode: "idle",           // idle | spin | result
    offsets: null,          // offsets au repos
    plans: null,
    targets: null,
    t0: 0,
    resultStart: 0,
    resultType: null,       // lose | win | jackpot
    parts: null,
    announced: -1,
  });

  /* init : symboles aléatoires non identiques */
  if (!A.current.offsets) {
    let s;
    do {
      s = [0, 0, 0].map(() => Math.floor(Math.random() * 5));
    } while (s[0] === s[1] && s[1] === s[2]);
    A.current.offsets = s.map((k, i) => targetOffset(i, k));
  }

  const spin = (force) => {
    const a = A.current;
    if (a.mode !== "idle") return;
    let t;
    if (force === "jackpot") t = [0, 0, 0];
    else if (force === "win") {
      const k = 1 + Math.floor(Math.random() * 4);
      t = [k, k, k];
    } else {
      const r = Math.random();
      if (r < 0.05) t = [0, 0, 0];
      else if (r < 0.2) {
        const k = 1 + Math.floor(Math.random() * 4);
        t = [k, k, k];
      } else {
        do {
          t = [0, 0, 0].map(() => Math.floor(Math.random() * 5));
        } while (t[0] === t[1] && t[1] === t[2]);
      }
    }
    a.targets = t;
    a.plans = a.offsets.map((o, i) => makePlan(i, o, t[i], STOPS[i]));
    a.t0 = performance.now() / 1000;
    a.mode = "spin";
    a.announced = -1;
    setBusy(true);
    setStatus("Lancement — les rouleaux défilent…");
  };

  useEffect(() => {
    const cv = canvasRef.current;
    const ctx = cv.getContext("2d");
    let raf;

    const draw = () => {
      const a = A.current;
      const now = performance.now() / 1000;
      const grid = new Float32Array(SIZE * SIZE);

      /* --- offsets courants --- */
      let offs;
      if (a.mode === "spin") {
        const t = now - a.t0;
        offs = a.plans.map((p) => offsetAt(p, t));
        const stopped = STOPS.filter((s) => t >= s).length;
        if (stopped !== a.announced && stopped > 0 && stopped < 3) {
          a.announced = stopped;
          setStatus(`Rouleau ${stopped} arrêté…`);
        }
        if (t >= STOPS[2] + 0.15) {
          a.mode = "result";
          a.resultStart = now;
          const [x, y, z] = a.targets;
          a.resultType = x === y && y === z ? (x === 0 ? "jackpot" : "win") : "lose";
          a.offsets = a.targets.map((k, i) => targetOffset(i, k));
          if (a.resultType === "jackpot") {
            a.fx = {
              bursts: Array.from({ length: 7 }, (_, i) => ({
                t0: 3.9 + i * 0.22 + Math.random() * 0.12,
                cx: CTR + (Math.random() * 2 - 1) * 6,
                cy: CTR + (Math.random() * 2 - 1) * 6,
                parts: Array.from({ length: 12 }, () => ({
                  ang: Math.random() * Math.PI * 2,
                  v: 4 + Math.random() * 5,
                  life: 0.7 + Math.random() * 0.4,
                })),
              })),
              twinkles: Array.from({ length: 36 }, () => {
                const [tx, ty] = MASK[Math.floor(Math.random() * MASK.length)];
                return { x: tx, y: ty, ph: Math.random() * 6.28, sp: 5 + Math.random() * 4 };
              }),
            };
            setStatus("JACKPOT 777 — gros effet de victoire");
          } else if (a.resultType === "win") {
            setStatus("3 symboles identiques — effet de victoire");
          } else setStatus("Pas de combinaison. Relance !");
        }
        if (barRef.current)
          barRef.current.style.width = Math.min(t / 5, 1) * 100 + "%";
      } else {
        offs = a.offsets;
        if (a.mode === "idle" && barRef.current) barRef.current.style.width = "0%";
      }

      /* --- fin des effets de résultat --- */
      if (a.mode === "result") {
        const dur = a.resultType === "jackpot" ? 7.5 : a.resultType === "win" ? 2.8 : 1;
        if (now - a.resultStart > dur) {
          a.mode = "idle";
          setBusy(false);
          setStatus("Appui long sur le bouton Glyph pour lancer");
        }
      }

      /* --- multiplicateur d'effet sur la payline --- */
      let payMult = 1;
      if (a.mode === "result" && a.resultType !== "lose") {
        const te = now - a.resultStart;
        payMult = 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(te * Math.PI * 6));
      }

      /* --- rouleaux --- */
      for (let i = 0; i < 3; i++) {
        const off = Math.round(offs[i]);
        for (let wy = 1; wy < SIZE - 1; wy++) {
          const stripRow = mod(wy - PAY_TOP - off, STRIP_LEN);
          const sym = STRIP[ORDER[i][Math.floor(stripRow / SYM_H)]];
          const r = stripRow % SYM_H;
          if (r >= 7) continue;
          const inPay = wy >= PAY_TOP && wy <= PAY_BOT;
          const dimRow = inPay ? 1 : 0.2;
          const row = SPR[sym][r];
          for (let x = 0; x < 7; x++) {
            const b = LVL[row[x]];
            if (!b) continue;
            grid[wy * SIZE + COLS[i] + x] = b * dimRow * (inPay ? payMult : 1);
          }
        }
      }

      /* --- effets --- */
      if (a.mode === "result") {
        const te = now - a.resultStart;
        if (a.resultType === "win") {
          const ring = 0.5 * (0.5 + 0.5 * Math.sin(te * Math.PI * 6));
          for (const [x, y] of MASK) {
            const d = Math.hypot(x - CTR, y - CTR);
            if (d > 11.3) grid[y * SIZE + x] = Math.max(grid[y * SIZE + x], ring);
          }
        }
        if (a.resultType === "jackpot") {
          /* Phase 1 (0–0.6 s) : triple strobe */
          for (const t0 of [0, 0.2, 0.4]) {
            const age = te - t0;
            if (age >= 0 && age < 0.14) {
              const f = 0.95 * (1 - age / 0.14);
              for (const [x, y] of MASK)
                grid[y * SIZE + x] = Math.max(grid[y * SIZE + x], f);
            }
          }
          /* Phase 2 (0.25–1.3 s) : ondes de choc concentriques */
          for (const tw of [0.25, 0.55, 0.85]) {
            const age = te - tw;
            if (age <= 0 || age > 0.9) continue;
            const r = age * 14;
            const fade = 1 - age / 0.9;
            for (const [x, y] of MASK) {
              const d = Math.hypot(x - CTR, y - CTR);
              const band = Math.abs(d - r);
              if (band < 1.0)
                grid[y * SIZE + x] = Math.max(grid[y * SIZE + x], (1 - band) * fade);
            }
          }
          /* Phase 3 (1.3–4.1 s) : bandeau JACKPOT en gros, défilement droite → gauche */
          if (te > 1.3 && te < 4.1) {
            grid.fill(0);
            const speed = (BANNER_W + SIZE) / 2.8;
            const scroll = (te - 1.3) * speed;
            for (let v = 0; v < 7; v++) {
              const y = PAY_TOP + v;
              for (let x = 0; x < SIZE; x++) {
                if (Math.hypot(x - CTR, y - CTR) > RADIUS) continue;
                const bc = Math.floor(x - SIZE + scroll);
                if (bc < 0 || bc >= BANNER_W) continue;
                if (BANNER[v][bc] === "2") grid[y * SIZE + x] = 1;
              }
            }
          }
          /* Phase 4 (3.4–5.6 s) : feux d'artifice multiples avec gravité */
          for (const bu of a.fx.bursts) {
            const age = te - bu.t0;
            if (age <= 0) continue;
            if (age < 0.12) {
              const px = Math.round(bu.cx), py = Math.round(bu.cy);
              if (px >= 0 && py >= 0 && px < SIZE && py < SIZE)
                grid[py * SIZE + px] = 1;
            }
            for (const p of bu.parts) {
              if (age > p.life) continue;
              const px = Math.round(bu.cx + Math.cos(p.ang) * p.v * age);
              const py = Math.round(bu.cy + Math.sin(p.ang) * p.v * age + 2.5 * age * age);
              if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) continue;
              if (Math.hypot(px - CTR, py - CTR) > RADIUS) continue;
              grid[py * SIZE + px] = Math.max(grid[py * SIZE + px], 1 - age / p.life);
            }
          }
          /* Phase 5 (5.4–7.3 s) : SEPT GÉANT — zoom + pulse, écran dédié */
          if (te > 5.4) {
            grid.fill(0);
            const e = Math.min((te - 5.4) / 0.45, 1);
            const s = 1 + 1.7 * (1 - Math.pow(1 - e, 3)); // zoom 1 → 2.7
            const fade = te > 7.0 ? Math.max(0, 1 - (te - 7.0) / 0.3) : 1;
            const pulse = 0.6 + 0.4 * Math.sin((te - 5.4) * 12);
            for (const [x, y] of MASK) {
              const u = Math.round((x - CTR) / s + 3);
              const v = Math.round((y - CTR) / s + 3);
              if (u < 0 || u > 6 || v < 0 || v > 6) continue;
              const b = LVL[SPR.seven[v][u]];
              if (b) grid[y * SIZE + x] = b * pulse * fade;
            }
          }
          /* Scintillement continu (1.0–7.2 s) */
          if (te > 1.0 && te < 7.2) {
            for (const tw of a.fx.twinkles) {
              const b = Math.pow(Math.max(0, Math.sin(te * tw.sp + tw.ph)), 3) * 0.35;
              grid[tw.y * SIZE + tw.x] = Math.max(grid[tw.y * SIZE + tw.x], b);
            }
          }
        }
      }

      /* --- rendu LED (pixels carrés) --- */
      ctx.clearRect(0, 0, cv.width, cv.height);
      let amp = 0;
      if (a.mode === "result" && a.resultType === "jackpot") {
        const te = now - a.resultStart;
        /* en fractions de cellule, pour rester constant quelle que soit la grille */
        if (te < 0.6) amp = CELL / 4;
        else if (a.fx && a.fx.bursts.some((b) => te - b.t0 > 0 && te - b.t0 < 0.22)) amp = CELL / 6;
      }
      ctx.save();
      if (amp) ctx.translate((Math.random() * 2 - 1) * amp, (Math.random() * 2 - 1) * amp);
      const off = (CELL - LED) / 2;
      for (const [x, y] of MASK) {
        const b = grid[y * SIZE + x];
        const px = x * CELL + off;
        const py = y * CELL + off;
        ctx.shadowBlur = 0;
        ctx.fillStyle = "rgba(255,255,255,0.05)";
        ctx.fillRect(px, py, LED, LED);
        if (b > 0.02) {
          ctx.fillStyle = `rgba(248,248,244,${Math.min(b, 1)})`;
          if (b > 0.5) {
            ctx.shadowColor = "rgba(255,255,255,0.85)";
            ctx.shadowBlur = LED * b;
          }
          ctx.fillRect(px, py, LED, LED);
        }
      }
      ctx.restore();
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(pressT.current);
      clearTimeout(hintT.current);
    };
  }, []);

  /* --- appui long sur le bouton Glyph ---
     Relâché trop tôt : rien ne se lance, on le dit au lieu de laisser
     croire que le bouton ne répond pas. */
  const pressStart = () => {
    down.current = true;
    fired.current = false;
    clearTimeout(hintT.current);
    setHint(false);
    setPressed(true);
    pressT.current = setTimeout(() => {
      fired.current = true;
      spin(null);
    }, 450);
  };
  const pressEnd = () => {
    if (!down.current) return;
    down.current = false;
    setPressed(false);
    clearTimeout(pressT.current);
    if (fired.current) return;
    setHint(true);
    hintT.current = setTimeout(() => setHint(false), 1900);
  };

  const btn = {
    background: "transparent",
    border: "1px solid #2E2E33",
    color: "#9C9CA3",
    fontFamily: "inherit",
    fontSize: 11,
    letterSpacing: "0.08em",
    textTransform: "uppercase",
    padding: "8px 12px",
    borderRadius: 999,
    cursor: "pointer",
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        /* sans ça, minHeight 100vh s'additionne au padding et la page
           déborde de 68 px — un scroll pour rien */
        boxSizing: "border-box",
        backgroundColor: BG,
        backgroundImage: DOTS,
        backgroundAttachment: "fixed", // la trame ne défile pas avec le contenu
        color: "#E8E8E4",
        fontFamily: "'Space Mono', ui-monospace, SFMono-Regular, monospace",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        padding: "28px 16px 40px",
      }}
    >
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&display=swap');
        button:disabled { opacity:.35; cursor:default; }
        button:not(:disabled):hover { border-color:#5A5A61; color:#E8E8E4; }
        /* glow jaune Nothing : le bouton Glyph doit sauter aux yeux sur la photo */
        @keyframes glyphPulse {
          0%,100% { box-shadow: 0 0 0 1px rgba(${YEL},.55),
                                0 0 12px 2px rgba(${YEL},.30),
                                0 0 0 0 rgba(${YEL},.34); }
          50%     { box-shadow: 0 0 0 2px rgba(${YEL},.95),
                                0 0 26px 6px rgba(${YEL},.60),
                                0 0 0 14px rgba(${YEL},0); }
        }
        .glyph-btn { animation: glyphPulse 2.2s ease-in-out infinite; background: transparent; }
        .glyph-btn:disabled { animation:none; box-shadow:none; }
        .glyph-btn:not(:disabled):hover {
          animation-duration: 1.1s;
          background: radial-gradient(circle, rgba(${YEL},.22), rgba(${YEL},.05) 70%);
        }
        .glyph-btn.is-hint { animation-duration: .7s; }
        .glyph-btn.is-pressed {
          animation:none;
          background: radial-gradient(circle, rgba(${YEL},.34), rgba(0,0,0,.35) 75%);
          box-shadow: 0 0 0 2px rgba(${YEL},1), 0 0 34px 10px rgba(${YEL},.75),
                      inset 0 2px 6px rgba(0,0,0,.6);
        }
        @media (prefers-reduced-motion: reduce) {
          .glyph-btn { animation:none; box-shadow:0 0 0 2px rgba(${YEL},.8), 0 0 18px 4px rgba(${YEL},.45); }
        }
      `}</style>

      {/* header */}
      <div style={{ textAlign: "center", marginBottom: 22 }}>
        <div
          style={{
            fontSize: 20,
            fontWeight: 700,
            letterSpacing: "0.38em",
            textTransform: "uppercase",
          }}
        >
          Glyph Slot<span style={{ color: "#D71921" }}>.</span>
        </div>
        <div style={{ fontSize: 11, color: "#6E6E75", letterSpacing: "0.12em", marginTop: 6 }}>
          MACHINE À SOUS · GLYPH MATRIX 25×25 · PHONE (3)
        </div>
      </div>

      {/* dos du Phone (3) : photo réelle, matrice et bouton Glyph superposés */}
      <div
        style={{
          position: "relative",
          width: PHONE_W,
          aspectRatio: `${PH_W} / ${PH_H}`,
          filter: "drop-shadow(0 26px 48px rgba(0,0,0,.65))",
        }}
      >
        <img
          src={PHONE_SRC}
          alt="Dos d'un Nothing Phone (3)"
          draggable="false"
          style={{
            position: "absolute",
            inset: 0,
            width: "100%",
            height: "100%",
            display: "block",
            /* coupe basse : la photo s'efface en fondu au lieu d'être recouverte
               par un aplat, sinon la trame de points disparaîtrait sur la bande */
            WebkitMaskImage: "linear-gradient(to bottom, #000 86%, transparent 99%)",
            maskImage: "linear-gradient(to bottom, #000 86%, transparent 99%)",
          }}
        />

        {/* matrice, calée sur le disque de la photo */}
        <div
          style={{
            position: "absolute",
            left: `${MX.x}%`,
            top: `${MX.y}%`,
            width: `${MX.d}%`,
            aspectRatio: "1",
            transform: "translate(-50%,-50%)",
            borderRadius: "50%",
            overflow: "hidden",
            background: "radial-gradient(circle at 40% 35%, #0E0E11, #08080A 72%)",
            boxShadow: "inset 0 2px 10px rgba(0,0,0,.9), 0 0 0 1px rgba(0,0,0,.8)",
          }}
        >
          <canvas
            ref={canvasRef}
            width={CANVAS}
            height={CANVAS}
            style={{ width: "100%", height: "100%", display: "block" }}
          />
          {/* reflet du verre */}
          <div
            style={{
              position: "absolute",
              inset: 0,
              borderRadius: "50%",
              pointerEvents: "none",
              background: "linear-gradient(148deg, rgba(255,255,255,.075), rgba(255,255,255,0) 46%)",
            }}
          />
        </div>

        {/* bouton Glyph, calé sur le bouton de la photo */}
        <button
          className={"glyph-btn" + (pressed ? " is-pressed" : "") + (hint ? " is-hint" : "")}
          aria-label="Bouton Glyph — appui long pour lancer"
          disabled={busy}
          onPointerDown={pressStart}
          onPointerUp={pressEnd}
          onPointerLeave={pressEnd}
          style={{
            position: "absolute",
            left: `${BT.x}%`,
            top: `${BT.y}%`,
            width: `${BT.d}%`,
            aspectRatio: "1",
            transform: `translate(-50%,-50%) scale(${pressed ? 0.93 : 1})`,
            borderRadius: "50%",
            border: "none",
            padding: 0,
            /* fond et glow viennent de .glyph-btn : pas de `background` inline,
               il gagnerait sur les règles :hover / .is-pressed */
            transition: "transform .12s",
            cursor: busy ? "default" : "pointer",
          }}
        />

        {/* rappel « appui long », à gauche du bouton, sur relâché trop tôt */}
        <span
          aria-hidden="true"
          style={{
            position: "absolute",
            left: `${BT.x - BT.d / 2 - 2}%`,
            top: `${BT.y}%`,
            transform: `translate(-100%,-50%) translateX(${hint ? 0 : 6}px)`,
            whiteSpace: "nowrap",
            fontSize: 10,
            letterSpacing: "0.14em",
            textTransform: "uppercase",
            color: `rgb(${YEL})`,
            textShadow: `0 0 12px rgba(${YEL},.5)`,
            opacity: hint ? 1 : 0,
            transition: "opacity .45s, transform .45s",
            pointerEvents: "none",
          }}
        >
          Appui long →
        </span>

      </div>

      {/* statut */}
      <div
        style={{
          marginTop: 20,
          fontSize: 12,
          letterSpacing: "0.06em",
          color: status.includes("JACKPOT") ? "#D71921" : "#B8B8BE",
          minHeight: 18,
          textAlign: "center",
        }}
      >
        {status}
      </div>

      {/* timeline 8 s */}
      <div style={{ width: PHONE_W, marginTop: 16 }}>
        <div
          style={{
            position: "relative",
            height: 5,
            background: "#1C1C20",
            borderRadius: 99,
            overflow: "hidden",
          }}
        >
          <div
            ref={barRef}
            style={{ height: "100%", width: "0%", background: "#E8E8E4", borderRadius: 99 }}
          />
        </div>
        <div style={{ position: "relative", height: 16, marginTop: 5 }}>
          {STOPS.map((s, i) => (
            <span
              key={i}
              style={{
                position: "absolute",
                left: (s / 5) * 100 + "%",
                transform: "translateX(-50%)",
                fontSize: 9,
                color: "#6E6E75",
                letterSpacing: "0.08em",
              }}
            >
              R{i + 1}·{s}s
            </span>
          ))}
          <span style={{ position: "absolute", right: 0, fontSize: 9, color: "#6E6E75" }}>5s</span>
        </div>
      </div>

      {/* contrôles de démo */}
      <div style={{ display: "flex", gap: 10, marginTop: 14, flexWrap: "wrap", justifyContent: "center" }}>
        <button style={btn} disabled={busy} onClick={() => spin(null)}>
          Tirage aléatoire
        </button>
        <button style={btn} disabled={busy} onClick={() => spin("win")}>
          Forcer ×3
        </button>
        <button style={btn} disabled={busy} onClick={() => spin("jackpot")}>
          Forcer 777
        </button>
      </div>

      <div
        style={{
          marginTop: 22,
          fontSize: 10,
          color: "#55555C",
          letterSpacing: "0.05em",
          textAlign: "center",
          maxWidth: 340,
          lineHeight: 1.7,
        }}
      >
        Appui long sur le bouton Glyph = event « change » reçu par le service
        GlyphToy. La matrice est rendue à sa place et à son échelle réelles sur
        le dos du Phone (3).
        <br />
        Payline centrale (7 lignes), symboles voisins atténués. Ligne du haut
        vers le bas, arrêts en cascade, léger « settle » à l'arrêt.
      </div>
    </div>
  );
}
