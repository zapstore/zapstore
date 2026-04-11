import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';

/// Shared stack link card used in profile and user screens.
/// Shows stack name, app count badge, and padlock icon for private stacks.
class StackLinkCard extends HookConsumerWidget {
  const StackLinkCard({
    super.key,
    required this.stack,
    this.displayName,
  });

  final AppStack stack;
  final String? displayName;

  bool get _isEncrypted => stack.content.isNotEmpty;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appCount = useState<int?>(null);

    useEffect(() {
      if (_isEncrypted) {
        Future<void> decrypt() async {
          final signer = ref.read(Signer.activeSignerProvider);
          final pubkey = ref.read(Signer.activePubkeyProvider);
          if (signer == null || pubkey == null) return;

          try {
            final decrypted = await signer.nip44Decrypt(stack.content, pubkey);
            final ids = (jsonDecode(decrypted) as List).cast<String>();
            appCount.value = ids.length;
          } catch (_) {
            // Decryption failed, leave count as null
          }
        }

        decrypt();
      } else {
        // Public stack: count 'a' tags
        final count = stack.event
            .getTagSetValues('a')
            .where((id) => id.startsWith('32267:'))
            .length;
        appCount.value = count;
      }
      return null;
    }, [stack.content, stack.id]);

    final title = displayName ?? stack.name ?? stack.identifier;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
      child: InkWell(
        onTap: () => pushStack(
          context,
          stack.identifier,
          author: stack.pubkey,
          kind: stack.event.kind,
        ),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            ),
          ),
          child: Row(
            children: [
              Expanded(
                child: Row(
                  children: [
                    Flexible(
                      child: Text(
                        title,
                        style: context.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (_isEncrypted) ...[
                      const SizedBox(width: 6),
                      Icon(
                        Icons.lock,
                        size: 16,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ],
                    if (appCount.value != null && appCount.value! > 0) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.surfaceContainerHigh,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Text(
                          '${appCount.value}',
                          style: context.textTheme.labelSmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Icon(
                Icons.chevron_right,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
