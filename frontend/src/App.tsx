import { useState } from 'react';
import { PriorityPanel } from './panels/PriorityPanel';
import { SourcesPanel } from './panels/SourcesPanel';
import { ReviewPanel } from './panels/ReviewPanel';
import { GuidePanel } from './panels/GuidePanel';
import { ConfigPanel } from './panels/ConfigPanel';

const TABS = ['Priority', 'Sources', 'Match review', 'Guide', 'Configuration'] as const;
type Tab = (typeof TABS)[number];

/** The whole inspector: what the merge decided, and the two things a human may change about it. */
export function App() {
  const [tab, setTab] = useState<Tab>('Priority');

  return (
    <div className="app">
      <header>
        <h1>HouseEPG</h1>
        <p className="muted">
          The merged guide, and the little configuration that is not in the YAML file.
        </p>
      </header>

      <nav>
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            className={name === tab ? 'tab active' : 'tab'}
            onClick={() => setTab(name)}
          >
            {name}
          </button>
        ))}
      </nav>

      <main>
        {tab === 'Priority' && <PriorityPanel />}
        {tab === 'Sources' && <SourcesPanel />}
        {tab === 'Match review' && <ReviewPanel />}
        {tab === 'Guide' && <GuidePanel />}
        {tab === 'Configuration' && <ConfigPanel />}
      </main>

      <footer className="muted">
        <a href="/xmltv">/xmltv</a> is the feed the DVR reads. ADR-0002: a routine refresh never changes an
        emitted channel id.
      </footer>
    </div>
  );
}
