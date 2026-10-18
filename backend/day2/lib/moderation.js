// Chat moderation rules: leetspeak-normalised badword masking and flood detection (pure functions).
const BAD = new Set(['badword', 'porn', 'scam', 'fuck', 'shit', 'bitch', 'asshole', 'idiotspam']);
const ZERO_WIDTH = /[​-‍⁠﻿­]/g;
const LEET = { '@': 'a', 0: 'o', 1: 'i', 3: 'e', $: 's' };
const AFFIX = /^([("'[{<]*)(.*?)([)"'\]}>.,;:!?]*)$/su;

function normalise(token, bangIsI) {
  let s = token.replace(ZERO_WIDTH, '').normalize('NFKC').replace(ZERO_WIDTH, '').toLowerCase();
  s = s.replace(/[_.\-*]/g, '').replace(/[@01$3]/g, (c) => LEET[c]);
  return bangIsI ? s.replace(/!/g, 'i') : s.replace(/!/g, '');
}

const isBad = (core) => core.length > 0 && (BAD.has(normalise(core, true)) || BAD.has(normalise(core, false)));

// Flood: 4+ identical consecutive characters (case-insensitive) or 3+ consecutive ! or ?.
const isFlood = (text) => /(.)\1{3}/isu.test(text) || /[!?]{3,}/.test(text);

// Replaces each offending whitespace-delimited token with *** and keeps the rest of the text.
function mask(text) {
  let hit = false;
  const out = text.split(/([ \t\r\n]+)/).map((part, i) => {
    if (i % 2 === 1 || part === '') return part;
    const [, pre, core, post] = AFFIX.exec(part);
    if (!isBad(core)) return part;
    hit = true;
    return `${pre}***${post}`;
  }).join('');
  return { text: out, hit };
}

// Runs a request's messages. Lists are per request, in order of first violation.
function moderate(messages) {
  const filtered = [];
  const kicked = [];
  const muted = [];
  const add = (list, v) => { if (!list.includes(v)) list.push(v); };
  for (const m of messages) {
    if (isFlood(m.text)) { add(muted, m.sender); continue; }
    const r = mask(m.text);
    if (r.hit) add(kicked, m.sender);
    filtered.push({ message_id: m.message_id, sender: m.sender, text: r.text, flagged: r.hit, reason: r.hit ? 'profanity_leetspeak' : null });
  }
  return { filtered, kicked, muted, flagged: filtered.filter((m) => m.flagged).length };
}

module.exports = { moderate, mask, isFlood, isBad };
