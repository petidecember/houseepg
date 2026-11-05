/** Wire shapes the backend emits. Kept in one place so a change is a compile error, not a blank panel. */

export type PrioritySource = {
  id: string;
  name: string;
  refreshInterval: string;
};

export type ReorderResult = {
  order: string[];
  persisted: boolean;
  detail: string;
};

export type ReloadResult = {
  added: string[];
  removed: string[];
  orderChanged: boolean;
  order: string[];
};

export type ReseedPreview = {
  changesAnything: boolean;
  added: string[];
  removed: string[];
  proposed: { id: string; name: string; origin: string }[];
};

export type Health = {
  sourceId: string;
  name: string;
  state: 'OK' | 'STALE' | 'FAILED';
  programmeCount: number;
  error: string | null;
};

export type Channel = {
  id: string;
  name: string;
  number: string | null;
  origin: 'PLAYLIST' | 'PRIMARY_SOURCE' | 'PASS_THROUGH';
  iconUrl: string | null;
  qualityTag: string | null;
};

export type Match = {
  source: string;
  sourceChannelId: string;
  canonicalId: string;
  confidence: string;
  state: 'AUTO' | 'CONFIRMED' | 'CORRECTED' | 'UNMATCHED';
  curated: boolean;
  sourceChannelName: string | null;
  sourceIconUrl: string | null;
};

export type Programme = {
  start: string;
  stop: string;
  title: string;
  description: string | null;
  category: string | null;
  episode: string | null;
  source: string;
};

export type Timeline = {
  id: string;
  name: string;
  materializedAt: string | null;
  programmes: Programme[];
};

export type Registry = {
  channels: Channel[];
  matches: Match[];
};

export type Candidate = {
  id: string;
  name: string;
  iconUrl: string | null;
  qualityTag: string | null;
  confidence: string;
  score: number;
};

export type ReviewRow = {
  source: string;
  sourceChannelId: string;
  sourceChannelName: string | null;
  sourceIconUrl: string | null;
  canonicalId: string;
  confidence: string;
  state: 'AUTO' | 'CONFIRMED' | 'CORRECTED' | 'UNMATCHED';
  curated: boolean;
  candidates: Candidate[];
};

export type Review = {
  rows: ReviewRow[];
  channels: Channel[];
};

/** One row the panel is accepting: the source channel and the channel it should point at. */
export type Decision = {
  source: string;
  sourceChannelId: string;
  canonicalId: string;
};

export type AcceptResult = {
  count: number;
  dryRun: boolean;
  rows: { source: string; sourceChannelId: string; canonicalId: string }[];
};

/** One span the preview draws: a programme block, a gap, or the result's own coverage. */
export type PreviewSpan = {
  start: string;
  stop: string;
};

export type PreviewWeakness = {
  field: 'description' | 'icon' | 'category' | 'episode';
  from: string;
};

/** One provider's entry inside the preview window, with the fate the merge walk gave it. */
export type PreviewEntry = {
  start: string;
  stop: string;
  title: string | null;
  description: string | null;
  category: string | null;
  episode: string | null;
  fate: 'WON' | 'LOST' | 'EXCLUDED';
  lostTo: string | null;
  weaker: PreviewWeakness[];
};

/** One provider column: what it carried, where its data went instead, and the window it fills. */
export type PreviewColumn = {
  source: string;
  channelId: string | null;
  channelName: string | null;
  carriesElsewhere: string | null;
  entries: PreviewEntry[];
  window: { coverage: PreviewSpan[]; gaps: PreviewSpan[] };
  caseLabel: string;
};

/** One programme the feed serves once the previewed order is applied. */
export type PreviewResultEntry = {
  start: string;
  stop: string;
  title: string | null;
  description: string | null;
  category: string | null;
  episode: string | null;
  source: string;
  weaker: PreviewWeakness[];
};

/** The merge preview for one channel and one window (design §6.6). */
export type MergePreview = {
  id: string | null;
  name: string | null;
  columns: PreviewColumn[];
  result: { entries: PreviewResultEntry[]; window: { coverage: PreviewSpan[]; gaps: PreviewSpan[] } };
};

export type PreviewWindow = {
  day: string;
  from: string;
  hours: number;
};
