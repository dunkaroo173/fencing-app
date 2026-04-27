import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  createProfile, applyBoutResult, getTier, displayRating,
  computeELODelta, computeFENCReward, computeSeasonPoints,
  TIERS, type FencerProfile, type Weapon,
} from './elo';

const WEAPON: Weapon = 'foil';
const STORAGE_KEY = 'fencing_profiles_v1';

function loadProfiles(): Record<string, FencerProfile> {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}'); } catch { return {}; }
}
function saveProfiles(p: Record<string, FencerProfile>) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(p));
}

declare global {
  interface Window {
    SpeechRecognition: any;
    webkitSpeechRecognition: any;
  }
}

// Radial-style score selector: 0–5 as circular radio buttons
function ScoreSelector({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <div className="flex gap-1.5 flex-wrap">
      {[0, 1, 2, 3, 4, 5].map(n => (
        <button
          key={n}
          onPointerDown={e => { e.stopPropagation(); onChange(n); }}
          style={{ minWidth: 40, minHeight: 40 }}
          className={`w-10 h-10 rounded-full text-sm font-bold border-2 transition-all select-none
            ${value === n
              ? 'bg-blue-600 text-white border-blue-600 shadow-md scale-110'
              : 'bg-white text-gray-700 border-gray-300 active:bg-gray-100'
            }`}
        >
          {n}
        </button>
      ))}
    </div>
  );
}

// Toggle switch for voice mode
function ToggleSwitch({
  enabled, onChange, label,
}: { enabled: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      onClick={() => onChange(!enabled)}
      className="flex items-center gap-2 select-none"
      aria-label={label}
    >
      <span className="text-xs font-semibold text-white/90">{label}</span>
      <span
        className={`relative inline-flex w-12 h-6 rounded-full transition-colors duration-200
          ${enabled ? 'bg-green-400' : 'bg-white/30'}`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform duration-200
            ${enabled ? 'translate-x-6' : 'translate-x-0'}`}
        />
      </span>
    </button>
  );
}

// Pill badge
function Pill({ children, color = 'blue' }: { children: React.ReactNode; color?: string }) {
  const bg = color === 'red' ? 'bg-red-500' : color === 'green' ? 'bg-green-500' : 'bg-blue-500';
  return (
    <span className={`inline-flex items-center gap-1 text-xs font-semibold text-white px-2 py-0.5 rounded-full ${bg}`}>
      {children}
    </span>
  );
}

type Match = { pouleIdx: number; keyA: string; keyB: string };

export default function TournamentAppPreview() {
  const [tab, setTab] = useState('register');
  const [profiles, setProfiles] = useState<Record<string, FencerProfile>>(loadProfiles);
  const [fencers, setFencers] = useState<string[]>([]);
  const [inputName, setInputName] = useState('');
  const [poules, setPoules] = useState<any[]>([]);
  const [pouleScores, setPouleScores] = useState<{ [key: string]: number }>({});
  const [seeding, setSeeding] = useState<any[]>([]);
  const [tableau, setTableau] = useState<any[]>([]);
  const [activeMatch, setActiveMatch] = useState<Match | null>(null);

  // Voice state
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [voiceStatus, setVoiceStatus] = useState('');
  const [voiceSupported, setVoiceSupported] = useState(false);

  // Persist profiles on every change
  useEffect(() => { saveProfiles(profiles); }, [profiles]);

  const recognitionRef = useRef<any>(null);

  // Keep mutable refs to avoid stale closures inside recognition callbacks
  const stateRef = useRef({
    tab, fencers, activeMatch, pouleScores, poules, voiceEnabled, profiles,
    setFencers, setPouleScores, setPoules, setSeeding, setTableau, setTab, setVoiceStatus, setActiveMatch,
    setProfiles,
  });
  stateRef.current = {
    tab, fencers, activeMatch, pouleScores, poules, voiceEnabled, profiles,
    setFencers, setPouleScores, setPoules, setSeeding, setTableau, setTab, setVoiceStatus, setActiveMatch,
    setProfiles,
  };

  useEffect(() => {
    setVoiceSupported(!!(window.SpeechRecognition || window.webkitSpeechRecognition));
  }, []);

  // Stable generatePoules / calculateSeeding via ref
  const generatePoules = useCallback(() => {
    const { fencers, setPoules } = stateRef.current;
    const shuffled = [...fencers].sort(() => Math.random() - 0.5);
    const mid = Math.ceil(shuffled.length / 2);
    setPoules([
      { id: 1, fencers: shuffled.slice(0, mid).map((name: string) => ({ name })) },
      { id: 2, fencers: shuffled.slice(mid).map((name: string) => ({ name })) },
    ]);
  }, []);

  const calculateSeeding = useCallback(() => {
    const { poules, pouleScores, setSeeding, setTableau } = stateRef.current;
    const stats: { [k: string]: { wins: number; indicator: number } } = {};
    poules.forEach((poule: any) => {
      poule.fencers.forEach((fa: any, i: number) => {
        poule.fencers.slice(i + 1).forEach((fb: any) => {
          const keyA = `${fa.name}-${fb.name}-A`;
          const keyB = `${fa.name}-${fb.name}-B`;
          const aScore = pouleScores[keyA] ?? 0;
          const bScore = pouleScores[keyB] ?? 0;
          if (!stats[fa.name]) stats[fa.name] = { wins: 0, indicator: 0 };
          if (!stats[fb.name]) stats[fb.name] = { wins: 0, indicator: 0 };
          if (aScore > bScore) stats[fa.name].wins++;
          if (bScore > aScore) stats[fb.name].wins++;
          stats[fa.name].indicator += aScore - bScore;
          stats[fb.name].indicator += bScore - aScore;
        });
      });
    });
    const sorted = Object.entries(stats)
      .map(([name, s]) => ({ name, ...s }))
      .sort((a, b) => b.wins - a.wins || b.indicator - a.indicator);
    setSeeding(sorted);
    const matches = [];
    for (let i = 0; i < sorted.length; i += 2) {
      matches.push([sorted[i], sorted[i + 1] || null]);
    }
    setTableau(matches);

    // Apply ELO deltas for every poule result
    const currentProfiles = stateRef.current.profiles ?? {};
    let updated = { ...currentProfiles };
    poules.forEach((poule: any) => {
      poule.fencers.forEach((fa: any, i: number) => {
        poule.fencers.slice(i + 1).forEach((fb: any) => {
          const sa = pouleScores[`${fa.name}-${fb.name}-A`] ?? 0;
          const sb = pouleScores[`${fa.name}-${fb.name}-B`] ?? 0;
          if (sa === 0 && sb === 0) return;
          const [winner, loser, sw, sl] = sa >= sb
            ? [fa.name, fb.name, sa, sb] : [fb.name, fa.name, sb, sa];
          const wp = updated[winner] ?? createProfile(winner);
          const lp = updated[loser]  ?? createProfile(loser);
          const wr = wp.ratings[WEAPON], lr = lp.ratings[WEAPON];
          const { deltaWinner, deltaLoser } = computeELODelta(
            wr.rating, lr.rating, sw, sl, 'poule', wr.boutsPlayed, lr.boutsPlayed);
          const fencW = computeFENCReward(wr.rating, lr.rating, true);
          const fencL = computeFENCReward(lr.rating, wr.rating, false);
          const spW   = computeSeasonPoints(wr.rating, lr.rating, true);
          const spL   = computeSeasonPoints(lr.rating, wr.rating, false);
          updated[winner] = applyBoutResult(wp, WEAPON, deltaWinner, fencW, spW, 0);
          updated[loser]  = applyBoutResult(lp, WEAPON, deltaLoser,  fencL, spL, 0);
        });
      });
    });
    stateRef.current.setProfiles?.(updated);
  }, []);

  // Voice command processor (always reads from stateRef — no stale closures)
  const processVoiceCommand = useCallback((transcript: string) => {
    const {
      tab, fencers, activeMatch, pouleScores,
      setFencers, setPouleScores, setTab, setVoiceStatus, setActiveMatch,
    } = stateRef.current;

    const text = transcript.toLowerCase().trim();
    setVoiceStatus(`Heard: "${transcript}"`);

    // Navigation
    if (/\bregister\b/.test(text)) { setTab('register'); setVoiceStatus('→ Register'); return; }
    if (/\bpoule[s]?\b|\bpool[s]?\b/.test(text)) { setTab('poules'); setVoiceStatus('→ Poules'); return; }
    if (/\bseeding\b|\bseed\b/.test(text)) { setTab('seeding'); setVoiceStatus('→ Seeding'); return; }
    if (/\btableau\b|\bbracket\b/.test(text)) { setTab('tableau'); setVoiceStatus('→ Tableau'); return; }

    // Generate poules
    if (/generate\s+poule/.test(text)) {
      if (fencers.length >= 2) {
        generatePoules();
        setTab('poules');
        setVoiceStatus('Poules generated');
      } else {
        setVoiceStatus('Need at least 2 fencers');
      }
      return;
    }

    // Calculate seeding
    if (/calculate\s+seeding|generate\s+seeding/.test(text)) {
      calculateSeeding();
      setTab('seeding');
      setVoiceStatus('Seeding calculated');
      return;
    }

    // Add fencer: "add [name]"
    const addMatch = text.match(/^add\s+(.+)$/);
    if (addMatch && tab === 'register') {
      const raw = addMatch[1].trim();
      const formatted = raw.replace(/\b\w/g, c => c.toUpperCase());
      setFencers((prev: string[]) => {
        if (prev.includes(formatted)) { setVoiceStatus(`${formatted} already registered`); return prev; }
        setVoiceStatus(`Added: ${formatted}`);
        return [...prev, formatted];
      });
      return;
    }

    // Score entry: "score a [n]" / "score b [n]" / "left [n]" / "right [n]"
    const scoreA = text.match(/(?:score\s+a|left|red|fencer\s+a)\s+(\d)/);
    const scoreB = text.match(/(?:score\s+b|right|green|fencer\s+b)\s+(\d)/);
    if (activeMatch) {
      if (scoreA) {
        const n = parseInt(scoreA[1]);
        if (n >= 0 && n <= 5) {
          setPouleScores((prev: { [k: string]: number }) => ({ ...prev, [activeMatch.keyA]: n }));
          setVoiceStatus(`Score A → ${n}`);
        }
        return;
      }
      if (scoreB) {
        const n = parseInt(scoreB[1]);
        if (n >= 0 && n <= 5) {
          setPouleScores((prev: { [k: string]: number }) => ({ ...prev, [activeMatch.keyB]: n }));
          setVoiceStatus(`Score B → ${n}`);
        }
        return;
      }
    }

    // Select match by number: "match [n]"
    const matchSelect = text.match(/match\s+(\d+)/);
    if (matchSelect) {
      setVoiceStatus(`Select match ${matchSelect[1]} manually`);
      return;
    }

    setVoiceStatus(`Not recognized: "${transcript}"`);
  }, [generatePoules, calculateSeeding]);

  // Start / stop recognition when voiceEnabled changes
  useEffect(() => {
    if (!voiceEnabled || !voiceSupported) {
      recognitionRef.current?.stop();
      recognitionRef.current = null;
      setIsListening(false);
      return;
    }

    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    const recognition = new SR();
    recognition.continuous = true;
    recognition.interimResults = false;
    recognition.lang = 'en-US';
    recognition.maxAlternatives = 1;

    recognition.onstart = () => setIsListening(true);
    recognition.onerror = (e: any) => {
      if (e.error !== 'no-speech' && e.error !== 'aborted') {
        setVoiceStatus(`Mic error: ${e.error}`);
      }
    };
    recognition.onend = () => {
      setIsListening(false);
      if (stateRef.current.voiceEnabled) {
        try { recognition.start(); } catch (_) {}
      }
    };
    recognition.onresult = (e: any) => {
      const last = e.results[e.results.length - 1];
      if (last.isFinal) processVoiceCommand(last[0].transcript);
    };

    recognitionRef.current = recognition;
    try { recognition.start(); } catch (_) {}

    return () => {
      recognition.onend = null;
      recognition.stop();
      recognitionRef.current = null;
    };
  }, [voiceEnabled, voiceSupported, processVoiceCommand]);

  const addFencer = () => {
    const name = inputName.trim();
    if (name && !fencers.includes(name)) {
      setFencers([...fencers, name]);
      if (!profiles[name]) {
        setProfiles(prev => ({ ...prev, [name]: createProfile(name) }));
      }
      setInputName('');
    }
  };

  const tabs = [
    { id: 'register',  label: 'Register',  icon: '👤' },
    { id: 'poules',    label: 'Poules',    icon: '⚔️' },
    { id: 'seeding',   label: 'Seeding',   icon: '📊' },
    { id: 'tableau',   label: 'Tableau',   icon: '🏆' },
    { id: 'rankings',  label: 'Rankings',  icon: '🎖️' },
    { id: 'wallet',    label: 'Wallet',    icon: '💰' },
  ];

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col" style={{ maxWidth: 480, margin: '0 auto' }}>

      {/* Header */}
      <header className="bg-blue-700 text-white px-4 shadow-md"
        style={{ paddingTop: 'max(12px, env(safe-area-inset-top))', paddingBottom: 12 }}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xl">⚔️</span>
            <h1 className="text-base font-bold tracking-wide">Fencing Tournament</h1>
          </div>
          <div className="flex items-center gap-3">
            {isListening && (
              <Pill color="red">
                <span className="animate-pulse">●</span> Listening
              </Pill>
            )}
            {voiceSupported
              ? <ToggleSwitch enabled={voiceEnabled} onChange={setVoiceEnabled} label="Voice" />
              : <span className="text-xs text-blue-300">Voice N/A</span>
            }
          </div>
        </div>
        {voiceEnabled && voiceStatus && (
          <p className="text-xs text-blue-200 mt-1 truncate">{voiceStatus}</p>
        )}
      </header>

      {/* Voice hint bar */}
      {voiceEnabled && (
        <div className="bg-blue-50 border-b border-blue-200 px-4 py-2">
          <p className="text-xs text-blue-700">
            <span className="font-semibold">Say:</span>{' '}
            "Add [name]" · "Score A/B [0–5]" · "Generate poules" · "Go to seeding"
          </p>
        </div>
      )}

      {/* Main content */}
      <main className="flex-1 overflow-y-auto px-4 py-4"
        style={{ paddingBottom: 'max(80px, calc(64px + env(safe-area-inset-bottom)))' }}>

        {/* ── REGISTER ── */}
        {tab === 'register' && (
          <div className="space-y-4">
            <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider">Register Fencers</h2>

            <div className="flex gap-2">
              <input
                className="flex-1 border border-gray-300 rounded-xl px-4 py-3 text-base bg-white"
                value={inputName}
                onChange={e => setInputName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && addFencer()}
                placeholder="Fencer name"
                autoCapitalize="words"
                autoCorrect="off"
              />
              <button
                onClick={addFencer}
                className="px-5 py-3 bg-green-600 text-white rounded-xl font-bold text-base active:bg-green-700"
              >
                Add
              </button>
            </div>

            {fencers.length > 0 && (
              <div className="bg-white rounded-2xl border border-gray-200 overflow-hidden shadow-sm">
                {fencers.map((f, i) => (
                  <div key={i}
                    className={`flex items-center justify-between px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                    <span className="font-medium text-gray-800">{i + 1}. {f}</span>
                    <button
                      onPointerDown={() => setFencers(fencers.filter((_, idx) => idx !== i))}
                      className="w-8 h-8 flex items-center justify-center rounded-full text-gray-400 active:bg-red-50 active:text-red-500 text-xl"
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}

            {fencers.length < 2 && (
              <p className="text-center text-xs text-gray-400">Add at least 2 fencers to continue</p>
            )}

            <button
              className="w-full py-3.5 bg-blue-600 text-white rounded-2xl font-bold disabled:opacity-40 active:bg-blue-700 text-base"
              onClick={() => { generatePoules(); setTab('poules'); }}
              disabled={fencers.length < 2}
            >
              Generate Poules →
            </button>
          </div>
        )}

        {/* ── POULES ── */}
        {tab === 'poules' && (
          <div className="space-y-5">
            {poules.length === 0
              ? <p className="text-center text-gray-400 py-12">No poules yet — register fencers first.</p>
              : poules.map((poule: any, pidx: number) => (
                <div key={pidx} className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                  <div className="bg-gray-50 border-b border-gray-200 px-4 py-2.5">
                    <h2 className="font-bold text-gray-700">Poule {poule.id}</h2>
                  </div>

                  {poule.fencers.map((a: any, i: number) =>
                    poule.fencers.slice(i + 1).map((b: any) => {
                      const keyA = `${a.name}-${b.name}-A`;
                      const keyB = `${a.name}-${b.name}-B`;
                      const isActive = activeMatch?.keyA === keyA;
                      const matchId: Match = { pouleIdx: pidx, keyA, keyB };

                      return (
                        <div key={keyA}
                          className={`px-4 py-3 border-b border-gray-100 last:border-b-0 transition-colors
                            ${isActive ? 'bg-blue-50' : ''}`}
                          onClick={() => setActiveMatch(isActive ? null : matchId)}
                        >
                          {/* Match header */}
                          <div className="flex items-center justify-between mb-3">
                            <span className="font-semibold text-gray-800 text-sm">{a.name}</span>
                            <span className="text-xs font-bold text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">VS</span>
                            <span className="font-semibold text-gray-800 text-sm">{b.name}</span>
                          </div>

                          {/* Radial score selectors */}
                          <div className="space-y-2">
                            <div>
                              <p className="text-xs text-gray-500 mb-1 font-medium">{a.name}</p>
                              <ScoreSelector
                                value={pouleScores[keyA] ?? -1}
                                onChange={v => setPouleScores(prev => ({ ...prev, [keyA]: v }))}
                              />
                            </div>
                            <div>
                              <p className="text-xs text-gray-500 mb-1 font-medium">{b.name}</p>
                              <ScoreSelector
                                value={pouleScores[keyB] ?? -1}
                                onChange={v => setPouleScores(prev => ({ ...prev, [keyB]: v }))}
                              />
                            </div>
                          </div>

                          {/* Voice target indicator */}
                          {voiceEnabled && isActive && (
                            <div className="mt-2">
                              <Pill color="blue">🎤 Active for voice scoring</Pill>
                            </div>
                          )}
                        </div>
                      );
                    })
                  )}
                </div>
              ))
            }

            {poules.length > 0 && (
              <button
                className="w-full py-3.5 bg-blue-600 text-white rounded-2xl font-bold active:bg-blue-700 text-base"
                onClick={() => { calculateSeeding(); setTab('seeding'); }}
              >
                Calculate Seeding →
              </button>
            )}
          </div>
        )}

        {/* ── SEEDING ── */}
        {tab === 'seeding' && (
          <div className="space-y-4">
            {seeding.length === 0 ? (
              <div className="text-center py-12 space-y-4">
                <p className="text-gray-400">No seeding yet — enter poule scores first.</p>
                <button
                  className="px-6 py-3 bg-blue-600 text-white rounded-2xl font-bold active:bg-blue-700"
                  onClick={calculateSeeding}
                >
                  Generate from Poules
                </button>
              </div>
            ) : (
              <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                <div className="bg-gray-50 border-b border-gray-200 px-4 py-2.5">
                  <h2 className="font-bold text-gray-700">Final Seeding</h2>
                </div>
                {seeding.map((f, i) => {
                  const p = profiles[f.name];
                  const fr = p?.ratings[WEAPON];
                  const tier = p ? getTier(fr!.rating, p.currentTier) : TIERS[1];
                  return (
                    <div key={i}
                      className={`flex items-center gap-3 px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                      <span className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold flex-shrink-0
                        ${i === 0 ? 'bg-yellow-400 text-yellow-900'
                          : i === 1 ? 'bg-gray-300 text-gray-700'
                          : i === 2 ? 'bg-amber-600 text-white'
                          : 'bg-gray-100 text-gray-500'}`}>
                        {i + 1}
                      </span>
                      <span className="text-lg leading-none" title={tier.name}>{tier.emoji}</span>
                      <span className="flex-1 font-semibold text-gray-800">{f.name}</span>
                      {fr && <span className="text-xs font-mono font-bold" style={{ color: tier.color }}>{displayRating(fr)}</span>}
                      <span className="text-xs text-gray-500 tabular-nums">W:{f.wins}</span>
                      <span className={`text-xs tabular-nums font-medium ${f.indicator >= 0 ? 'text-green-600' : 'text-red-500'}`}>
                        {f.indicator > 0 ? '+' : ''}{f.indicator}
                      </span>
                      {p && <span className="text-xs text-yellow-600 font-mono">{Math.round(p.fencBalance)}✦</span>}
                    </div>
                  );
                })}
              </div>
            )}

            {seeding.length > 0 && (
              <button
                className="w-full py-3.5 bg-blue-600 text-white rounded-2xl font-bold active:bg-blue-700 text-base"
                onClick={() => setTab('tableau')}
              >
                View Tableau →
              </button>
            )}
          </div>
        )}

        {/* ── TABLEAU ── */}
        {tab === 'tableau' && (
          <div className="space-y-4">
            {tableau.length === 0
              ? <p className="text-center text-gray-400 py-12">No tableau yet — complete seeding first.</p>
              : (
                <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                  <div className="bg-gray-50 border-b border-gray-200 px-4 py-2.5">
                    <h2 className="font-bold text-gray-700">Direct Elimination</h2>
                  </div>
                  {tableau.map(([a, b], i) => {
                    if (!a || !b) return (
                      <div key={i} className={`flex items-center gap-3 px-4 py-4 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                        <span className="w-6 text-center text-xs text-gray-400 font-mono">{i + 1}</span>
                        <span className="font-semibold text-sm text-gray-800">{a?.name || 'BYE'}</span>
                        <span className="text-xs text-gray-300 font-bold">vs</span>
                        <span className="text-sm text-gray-400 italic">BYE</span>
                      </div>
                    );
                    const pa = profiles[a.name], pb = profiles[b.name];
                    const ra = pa?.ratings[WEAPON].rating ?? 1200;
                    const rb = pb?.ratings[WEAPON].rating ?? 1200;
                    const ba = pa?.ratings[WEAPON].boutsPlayed ?? 0;
                    const bb = pb?.ratings[WEAPON].boutsPlayed ?? 0;
                    const pWinA = +(1 / (1 + Math.pow(10, (rb - ra) / 400)) * 100).toFixed(0);
                    const Ka = Math.max(16, 64 - ba * 2);
                    const Kb = Math.max(16, 64 - bb * 2);
                    const gainA = Math.round(Ka * (1 - pWinA / 100));
                    const gainB = Math.round(Kb * (pWinA / 100));
                    const fencA = computeFENCReward(ra, rb, true);
                    const fencB = computeFENCReward(rb, ra, true);
                    const tierA = pa ? getTier(ra, pa.currentTier) : TIERS[1];
                    const tierB = pb ? getTier(rb, pb.currentTier) : TIERS[1];
                    return (
                      <div key={i} className={`px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                        <div className="flex items-center gap-2 mb-2">
                          <span className="w-5 text-center text-xs text-gray-400 font-mono">{i + 1}</span>
                          <span className="text-base">{tierA.emoji}</span>
                          <span className="font-semibold text-sm text-gray-800 flex-1">{a.name}</span>
                          <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-blue-50 text-blue-700">{pWinA}%</span>
                          <span className="text-xs text-gray-300 font-bold">vs</span>
                          <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-red-50 text-red-600">{100 - pWinA}%</span>
                          <span className="font-semibold text-sm text-gray-800 flex-1 text-right">{b.name}</span>
                          <span className="text-base">{tierB.emoji}</span>
                        </div>
                        <div className="flex gap-3 text-xs text-gray-500 pl-7">
                          <span>+{gainA} ELO if win</span>
                          <span className="text-yellow-600">✦{fencA} FENC</span>
                          <span className="ml-auto">✦{fencB} FENC</span>
                          <span>+{gainB} ELO if win</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )
            }
          </div>
        )}
        {/* ── RANKINGS ── */}
        {tab === 'rankings' && (() => {
          const ranked = Object.values(profiles)
            .map(p => ({ p, rating: p.ratings[WEAPON].rating }))
            .sort((a, b) => b.rating - a.rating);
          return (
            <div className="space-y-3">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider">ELO Leaderboard</h2>
              {ranked.length === 0 && <p className="text-center text-gray-400 py-12">No rated fencers yet.</p>}
              <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                {ranked.map(({ p, rating }, i) => {
                  const fr = p.ratings[WEAPON];
                  const tier = getTier(rating, p.currentTier);
                  return (
                    <div key={p.id} className={`flex items-center gap-3 px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                      <span className="w-6 text-xs text-gray-400 font-mono text-center font-bold">{i + 1}</span>
                      <span className="text-xl">{tier.emoji}</span>
                      <div className="flex-1 min-w-0">
                        <p className="font-semibold text-gray-800 text-sm truncate">{p.name}</p>
                        <p className="text-xs font-medium" style={{ color: tier.color }}>{tier.name}</p>
                      </div>
                      <div className="text-right">
                        <p className="font-mono font-bold text-sm text-gray-800">{displayRating(fr)}</p>
                        <p className="text-xs text-yellow-600">✦ {Math.round(p.fencBalance)}</p>
                      </div>
                      <div className="text-right text-xs text-gray-400">
                        <p>{fr.boutsPlayed}b</p>
                        <p>{p.seasonPoints}sp</p>
                      </div>
                    </div>
                  );
                })}
              </div>
              <div className="bg-gray-50 rounded-2xl border border-gray-200 p-4">
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Tier Rewards</p>
                {TIERS.slice().reverse().map(t => (
                  <div key={t.name} className="flex items-center gap-2 py-1">
                    <span>{t.emoji}</span>
                    <span className="text-sm font-medium flex-1" style={{ color: t.color }}>{t.name}</span>
                    <span className="text-xs text-gray-500">{t.rewardMultiplier}× FENC</span>
                    <span className="text-xs text-gray-400">${t.minBetUSDC}–${t.maxBetUSDC}</span>
                  </div>
                ))}
              </div>
            </div>
          );
        })()}

        {/* ── WALLET ── */}
        {tab === 'wallet' && (() => {
          const allProfiles = Object.values(profiles);
          const selected = allProfiles[0]; // first profile as demo "current user"
          return (
            <div className="space-y-4">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider">Wallet — Demo Mode</h2>
              {!selected
                ? <p className="text-center text-gray-400 py-12">Register a fencer first.</p>
                : <>
                  <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4 space-y-3">
                    <div className="flex items-center gap-3">
                      <span className="text-2xl">{getTier(selected.ratings[WEAPON].rating, selected.currentTier).emoji}</span>
                      <div>
                        <p className="font-bold text-gray-800">{selected.name}</p>
                        <p className="text-xs font-medium" style={{ color: getTier(selected.ratings[WEAPON].rating, selected.currentTier).color }}>
                          {getTier(selected.ratings[WEAPON].rating, selected.currentTier).name} · {displayRating(selected.ratings[WEAPON])} ELO
                        </p>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="bg-yellow-50 rounded-xl p-3 text-center border border-yellow-100">
                        <p className="text-xs text-yellow-600 font-semibold">FENC Balance</p>
                        <p className="text-2xl font-bold text-yellow-700">✦{Math.round(selected.fencBalance)}</p>
                      </div>
                      <div className="bg-green-50 rounded-xl p-3 text-center border border-green-100">
                        <p className="text-xs text-green-600 font-semibold">USDC Balance</p>
                        <p className="text-2xl font-bold text-green-700">${selected.usdcBalance.toFixed(2)}</p>
                      </div>
                    </div>
                    <div className="bg-gray-50 rounded-xl p-3 border border-gray-200">
                      <p className="text-xs text-gray-500 mb-1">Wallet Address</p>
                      <p className="text-xs font-mono text-gray-400 break-all">
                        {selected.walletAddress ?? 'Not connected — Base mainnet (demo mode)'}
                      </p>
                    </div>
                  </div>
                  <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-4">
                    <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-3">Season Stats</p>
                    <div className="space-y-2 text-sm">
                      <div className="flex justify-between"><span className="text-gray-500">Bouts played</span><span className="font-semibold">{selected.ratings[WEAPON].boutsPlayed}</span></div>
                      <div className="flex justify-between"><span className="text-gray-500">Season points</span><span className="font-semibold">{selected.seasonPoints}</span></div>
                      <div className="flex justify-between"><span className="text-gray-500">Peak ELO</span><span className="font-semibold">{selected.peakELO}</span></div>
                      <div className="flex justify-between"><span className="text-gray-500">Protected ELO</span><span className="font-semibold">{selected.protectedELO}</span></div>
                    </div>
                  </div>
                  <div className="bg-blue-50 border border-blue-200 rounded-2xl p-4">
                    <p className="text-xs font-semibold text-blue-700 mb-1">Live betting — coming soon</p>
                    <p className="text-xs text-blue-500">USDC escrow on Base mainnet via Privy embedded wallets. Payouts use ELO-adjusted parimutuel with 5% rake and 3× upset cap.</p>
                  </div>
                </>
              }
            </div>
          );
        })()}

      </main>

      {/* Bottom navigation */}
      <nav
        className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 flex"
        style={{
          maxWidth: 480, margin: '0 auto', left: 0, right: 0,
          paddingBottom: 'env(safe-area-inset-bottom)',
        }}
      >
        {tabs.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex-1 flex flex-col items-center pt-2 pb-1 text-xs font-medium transition-colors relative
              ${tab === t.id ? 'text-blue-600' : 'text-gray-400'}`}
          >
            {tab === t.id && (
              <span className="absolute top-0 left-1/2 -translate-x-1/2 w-8 h-0.5 bg-blue-600 rounded-full" />
            )}
            <span className="text-xl leading-tight">{t.icon}</span>
            <span className="mt-0.5">{t.label}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}
