import type { Channel } from './types';

/**
 * How a channel is named in a picker.
 *
 * A registry id is the channel authority's `tvg-id` — what the feed emits (ADR-0002) — and providers
 * build it out of the display name plus a country or quality suffix, so 64 of the live registry's 132
 * ids already contain their own name. Printing `name (id)` then reads as `AMC (AMC (HD).hu)`:
 * the id repeats what the name just said, and the operator has to find the difference between two
 * halves of one string.
 *
 * Ids are also not unique labels: `RTL+.hu` and a channel literally called `RTL Plus` fold to the same
 * comparison key (ADR-0003), and a provider may declare one id twice. So the id is not dropped from the
 * UI — it stays the option's value and its title — it is only left out of the text when the name
 * already carries it.
 */
export function channelLabel(channel: Channel): string {
  const name = (channel.name ?? '').trim();
  if (name === '') {
    return channel.id;
  }
  const id = channel.id.toLowerCase();
  const shown = name.toLowerCase();
  // Either half repeating the other is enough: the id already carries the name, or the name already
  // carries the id. Both mean the pair says one thing twice.
  return shown.includes(id) || id.includes(shown) ? name : `${name} (${channel.id})`;
}
