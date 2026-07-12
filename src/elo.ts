// ═══════════════════════════════════════════════════════
//  ELO ENGINE  —  Fencing Tournament App
//  Synthesised from multi-agent deliberation.
//  Zero dependencies. Pure TypeScript, strict-mode safe.
// ═══════════════════════════════════════════════════════

// ── Constants ────────────────────────────────────────────
export const INITIAL_RATING         = 1200;
export const RATING_FLOOR           = 800;
export const PROVISIONAL_THRESHOLD  = 20;   // bouts before rating "stabilises"
export const DEMOTION_BUFFER        = 25;   // ELO points of hysteresis on tier demotion
export const BASE_FENC_REWARD       = 100;  // base FENC tokens per bout
export const PLATFORM_FEE_RATE      = 0.05; // 5 % rake on USDC bets
export const MAX_USDC_MULTIPLIER    = 3.0;  // cap on ELO-adjusted payout multiplier
export const PROTECTED_ELO_BUFFER   = 150;  // anti-sandbagging: protectedELO = peak − 150

// ── Tier Table ───────────────────────────────────────────
export interface Tier {
  name:             string;
  floor:            number;   // minimum ELO for this tier
  rewardMultiplier: number;   // FENC multiplier
  color:            string;   // hex for UI badges
  emoji:            string;
  minBetUSDC:       number;
  maxBetUSDC:       number;
}

export const TIERS: Tier[] = [
  { name: 'Iron',        floor:  800, rewardMultiplier:  0.5, color: '#9CA3AF', emoji: '⚙️',  minBetUSDC:   1, maxBetUSDC:   10 },
  { name: 'Bronze',      floor: 1100, rewardMultiplier:  1.0, color: '#B45309', emoji: '🥉',  minBetUSDC:   2, maxBetUSDC:   25 },
  { name: 'Silver',      floor: 1300, rewardMultiplier:  1.5, color: '#6B7280', emoji: '🥈',  minBetUSDC:   5, maxBetUSDC:   75 },
  { name: 'Gold',        floor: 1500, rewardMultiplier:  2.0, color: '#D97706', emoji: '🥇',  minBetUSDC:  10, maxBetUSDC:  200 },
  { name: 'Platinum',    floor: 1700, rewardMultiplier:  3.0, color: '#0EA5E9', emoji: '💎',  minBetUSDC:  25, maxBetUSDC:  500 },
  { name: 'Diamond',     floor: 1900, rewardMultiplier:  4.5, color: '#6366F1', emoji: '♦️',  minBetUSDC:  50, maxBetUSDC: 1500 },
  { name: 'Masters',     floor: 2100, rewardMultiplier:  6.0, color: '#EC4899', emoji: '👑',  minBetUSDC: 100, maxBetUSDC: 3000 },
  { name: 'Grandmaster', floor: 2300, rewardMultiplier: 10.0, color: '#F59E0B', emoji: '🏆',  minBetUSDC: 200, maxBetUSDC: 5000 },
];

// ── Core Types ───────────────────────────────────────────
export type Weapon     = 'foil' | 'epee' | 'sabre';
export type BoutFormat = 'poule' | 'DE';

export interface FencerRating {
  rating:       number;
  boutsPlayed:  number;
  lastBoutDate: string | null; // ISO 8601
}

export interface FencerProfile {
  id:            string;
  name:          string;
  ratings:       Record<Weapon, FencerRating>;
  currentTier:   string;        // tier name; updated after each bout
  peakELO:       number;        // all-time high for any weapon
  protectedELO:  number;        // max(current, peak − PROTECTED_ELO_BUFFER) — anti-sandbagging
  fencBalance:   number;        // accumulated FENC tokens (demo mode)
  usdcBalance:   number;        // mock USDC for demo betting
  walletAddress: string | null; // ETH address once connected
  seasonPoints:  number;
}

export interface ELODelta {
  deltaWinner: number;
  deltaLoser:  number;
}

export interface MatchPreview {
  pWinA: number; pWinB: number;
  eloGainA: number; eloLossA: number;
  eloGainB: number; eloLossB: number;
  fencWinA: number; fencWinB: number;
  usdcPayoutA: number; usdcPayoutB: number;
  tierA: Tier; tierB: Tier;
  underdog: 'A' | 'B' | 'even';
}

// ── K-Factor ─────────────────────────────────────────────
export function getKFactor(rating: number, boutsPlayed: number): number {
  // Provisional phase — rapid convergence
  if (boutsPlayed < 8)  return 64;
  if (boutsPlayed < 16) return 48;
  if (boutsPlayed < 21) return 32;
  // Established — stability increases with rating
  if (rating < 1200) return 32;
  if (rating < 1600) return 24;
  if (rating < 2000) return 20;
  return 16;
}

// ── Expected Score ────────────────────────────────────────
export function expectedScore(ratingA: number, ratingB: number): number {
  return 1 / (1 + Math.pow(10, (ratingB - ratingA) / 400));
}

// ── Margin-of-Victory Multiplier ─────────────────────────
// ln(1+diff) / ln(1+max) — normalised to (0,1]; 5–0 poule win = 1.0
export function movMultiplier(scoreWinner: number, scoreLoser: number, maxTouches: number): number {
  const diff = Math.abs(scoreWinner - scoreLoser);
  if (diff === 0) return 0;
  return Math.log(1 + diff) / Math.log(1 + maxTouches);
}

// ── Tier Lookup (with demotion hysteresis) ────────────────
export function getTier(rating: number, currentTierName?: string): Tier {
  // Find highest tier the player qualifies for (promotion: immediate)
  let promotionTier = TIERS[0];
  for (const t of TIERS) {
    if (rating >= t.floor) promotionTier = t;
  }
  // Demotion hysteresis: stay in current tier until 25 pts below its floor
  if (currentTierName) {
    const currentIdx    = TIERS.findIndex(t => t.name === currentTierName);
    const promotionIdx  = TIERS.findIndex(t => t.name === promotionTier.name);
    if (currentIdx > 0 && promotionIdx < currentIdx) {
      const currentTier = TIERS[currentIdx];
      if (rating >= currentTier.floor - DEMOTION_BUFFER) return currentTier;
    }
  }
  return promotionTier;
}

export function isProvisional(fr: FencerRating): boolean {
  return fr.boutsPlayed < PROVISIONAL_THRESHOLD;
}

export function displayRating(fr: FencerRating): string {
  const r = Math.round(fr.rating);
  return isProvisional(fr) ? `~${r}` : `${r}`;
}

// Seeding uses rating with provisional penalty
export function seedingRating(fr: FencerRating): number {
  return isProvisional(fr) ? fr.rating - 50 : fr.rating;
}

// Composite rating across weapons (weighted by bouts, ≥5 bouts to count)
export function compositeRating(profile: FencerProfile): number {
  const weapons: Weapon[] = ['foil', 'epee', 'sabre'];
  let weightedSum = 0, totalWeight = 0;
  for (const w of weapons) {
    const r = profile.ratings[w];
    if (r.boutsPlayed >= 5) {
      weightedSum += r.rating * r.boutsPlayed;
      totalWeight += r.boutsPlayed;
    }
  }
  if (totalWeight === 0) return INITIAL_RATING;
  return weightedSum / totalWeight;
}

// ── ELO Delta ────────────────────────────────────────────
export function computeELODelta(
  ratingWinner:  number,
  ratingLoser:   number,
  scoreWinner:   number,
  scoreLoser:    number,
  format:        BoutFormat,
  boutsWinner:   number,
  boutsLoser:    number,
): ELODelta {
  const maxTouches = format === 'poule' ? 5 : 15;
  const Ew = expectedScore(ratingWinner, ratingLoser);
  const Kw = getKFactor(ratingWinner, boutsWinner);
  const Kl = getKFactor(ratingLoser,  boutsLoser);
  // Floor MoV at 0.2 so even narrow wins earn something
  const mov = Math.max(movMultiplier(scoreWinner, scoreLoser, maxTouches), 0.2);

  const deltaWinner = Math.round(Kw * mov * (1 - Ew));
  const deltaLoser  = -Math.round(Kl * mov * Ew);

  return { deltaWinner, deltaLoser };
}

// Apply floor (must be called after delta)
export function applyFloor(rating: number): number {
  return Math.max(rating, RATING_FLOOR);
}

// ── FENC Reward Formula ───────────────────────────────────
// EV multiplier × DVG (David-vs-Goliath) bonus
export function computeFENCReward(
  winnerELO:  number,
  loserELO:   number,
  didWin:     boolean,
  baseFENC =  BASE_FENC_REWARD,
): number {
  const pWin = expectedScore(winnerELO, loserELO);
  if (didWin) {
    const evMultiplier  = Math.min(1 / pWin, 200);           // cap 200x
    const eloDiff       = loserELO - winnerELO;              // positive = underdog
    const dvgMultiplier = eloDiff > 200
      ? 1 + Math.pow(eloDiff / 400, 1.5)
      : 1.0;
    return Math.round(baseFENC * evMultiplier * dvgMultiplier);
  }
  // Consolation — keeps losing worthwhile but not farm-able
  return Math.round(baseFENC * (1 - pWin) * 0.25);
}

// Season Points earned per match
export function computeSeasonPoints(
  winnerELO: number,
  loserELO:  number,
  didWin:    boolean,
): number {
  if (!didWin) return 2; // participation credit
  const pWin = expectedScore(winnerELO, loserELO);
  return Math.round(10 * (1 / pWin));
}

// ── USDC Payout ───────────────────────────────────────────
export interface USDCPayout {
  winnerPayout:  number;
  platformFee:   number;
  reserveDraw:   number; // amount drawn from reserve fund (may be 0)
}

export function computeUSDCPayout(
  stakeWinner:    number,
  stakeLoser:     number,
  winnerELO:      number,
  loserELO:       number,
  feeRate =       PLATFORM_FEE_RATE,
  maxMultiplier = MAX_USDC_MULTIPLIER,
): USDCPayout {
  const pool       = stakeWinner + stakeLoser;
  const fee        = pool * feeRate;
  const netPool    = pool - fee;
  const pWin       = expectedScore(winnerELO, loserELO);
  const multiplier = Math.min(1 / pWin, maxMultiplier);
  const winnerPayout = Math.min(netPool * multiplier, netPool * maxMultiplier);
  const reserveDraw  = Math.max(0, winnerPayout - netPool);
  return { winnerPayout, platformFee: fee, reserveDraw };
}

// ── Pre-Match Preview ────────────────────────────────────
export function computeMatchPreview(
  ratingA:   number, ratingB:   number,
  boutsA:    number, boutsB:    number,
  tierNameA: string, tierNameB: string,
  stakeUSDC = 10,
): MatchPreview {
  const pWinA  = expectedScore(ratingA, ratingB);
  const pWinB  = 1 - pWinA;
  const Ka     = getKFactor(ratingA, boutsA);
  const Kb     = getKFactor(ratingB, boutsB);
  const tierA  = getTier(ratingA, tierNameA);
  const tierB  = getTier(ratingB, tierNameB);

  const eloGainA = Math.round(Ka * (1 - pWinA));
  const eloLossA = Math.round(Ka * pWinA);
  const eloGainB = Math.round(Kb * (1 - pWinB));
  const eloLossB = Math.round(Kb * pWinB);

  const fencWinA = computeFENCReward(ratingA, ratingB, true);
  const fencWinB = computeFENCReward(ratingB, ratingA, true);

  const { winnerPayout: usdcPayoutA } = computeUSDCPayout(stakeUSDC, stakeUSDC, ratingA, ratingB);
  const { winnerPayout: usdcPayoutB } = computeUSDCPayout(stakeUSDC, stakeUSDC, ratingB, ratingA);

  const underdog: 'A' | 'B' | 'even' =
    pWinA < 0.45 ? 'A' : pWinB < 0.45 ? 'B' : 'even';

  return { pWinA, pWinB, eloGainA, eloLossA, eloGainB, eloLossB,
           fencWinA, fencWinB, usdcPayoutA, usdcPayoutB, tierA, tierB, underdog };
}

// ── Profile Factories ────────────────────────────────────
function blankRating(): FencerRating {
  return { rating: INITIAL_RATING, boutsPlayed: 0, lastBoutDate: null };
}

export function createProfile(name: string): FencerProfile {
  return {
    id:            crypto.randomUUID(),
    name,
    ratings:       { foil: blankRating(), epee: blankRating(), sabre: blankRating() },
    currentTier:   'Bronze',
    peakELO:       INITIAL_RATING,
    protectedELO:  INITIAL_RATING,
    fencBalance:   100,   // welcome FENC pack
    usdcBalance:   100,   // demo USDC
    walletAddress: null,
    seasonPoints:  0,
  };
}

// Update a profile after a rated bout
export function applyBoutResult(
  profile:  FencerProfile,
  weapon:   Weapon,
  delta:    number,          // positive = gained, negative = lost
  fencEarned: number,
  spEarned:   number,
  usdcNet:    number,        // positive = won money, negative = lost stake
): FencerProfile {
  const fr = profile.ratings[weapon];
  const newRating = applyFloor(fr.rating + delta);
  const newBouts  = fr.boutsPlayed + 1;
  const newPeak   = Math.max(profile.peakELO, newRating);
  const newTier   = getTier(newRating, profile.currentTier);
  return {
    ...profile,
    ratings: {
      ...profile.ratings,
      [weapon]: {
        rating:       newRating,
        boutsPlayed:  newBouts,
        lastBoutDate: new Date().toISOString(),
      },
    },
    currentTier:  newTier.name,
    peakELO:      newPeak,
    protectedELO: Math.max(newRating, newPeak - PROTECTED_ELO_BUFFER),
    fencBalance:  profile.fencBalance + fencEarned,
    usdcBalance:  Math.max(0, profile.usdcBalance + usdcNet),
    seasonPoints: profile.seasonPoints + spEarned,
  };
}
