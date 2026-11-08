import { describe, expect, it } from 'vitest';
import { channelLabel } from './channelLabel';
import type { Channel } from './types';

function channel(id: string, name: string): Channel {
  return { id, name, number: null, origin: 'PLAYLIST', iconUrl: null, qualityTag: null };
}

describe('a channel is named once', () => {
  it('names a channel by its name when the id already carries it', () => {
    // The provider builds the tvg-id out of the display name, so the id is a worse label than the name.
    expect(channelLabel(channel('AMC (HD).hu', 'AMC'))).toBe('AMC');
    expect(channelLabel(channel('RTL (HD) (RTL Klub).hu', 'RTL'))).toBe('RTL');
    expect(channelLabel(channel('Canal+ Action.hu', 'CANAL+ Action'))).toBe('CANAL+ Action');
  });

  it('keeps the id when it says something the name does not', () => {
    // Here the name would not tell the two apart, so the id has to stay in the text.
    expect(channelLabel(channel('channel076+.xx', 'Provider B 076+'))).toBe('Provider B 076+ (channel076+.xx)');
    expect(channelLabel(channel('RTLKlub.hu', 'Provider A RTL Klub'))).toBe('Provider A RTL Klub (RTLKlub.hu)');
  });

  it('compares without case in either direction', () => {
    expect(channelLabel(channel('fox.hu', 'FOX'))).toBe('FOX');
    expect(channelLabel(channel('FOX.hu', 'fox'))).toBe('fox');
  });

  it('falls back to the id when there is no name', () => {
    // A pass-through channel carries the source's own label, and an unnamed channel must still be pickable.
    expect(channelLabel(channel('channel_id', ''))).toBe('channel_id');
    expect(channelLabel(channel('channel_id', '   '))).toBe('channel_id');
  });

  it('leaves a channel whose name is unrelated to its id alone', () => {
    expect(channelLabel(channel('dunaworld.hu', 'Duna World / M4 Sport+'))).toBe(
      'Duna World / M4 Sport+ (dunaworld.hu)',
    );
  });

  it('keeps a name that contains its own id rather than dropping the id', () => {
    // The id is inside the name, not the other way round: the name alone still identifies the channel.
    expect(channelLabel(channel('RTL', 'RTL HD'))).toBe('RTL HD');
  });
});
