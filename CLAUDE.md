# UFL Fencing Labelling App

## Project
Single-file Android-optimised fencing match labelling app. No build step.
Source: `ufl/ufl-mobile.html`

React/TypeScript PWA layer: `src/App.tsx` (mobile-optimised, voice commands, ELO, crypto betting)

## Stack

### UFL HTML App (`ufl/ufl-mobile.html`)
- Vanilla JS, HTML5 Canvas, MediaRecorder API
- Zero dependencies, zero frameworks

### React PWA (`src/`)
- React 18 + TypeScript + Vite
- Tailwind CSS (utility classes, no config file)
- Web Speech API (voice commands)
- ELO engine: `src/elo.ts`
- Target: Android & iOS browsers (PWA)

## Architecture

### UFL HTML App
- **Screens**: Setup (state 0) → Match (state 1) → Pause/Result (state 2) → Events list (state 3)
- **Pie menu**: MENU_MAIN → MENU_SWORDS (attack) / MENU_SHIELD (defense) / MENU_CIRCLE (other) / MENU_CARD (cards)
- **ActionID scheme**: Left fencer hits = 100-105, off-target = 140-145, cards = 190-191. Right = 200s. Ref = single digits.
- **Match state**: `M` object holds all live match data. `PS` holds pending pie selection state.
- **Touch**: All interaction via `touchend` listeners with 300ms debounce (no `onclick` on non-button elements).

### React PWA
- **Tabs**: Register → Poules → Seeding → Tableau → Rankings → Wallet
- **ELO**: `src/elo.ts` — K-factors, MoV multiplier, 8 tiers (Iron→Grandmaster), FENC/USDC reward formulas
- **Betting**: Demo-mode USDC escrow + FENC token rewards (EV × DVG multiplier)
- **Persistence**: localStorage for fencer profiles, ratings, balances
- **Voice**: Web Speech API toggle, continuous recognition, stateRef pattern

## Key UFL Functions
- `clickStart()` — initialise match, start countdown
- `clickEvent(isLeft)` — open pie menu for left/right fencer
- `handlePieTap(e, side)` — process pie sector tap, advance menu state or show confirm
- `doConfirm(isHit)` — record action, update score/HP, check win condition
- `drawPie(side, menuType)` — render radial canvas menu
- `toggleCamera()` — cycle through: idle → camera preview → recording → stop
- `exportJSON()` / `exportCSV()` / `exportTXT()` — download match data

## Key ELO Constants (src/elo.ts)
- Starting rating: 1200, Floor: 800, Provisional threshold: 20 bouts
- K-factors: 64/48/32 (provisional), 32/24/20/16 (established by rating band)
- MoV multiplier: `ln(1+diff) / ln(1+max)` — rewards decisive wins
- FENC reward: `base × (1/pWin) × DVG` where DVG = `1 + (eloDiff/400)^1.5` when diff > 200
- USDC payout: ELO-adjusted parimutuel, 5% fee, 3× cap, reserve fund for upsets

## ELO Tiers
| Tier | ELO | FENC Mult | USDC Bet Range |
|---|---|---|---|
| Iron | 800–1099 | 0.5× | $1–$10 |
| Bronze | 1100–1299 | 1.0× | $2–$25 |
| Silver | 1300–1499 | 1.5× | $5–$75 |
| Gold | 1500–1699 | 2.0× | $10–$200 |
| Platinum | 1700–1899 | 3.0× | $25–$500 |
| Diamond | 1900–2099 | 4.5× | $50–$1500 |
| Masters | 2100–2299 | 6.0× | $100–$3000 |
| Grandmaster | 2300+ | 10.0× | $200–$5000 |

## Files
```
/home/user/fencing-app/
├── CLAUDE.md                  ← this file
├── index.html                 ← React PWA entry point
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── App.tsx                ← Main React component (6 tabs)
│   ├── elo.ts                 ← ELO engine (pure TS, zero deps)
│   ├── main.tsx               ← React root
│   └── index.css              ← Global styles
└── ufl/
    └── ufl-mobile.html        ← Standalone UFL labelling app
```

## Branch
Active development: `claude/optimize-fencing-app-voice-xXiuq`
Main: `main`
Remote: `dunkaroo173/fencing-app` (GitHub)

## Development Notes
- Never commit secrets or wallet private keys
- `elo.ts` is zero-dependency pure TypeScript — keep it that way
- All new React components go in `src/`
- UFL HTML app is self-contained — edits stay within `ufl/ufl-mobile.html`
- Run locally: `npm install && npm run dev` (Vite dev server on port 5173)
