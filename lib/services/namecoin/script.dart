import 'dart:convert';
import 'dart:typed_data';

import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart' show sha256;

/// Namecoin script opcodes used by the name-index script and
/// `NAME_UPDATE` outputs. Matches the ElectrumX Namecoin fork
/// (`electrumx/lib/coins.py`) and the canonical Go reference at
/// `mstrofnone/nostrlib-nip05-namecoin`.
const int opNameUpdate = 0x53; // OP_3, repurposed by Namecoin as OP_NAME_UPDATE
const int op2Drop = 0x6d;
const int opDrop = 0x75;
const int opReturn = 0x6a;
const int opPushData1 = 0x4c;
const int opPushData2 = 0x4d;
const int opPushData4 = 0x4e;

/// Constructs the canonical script used by the Namecoin ElectrumX fork
/// to index names on-chain.
///
/// Format:
///
///     OP_NAME_UPDATE <push(name)> <push(empty)> OP_2DROP OP_DROP OP_RETURN
///
/// The resulting script's SHA-256 (reversed, hex-encoded) is the
/// scripthash queried via `blockchain.scripthash.get_history`.
Uint8List buildNameIndexScript(List<int> nameBytes) {
  final out = <int>[];
  out.add(opNameUpdate);
  _pushData(out, nameBytes);
  _pushData(out, const []);
  out
    ..add(op2Drop)
    ..add(opDrop)
    ..add(opReturn);
  return Uint8List.fromList(out);
}

/// Computes the Electrum-style scripthash: SHA-256 of the script,
/// byte-reversed, then hex-encoded. Matches the format expected by
/// `blockchain.scripthash.get_history` and friends.
String electrumScriptHash(List<int> script) {
  final digest = Uint8List.fromList(sha256.convert(script).bytes);
  // Reverse in place.
  for (var i = 0, j = digest.length - 1; i < j; i++, j--) {
    final tmp = digest[i];
    digest[i] = digest[j];
    digest[j] = tmp;
  }
  return hex.encode(digest);
}

/// A parsed Namecoin `NAME_UPDATE` output.
class NameScript {
  /// The name, e.g. `d/example`.
  final String name;

  /// The raw value, e.g. the JSON payload stored against the name.
  final String value;

  /// Creates a [NameScript] with the given [name] and [value].
  const NameScript({required this.name, required this.value});
}

/// Extracts the name and value from a `NAME_UPDATE` output script.
///
/// Layout:
///
///     OP_NAME_UPDATE <push(name)> <push(value)> OP_2DROP OP_DROP <address-script>
///
/// Only the leading push-data pair is decoded; the address script
/// portion is ignored. Returns `null` if [script] is not a
/// `NAME_UPDATE` or is malformed.
NameScript? parseNameScript(List<int> script) {
  if (script.isEmpty || script[0] != opNameUpdate) return null;

  var pos = 1;
  final nameRead = _readPushData(script, pos);
  if (nameRead == null) return null;
  pos = nameRead.next;

  final valueRead = _readPushData(script, pos);
  if (valueRead == null) return null;

  try {
    return NameScript(
      name: utf8.decode(nameRead.data, allowMalformed: true),
      value: utf8.decode(valueRead.data, allowMalformed: true),
    );
  } on FormatException {
    return null;
  }
}

/// Returns the Bitcoin-style push-data encoding of [data], appending
/// it to [out]. Matches the Go and TS references: direct push for
/// `len < 0x4c`, then `OP_PUSHDATA1` for `len <= 0xff`,
/// `OP_PUSHDATA2` for larger (little-endian).
void _pushData(List<int> out, List<int> data) {
  final n = data.length;
  if (n < opPushData1) {
    out.add(n);
  } else if (n <= 0xff) {
    out
      ..add(opPushData1)
      ..add(n);
  } else {
    out
      ..add(opPushData2)
      ..add(n & 0xff)
      ..add((n >> 8) & 0xff);
  }
  out.addAll(data);
}

class _PushRead {
  final Uint8List data;
  final int next;
  const _PushRead(this.data, this.next);
}

/// Decodes one push-data element starting at [pos] and returns the
/// payload bytes plus the next read position.
_PushRead? _readPushData(List<int> script, int pos) {
  if (pos >= script.length) return null;
  final op = script[pos];

  if (op == 0x00) {
    return _PushRead(Uint8List(0), pos + 1);
  }
  if (op < opPushData1) {
    final length = op;
    final end = pos + 1 + length;
    if (end > script.length) return null;
    return _PushRead(Uint8List.fromList(script.sublist(pos + 1, end)), end);
  }
  if (op == opPushData1) {
    if (pos + 2 > script.length) return null;
    final length = script[pos + 1];
    final end = pos + 2 + length;
    if (end > script.length) return null;
    return _PushRead(Uint8List.fromList(script.sublist(pos + 2, end)), end);
  }
  if (op == opPushData2) {
    if (pos + 3 > script.length) return null;
    final length = script[pos + 1] | (script[pos + 2] << 8);
    final end = pos + 3 + length;
    if (end > script.length) return null;
    return _PushRead(Uint8List.fromList(script.sublist(pos + 3, end)), end);
  }
  if (op == opPushData4) {
    if (pos + 5 > script.length) return null;
    final length = script[pos + 1] |
        (script[pos + 2] << 8) |
        (script[pos + 3] << 16) |
        (script[pos + 4] << 24);
    final end = pos + 5 + length;
    if (end < 0 || end > script.length) return null;
    return _PushRead(Uint8List.fromList(script.sublist(pos + 5, end)), end);
  }
  return null;
}
