import { useEffect, useRef, useState } from "react";

/* ---------- Glyph Matrix : constantes ---------- */
const SIZE = 25;          // matrice 25×25
const CELL = 12;          // px par LED (rendu)
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

/* masque circulaire pré-calculé */
const MASK = [];
for (let y = 0; y < SIZE; y++)
  for (let x = 0; x < SIZE; x++)
    if (Math.hypot(x - CTR, y - CTR) <= RADIUS) MASK.push([x, y]);

export default function GlyphSlot() {
  const canvasRef = useRef(null);
  const barRef = useRef(null);
  const pressT = useRef(null);
  const [status, setStatus] = useState("Appui long sur le bouton Glyph pour lancer");
  const [busy, setBusy] = useState(false);
  const [pressed, setPressed] = useState(false);

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
      const S = 8; // côté du carré LED (gap de 4 px entre LEDs)
      ctx.clearRect(0, 0, cv.width, cv.height);
      let amp = 0;
      if (a.mode === "result" && a.resultType === "jackpot") {
        const te = now - a.resultStart;
        if (te < 0.6) amp = 3;
        else if (a.fx && a.fx.bursts.some((b) => te - b.t0 > 0 && te - b.t0 < 0.22)) amp = 2;
      }
      ctx.save();
      if (amp) ctx.translate((Math.random() * 2 - 1) * amp, (Math.random() * 2 - 1) * amp);
      for (const [x, y] of MASK) {
        const b = grid[y * SIZE + x];
        const px = x * CELL + (CELL - S) / 2;
        const py = y * CELL + (CELL - S) / 2;
        ctx.shadowBlur = 0;
        ctx.fillStyle = "rgba(255,255,255,0.05)";
        ctx.fillRect(px, py, S, S);
        if (b > 0.02) {
          ctx.fillStyle = `rgba(248,248,244,${Math.min(b, 1)})`;
          if (b > 0.5) {
            ctx.shadowColor = "rgba(255,255,255,0.85)";
            ctx.shadowBlur = 8 * b;
          }
          ctx.fillRect(px, py, S, S);
        }
      }
      ctx.restore();
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, []);

  /* --- appui long sur le bouton Glyph --- */
  const pressStart = () => {
    setPressed(true);
    pressT.current = setTimeout(() => spin(null), 450);
  };
  const pressEnd = () => {
    setPressed(false);
    clearTimeout(pressT.current);
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
        background: "#0A0A0B",
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

      {/* dos du téléphone */}
      <div
        style={{
          position: "relative",
          width: 344,
          borderRadius: 34,
          background: "linear-gradient(160deg,#1B1B1E,#141416)",
          border: "1px solid #29292E",
          padding: "22px 22px 18px",
          boxShadow: "0 24px 60px rgba(0,0,0,.55)",
        }}
      >
        {/* LED rouge (enregistrement) */}
        <div
          style={{
            position: "absolute",
            top: 18,
            left: 26,
            width: 7,
            height: 7,
            borderRadius: 99,
            background: "#D71921",
            boxShadow: "0 0 8px rgba(215,25,33,.7)",
          }}
        />
        {/* matrice */}
        <div
          style={{
            width: 300,
            height: 300,
            borderRadius: "50%",
            background: "radial-gradient(circle at 40% 35%, #101013, #0B0B0D 70%)",
            border: "1px solid #26262B",
            boxShadow: "inset 0 4px 18px rgba(0,0,0,.8)",
            display: "grid",
            placeItems: "center",
          }}
        >
          <canvas ref={canvasRef} width={300} height={300} style={{ width: 300, height: 300 }} />
        </div>

        {/* bouton Glyph */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 10, marginTop: 16 }}>
          <span style={{ fontSize: 10, color: "#6E6E75", letterSpacing: "0.1em" }}>
            APPUI LONG →
          </span>
          <button
            aria-label="Bouton Glyph — appui long pour lancer"
            disabled={busy}
            onPointerDown={pressStart}
            onPointerUp={pressEnd}
            onPointerLeave={pressEnd}
            style={{
              width: 34,
              height: 34,
              borderRadius: "50%",
              border: "1px solid #3A3A40",
              background: pressed ? "#2A2A2F" : "#1E1E22",
              transform: pressed ? "scale(.88)" : "scale(1)",
              transition: "transform .12s, background .12s",
              cursor: busy ? "default" : "pointer",
              boxShadow: "inset 0 2px 5px rgba(0,0,0,.6)",
            }}
          />
        </div>
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
      <div style={{ width: 320, marginTop: 16 }}>
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
        Appui long = event « change » reçu par le service GlyphToy.
        <br />
        Payline centrale (7 lignes), symboles voisins atténués. Ligne du haut
        vers le bas, arrêts en cascade, léger « settle » à l'arrêt.
      </div>
    </div>
  );
}
