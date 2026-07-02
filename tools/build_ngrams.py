#!/usr/bin/env python3
"""Build BawirBoard next-word prediction data from a Karakalpak corpus.

Inputs:
  1. kaa_uz sentence pairs (TSV: karakalpak<TAB>uzbek), Latin script with
     occasional stray Cyrillic (transliterated with the YCUKEN mapping below)
  2. existing data.jsonl ({"w": ..., "s": [...]}) whose ranked suggestions
     are merged in as pseudo-counts

Outputs (JSONL, sorted case-insensitively by key):
  data_v2.jsonl   {"w": word, "s": [[next, weight], ...]}   bigram model, top 8
  trigrams.jsonl  {"w": "w1 w2", "s": [[next, weight], ...]} two-word context
  starters.json   [[word, weight], ...]                      sentence starters
  words.tsv       word<TAB>count, for prefix completion      unigram model
  data.jsonl      {"w": word, "s": [next, ...]}              legacy top-3 format

Usage:
  build_ngrams.py <pairs.tsv> <old_data.jsonl> <removed_words.txt> <outdir>
"""
import collections
import json
import re
import sys

# Cyrillic -> Latin (2016 Karakalpak orthography), YCUKEN keyboard order.
# Uppercase of dotless "ı" is "Í"; soft/hard signs are dropped.
CYR2LAT = {
    'Й': 'Y', 'Ц': 'C', 'У': 'U', 'К': 'K', 'Е': 'E', 'Н': 'N', 'Г': 'G',
    'Ш': 'Sh', 'Щ': 'Shsh', 'З': 'Z', 'Х': 'X', 'Ф': 'F', 'Ы': 'Í',
    'В': 'V', 'А': 'A', 'П': 'P', 'Р': 'R', 'О': 'O', 'Л': 'L', 'Д': 'D',
    'Ж': 'J', 'Э': 'E', 'Я': 'Ya', 'Ч': 'Ch', 'С': 'S', 'М': 'M', 'И': 'I',
    'Т': 'T', 'Ь': '', 'Б': 'B', 'Ю': 'Yu',
    'й': 'y', 'ц': 'c', 'у': 'u', 'к': 'k', 'е': 'e', 'н': 'n', 'г': 'g',
    'ш': 'sh', 'щ': 'shsh', 'з': 'z', 'х': 'x', 'ф': 'f', 'ы': 'ı',
    'в': 'v', 'а': 'a', 'п': 'p', 'р': 'r', 'о': 'o', 'л': 'l', 'д': 'd',
    'ж': 'j', 'э': 'e', 'я': 'ya', 'ч': 'ch', 'с': 's', 'м': 'm', 'и': 'i',
    'т': 't', 'ь': '', 'б': 'b', 'ю': 'yu',
    'Ү': 'Ú', 'Ў': 'W', 'Қ': 'Q', 'Ё': 'Yo', 'Ң': 'Ń', 'Ғ': 'Ǵ', 'Ҳ': 'H',
    'Ә': 'Á', 'Ө': 'Ó', 'Ъ': '',
    'ү': 'ú', 'ў': 'w', 'қ': 'q', 'ё': 'yo', 'ң': 'ń', 'ғ': 'ǵ', 'ҳ': 'h',
    'ә': 'á', 'ө': 'ó', 'ъ': '',
    'Ѳ': 'Ó', 'ѳ': 'ó',
}
CYR_RE = re.compile('[' + ''.join(map(re.escape, CYR2LAT)) + ']')

LAT_UP = 'A-ZÁÍÓÚŃǴ'
LAT_LO = 'a-záóúıńǵ'
WORD_RE = re.compile(f'[{LAT_UP}{LAT_LO}]+')
SENT_SPLIT = re.compile(r'[.!?…;:]+')
MIDWORD_UPPER = re.compile(f'[{LAT_LO}][{LAT_UP}]')

UZBEK_PATTERNS = [re.compile(p) for p in (
    r'ning$', r'ningi', r'lari$', r'larini$', r'lariga$', r'larida$',
    r'larni$', r'larga$', r'moqda$', r'moq$', r'digan', r'dagi$',
    r'chilik', r'chilar', r'yotgan', r'ayotir',
)]

# markers of an Uzbek sentence misaligned into the Karakalpak column:
# whole sentence is skipped when one is present
UZBEK_MARKER_WORDS = {'va', 'uchun', 'bilan', 'hamda', 'nomidagi', 'uning',
                      'ushbu', 'boyicha', 'davlat', 'tomonidan', 'emas'}
UZBEK_MARKER_CHARS = ('ʻ', 'ʼ', 'oʻ', 'gʻ')

def kk_lower(w):
    # str.lower() maps Í -> í, but lowercase of Í is dotless ı in this orthography
    return w.replace('Í', 'ı').lower()


def transliterate(text):
    return CYR_RE.sub(lambda m: CYR2LAT[m.group(0)], text)


def is_junk(token, junk_set):
    lw = kk_lower(token)
    if token in junk_set or lw in junk_set:
        return True
    if len(token) < 2 or len(lw) > 28:
        return True
    if MIDWORD_UPPER.search(token):        # glued words
        return True
    if re.search(r'(.)\1\1', lw):          # triple letters
        return True
    return any(p.search(lw) for p in UZBEK_PATTERNS)


def normalize_token(token, lowercase_vocab, sentence_initial):
    """Fold heading ALL-CAPS and sentence-initial capitals down to the
    lowercase form when that form is otherwise attested. Short all-caps
    tokens without Í are kept as acronyms."""
    if token.isupper() and (len(token) > 4 or 'Í' in token):
        return kk_lower(token)
    if sentence_initial and token[:1].isupper():
        if kk_lower(token) in lowercase_vocab:
            return kk_lower(token[0]) + token[1:]
    return token


def main(pairs_path, old_data_path, removed_path, outdir):
    junk = set()
    with open(removed_path, encoding='utf-8') as f:
        for line in f:
            junk.add(line.split('\t')[0].strip())

    # ---- pass 1: collect sentences from the Karakalpak column ----
    sentences = []
    with open(pairs_path, encoding='utf-8') as f:
        for line in f:
            kaa = line.split('\t')[0]
            kaa = transliterate(kaa)
            if any(c in kaa for c in UZBEK_MARKER_CHARS):
                continue
            for sent in SENT_SPLIT.split(kaa):
                toks = WORD_RE.findall(sent)
                if toks and not (set(t.lower() for t in toks)
                                 & UZBEK_MARKER_WORDS):
                    sentences.append(toks)

    # lowercase vocabulary of words seen in non-initial, non-allcaps position
    lowercase_vocab = set()
    for toks in sentences:
        for i, t in enumerate(toks):
            if i > 0 and not t[:1].isupper():
                lowercase_vocab.add(kk_lower(t))

    # ---- pass 2: normalize case, filter junk, count n-grams ----
    uni = collections.Counter()
    bi = collections.Counter()
    tri = collections.Counter()
    starters = collections.Counter()
    for toks in sentences:
        norm = []
        for i, t in enumerate(toks):
            t = normalize_token(t, lowercase_vocab, sentence_initial=(i == 0))
            norm.append(None if is_junk(t, junk) else t)
        if norm and norm[0]:
            starters[norm[0]] += 1
        for i, t in enumerate(norm):
            if t is None:
                continue
            uni[t] += 1
            if i + 1 < len(norm) and norm[i + 1]:
                bi[(t, norm[i + 1])] += 1
                if i + 2 < len(norm) and norm[i + 2]:
                    tri[(t, norm[i + 1], norm[i + 2])] += 1

    corpus_tokens = sum(uni.values())

    # ---- blend with the old ranked dictionary ----
    # Corpus counts are sqrt-damped so this (domain-specific) corpus refines
    # rather than overwhelms the old general dictionary; old ranks 1/2/3 get
    # fixed bonuses on the same scale. score = round(10*sqrt(count)) + bonus.
    OLD_RANK_BONUS = (120, 80, 50)
    by_word = collections.defaultdict(collections.Counter)
    for (a, b), c in bi.items():
        by_word[a][b] = round(10 * c ** 0.5)
    with open(old_data_path, encoding='utf-8') as f:
        for line in f:
            o = json.loads(line)
            if o['w'] in junk:
                continue
            for rank, s in enumerate(o['s'][:3]):
                if s not in junk:
                    by_word[o['w']][s] += OLD_RANK_BONUS[rank]

    # ---- write outputs ----
    def ranked(counter_items, limit):
        top = sorted(counter_items, key=lambda kv: (-kv[1], kv[0]))
        out, seen = [], set()
        for w, c in top:                 # collapse case-duplicates
            lw = kk_lower(w)
            if lw not in seen:
                seen.add(lw)
                out.append([w, c])
            if len(out) == limit:
                break
        return out

    with open(f'{outdir}/data_v2.jsonl', 'w', encoding='utf-8') as f:
        for w in sorted(by_word, key=kk_lower):
            f.write(json.dumps({'w': w, 's': ranked(by_word[w].items(), 8)},
                               ensure_ascii=False) + '\n')

    # legacy format for the current app: plain top-3 lists
    with open(f'{outdir}/data.jsonl', 'w', encoding='utf-8') as f:
        for w in sorted(by_word, key=kk_lower):
            f.write(json.dumps(
                {'w': w, 's': [x[0] for x in ranked(by_word[w].items(), 3)]},
                ensure_ascii=False) + '\n')

    # trigrams: only contexts with real evidence (count >= 2) to bound size
    by_ctx = collections.defaultdict(collections.Counter)
    for (a, b, c), n in tri.items():
        if n >= 2:
            by_ctx[f'{a} {b}'][c] = n
    with open(f'{outdir}/trigrams.jsonl', 'w', encoding='utf-8') as f:
        for ctx in sorted(by_ctx, key=kk_lower):
            f.write(json.dumps({'w': ctx, 's': ranked(by_ctx[ctx].items(), 5)},
                               ensure_ascii=False) + '\n')

    with open(f'{outdir}/starters.json', 'w', encoding='utf-8') as f:
        json.dump(ranked(((w, c) for w, c in starters.items() if c >= 3), 50),
                  f, ensure_ascii=False)

    # unigram list for prefix completion: corpus counts, plus every old
    # dictionary word (and its suggestions) attested with count 1
    for w in by_word:
        if ' ' not in w:
            uni[w] += 1
    for sugs in by_word.values():
        for s in sugs:
            uni[s] += 1
    with open(f'{outdir}/words.tsv', 'w', encoding='utf-8') as f:
        for w in sorted(uni, key=lambda x: (kk_lower(x), x)):
            f.write(f'{w}\t{uni[w]}\n')

    print(f'sentences: {len(sentences)}, corpus tokens: {corpus_tokens}')
    print(f'vocabulary: {len(uni)}, bigram keys: {len(by_word)}, '
          f'trigram contexts: {len(by_ctx)}')


if __name__ == '__main__':
    main(*sys.argv[1:5])
