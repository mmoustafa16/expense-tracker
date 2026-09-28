"""Offline Unigram tokenizer matching potion-multilingual-128M.

The normalizer is SentencePiece's precompiled charsmap. The model is
Unigram with a Metaspace pre-tokenizer (▁, always prepended). This module
is the reference for the Kotlin runtime.
"""

from __future__ import annotations

import base64
import json
import re
import unicodedata
from dataclasses import dataclass

META = "\u2581"


def graphemes(text: str) -> list[str]:
    clusters: list[str] = []
    current = ""
    for char in text:
        if not current:
            current = char
            continue
        joined = current + char
        if unicodedata.combining(char) or _is_extend(char):
            current = joined
        else:
            clusters.append(current)
            current = char
    if current:
        clusters.append(current)
    return clusters


def _is_extend(char: str) -> bool:
    # Combining marks and a few emoji modifiers. Arabic tashkeel is Mn/Me.
    category = unicodedata.category(char)
    return category in {"Mn", "Mc", "Me"}


class CharsMap:
    def __init__(self, blob: bytes):
        trie_bytes = int.from_bytes(blob[:4], "little")
        count = trie_bytes // 4
        offset = 4
        units = []
        for _ in range(count):
            units.append(int.from_bytes(blob[offset : offset + 4], "little"))
            offset += 4
        self.units = units
        # Replacement strings are addressed by byte offset into this UTF-8 blob.
        self.normalized = blob[offset:]

    def transform(self, chunk: str) -> str | None:
        results = self._prefixes(chunk.encode("utf-8"))
        if not results:
            return None
        # SentencePiece keeps the longest matching prefix.
        index = results[-1]
        end = index
        raw = self.normalized
        while end < len(raw) and raw[end] != 0:
            end += 1
        return raw[index:end].decode("utf-8")

    def _prefixes(self, key: bytes) -> list[int]:
        units = self.units
        node = 0
        unit = units[node]
        node ^= _offset(unit)
        found = []
        for byte in key:
            if byte == 0:
                break
            node ^= byte
            unit = units[node]
            if _label(unit) != byte:
                return found
            node ^= _offset(unit)
            if _has_leaf(unit):
                found.append(_value(units[node]))
        return found

    def normalize(self, text: str) -> str:
        out = []
        for cluster in graphemes(text):
            if len(cluster.encode("utf-8")) < 6:
                replaced = self.transform(cluster)
                if replaced is not None:
                    out.append(replaced)
                    continue
            index = 0
            for char in cluster:
                size = len(char.encode("utf-8"))
                part = cluster[index : index + size]
                index += size
                replaced = self.transform(part)
                out.append(char if replaced is None else replaced)
        return "".join(out)


def _has_leaf(unit: int) -> bool:
    return ((unit >> 8) & 1) == 1


def _value(unit: int) -> int:
    return unit & ((1 << 31) - 1)


def _label(unit: int) -> int:
    return unit & ((1 << 31) | 0xFF)


def _offset(unit: int) -> int:
    return (unit >> 10) << ((unit & (1 << 9)) >> 6)


@dataclass
class TrieNode:
    children: dict[str, TrieNode]
    token_id: int | None = None
    score: float = 0.0


def build_trie(vocab: list[tuple[str, float]]) -> TrieNode:
    root = TrieNode(children={})
    for token_id, (token, score) in enumerate(vocab):
        node = root
        for char in token:
            node = node.children.setdefault(char, TrieNode(children={}))
        node.token_id = token_id
        node.score = float(score)
    return root


class UnigramTokenizer:
    def __init__(self, tokenizer_json: dict, allowed: set[int] | None = None):
        steps = tokenizer_json["normalizer"]["normalizers"]
        precompiled = steps[0]["normalizers"][0]
        blob = base64.b64decode(precompiled["precompiled_charsmap"])
        self.chars = CharsMap(blob)
        self.replacements: list[tuple[str, str, bool]] = []
        for step in steps:
            if step["type"] == "Sequence":
                for inner in step["normalizers"]:
                    if inner["type"] == "Replace":
                        self.replacements.append(_replacement(inner))
            elif step["type"] == "Replace":
                self.replacements.append(_replacement(step))
        strip = steps[-1]
        self.strip_left = bool(strip.get("strip_left"))
        self.strip_right = bool(strip.get("strip_right"))
        full = tokenizer_json["model"]["vocab"]
        self.unk_id = int(tokenizer_json["model"]["unk_id"])
        if allowed is None:
            self.vocab = [(token, float(score)) for token, score in full]
            self.ids = list(range(len(full)))
        else:
            self.ids = [index for index in range(len(full)) if index in allowed or index == self.unk_id]
            self.vocab = [(full[index][0], float(full[index][1])) for index in self.ids]
        self.local_to_global = self.ids
        self.unk_local = self.ids.index(self.unk_id)
        self.trie = build_trie(self.vocab)
        self.unk_score = float(full[self.unk_id][1])

    def normalize(self, text: str) -> str:
        text = self.chars.normalize(text)
        for pattern, content, is_regex in self.replacements:
            if is_regex:
                text = re.sub(pattern, content, text)
            else:
                text = text.replace(pattern, content)
        if self.strip_left or self.strip_right:
            text = text.strip()
        return text

    def metaspace(self, text: str) -> str:
        if not text:
            return ""
        return META + text.replace(" ", META)

    def token_ids(self, text: str, max_tokens: int = 512, median: int = 6) -> list[int]:
        local = self.runtime_ids(text, max_tokens=max_tokens, median=median)
        return [self.local_to_global[item] for item in local]

    def runtime_ids(self, text: str, max_tokens: int = 512, median: int = 6) -> list[int]:
        clipped = text[: max_tokens * median]
        prepared = self.metaspace(self.normalize(clipped))
        local = self._encode(prepared)
        kept = [item for item in local if self.local_to_global[item] != self.unk_id]
        return kept[:max_tokens]

    def _encode(self, text: str) -> list[int]:
        if not text:
            return []
        chars = list(text)
        size = len(chars)
        best = [float("-inf")] * (size + 1)
        best[0] = 0.0
        back_pos = [-1] * (size + 1)
        back_id = [-1] * (size + 1)
        for start in range(size):
            if best[start] == float("-inf"):
                continue
            node = self.trie
            end = start
            while end < size:
                node = node.children.get(chars[end])
                if node is None:
                    break
                end += 1
                if node.token_id is not None:
                    score = best[start] + node.score
                    if score > best[end]:
                        best[end] = score
                        back_pos[end] = start
                        back_id[end] = node.token_id
            if back_pos[start + 1] != start and best[start + 1] < best[start] + self.unk_score:
                # A single unseen character falls back to unknown.
                if end == start:
                    score = best[start] + self.unk_score
                    if score > best[start + 1]:
                        best[start + 1] = score
                        back_pos[start + 1] = start
                        back_id[start + 1] = self.unk_local
        if best[size] == float("-inf"):
            return [self.unk_local]
        cursor = size
        local_ids = []
        while cursor > 0:
            prev = back_pos[cursor]
            if prev < 0:
                break
            local_ids.append(back_id[cursor])
            cursor = prev
        local_ids.reverse()
        return local_ids


def _replacement(step: dict) -> tuple[str, str, bool]:
    pattern = step["pattern"]
    if "Regex" in pattern:
        return pattern["Regex"], step["content"], True
    return pattern["String"], step["content"], False


def load_tokenizer_json(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)
