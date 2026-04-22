import React, { useState, useEffect, useRef, useCallback } from 'react';

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

  const recognitionRef = useRef<any>(null);

  // Keep mutable refs to avoid stale closures inside recognition callbacks
  const stateRef = useRef({
    tab, fencers, activeMatch, pouleScores, poules, voiceEnabled,
    setFencers, setPouleScores, setPoules, setSeeding, setTableau, setTab, setVoiceStatus, setActiveMatch,
  });
  stateRef.current = {
    tab, fencers, activeMatch, pouleScores, poules, voiceEnabled,
    setFencers, setPouleScores, setPoules, setSeeding, setTableau, setTab, setVoiceStatus, setActiveMatch,
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
      setInputName('');
    }
  };

  const tabs = [
    { id: 'register', label: 'Register', icon: '👤' },
    { id: 'poules', label: 'Poules', icon: '⚔️' },
    { id: 'seeding', label: 'Seeding', icon: '📊' },
    { id: 'tableau', label: 'Tableau', icon: '🏆' },
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
                {seeding.map((f, i) => (
                  <div key={i}
                    className={`flex items-center gap-3 px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                    <span className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold flex-shrink-0
                      ${i === 0 ? 'bg-yellow-400 text-yellow-900'
                        : i === 1 ? 'bg-gray-300 text-gray-700'
                        : i === 2 ? 'bg-amber-600 text-white'
                        : 'bg-gray-100 text-gray-500'}`}>
                      {i + 1}
                    </span>
                    <span className="flex-1 font-semibold text-gray-800">{f.name}</span>
                    <span className="text-xs text-gray-500 tabular-nums">W: {f.wins}</span>
                    <span className={`text-xs tabular-nums font-medium ${f.indicator >= 0 ? 'text-green-600' : 'text-red-500'}`}>
                      {f.indicator > 0 ? '+' : ''}{f.indicator}
                    </span>
                  </div>
                ))}
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
                  {tableau.map(([a, b], i) => (
                    <div key={i}
                      className={`flex items-center gap-3 px-4 py-4 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                      <span className="w-6 text-center text-xs text-gray-400 font-mono font-bold">{i + 1}</span>
                      <div className="flex-1 flex items-center justify-between gap-3">
                        <span className={`font-semibold text-sm ${a ? 'text-gray-800' : 'text-gray-400 italic'}`}>
                          {a?.name || 'BYE'}
                        </span>
                        <span className="text-xs font-extrabold text-gray-300">vs</span>
                        <span className={`font-semibold text-sm text-right ${b ? 'text-gray-800' : 'text-gray-400 italic'}`}>
                          {b?.name || 'BYE'}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )
            }
          </div>
        )}
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
