import React, { useState, useEffect } from 'react';

export default function TournamentAppPreview() {
  const [tab, setTab] = useState('register');
  const [fencers, setFencers] = useState<string[]>([]);
  const [inputName, setInputName] = useState('');
  const [poules, setPoules] = useState<any[]>([]);
  const [pouleScores, setPouleScores] = useState<{ [key: string]: number }>({});
  const [seeding, setSeeding] = useState<any[]>([]);
  const [tableau, setTableau] = useState<any[]>([]);

  const addFencer = () => {
    if (inputName.trim() && !fencers.includes(inputName.trim())) {
      setFencers([...fencers, inputName.trim()]);
      setInputName('');
    }
  };

  const generatePoules = () => {
    const shuffled = [...fencers].sort(() => Math.random() - 0.5);
    const mid = Math.ceil(shuffled.length / 2);
    const generatedPoules = [
      { id: 1, fencers: shuffled.slice(0, mid).map(name => ({ name })) },
      { id: 2, fencers: shuffled.slice(mid).map(name => ({ name })) },
    ];
    setPoules(generatedPoules);
  };

  const calculateSeeding = () => {
    const stats: { [key: string]: { wins: number; indicator: number } } = {};
    poules.forEach(poule => {
      poule.fencers.forEach((fa: any, i: number) => {
        poule.fencers.slice(i + 1).forEach((fb: any) => {
          const keyA = `${fa.name}-${fb.name}-A`;
          const keyB = `${fa.name}-${fb.name}-B`;
          const aScore = pouleScores[keyA] || 0;
          const bScore = pouleScores[keyB] || 0;

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
  };

  return (
    <div className="p-4">
      <h1 className="text-xl font-bold mb-4">Tournament App Preview</h1>
      {['register', 'poules', 'seeding', 'tableau'].map(t => (
        <button key={t} onClick={() => setTab(t)}
          className={`px-3 py-1 rounded ${tab === t ? 'bg-blue-500 text-white' : 'bg-gray-200'} mr-2`}>
          {t.charAt(0).toUpperCase() + t.slice(1)}
        </button>
      ))}

      {tab === 'register' && (
        <div>
          <input className="border px-2 py-1" value={inputName} onChange={(e) => setInputName(e.target.value)} placeholder="Fencer name" />
          <button onClick={addFencer} className="ml-2 px-3 py-1 bg-green-600 text-white rounded">Add</button>
          <ul className="list-disc ml-4 mt-2">{fencers.map((f, i) => <li key={i}>{f}</li>)}</ul>
          <button className="mt-4 px-4 py-2 bg-blue-600 text-white rounded" onClick={generatePoules} disabled={fencers.length < 2}>Generate Poules</button>
        </div>
      )}

      {tab === 'poules' && (
        <div>
          {poules.map((poule: any, idx: number) => (
            <div key={idx} className="mb-4">
              <h2 className="font-semibold">Poule {poule.id}</h2>
              <table className="table-auto border">
                <thead><tr><th>A</th><th>B</th><th>Score A</th><th>Score B</th></tr></thead>
                <tbody>
                  {poule.fencers.map((a: any, i: number) =>
                    poule.fencers.slice(i + 1).map((b: any) => {
                      const keyA = `${a.name}-${b.name}-A`;
                      const keyB = `${a.name}-${b.name}-B`;
                      return (
                        <tr key={keyA}>
                          <td>{a.name}</td>
                          <td>{b.name}</td>
                          <td><input type="number" min={0} max={5} className="w-16 border"
                            value={pouleScores[keyA] || ''} onChange={e => setPouleScores({ ...pouleScores, [keyA]: +e.target.value })} /></td>
                          <td><input type="number" min={0} max={5} className="w-16 border"
                            value={pouleScores[keyB] || ''} onChange={e => setPouleScores({ ...pouleScores, [keyB]: +e.target.value })} /></td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}

      {tab === 'seeding' && (
        <div>
          <button className="mb-2 bg-blue-600 text-white px-3 py-1 rounded" onClick={calculateSeeding}>Generate from Poules</button>
          <ol className="ml-6">
            {seeding.map((f, i) => <li key={i}>{f.name} – Wins: {f.wins}, Indicator: {f.indicator}</li>)}
          </ol>
        </div>
      )}

      {tab === 'tableau' && (
        <ul className="list-disc ml-4">
          {tableau.map(([a, b], i) => (
            <li key={i}>{a?.name || 'TBD'} vs {b?.name || 'TBD'}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
