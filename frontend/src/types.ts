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
};

export type Match = {
  source: string;
  sourceChannelId: string;
  canonicalId: string;
  confidence: string;
  state: 'AUTO' | 'CONFIRMED' | 'CORRECTED' | 'UNMATCHED';
  curated: boolean;
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
