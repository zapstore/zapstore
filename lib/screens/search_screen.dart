import 'package:dart_nostr/dart_nostr.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/note.dart';

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchState = useState<String?>(null);
    final watcher = ref.notes
        .watchAll(remote: false, params: {'search': searchState.value});
    return Expanded(
      child: switch (watcher) {
        DataState(isLoading: false, exception: null, model: final value) =>
          Padding(
            padding: const EdgeInsets.all(12.0),
            child: Column(
              children: [
                SearchBar(
                  elevation: MaterialStatePropertyAll(2.2),
                  onSubmitted: (query) {
                    searchState.value = query;
                    if (query.isNotEmpty) {
                      ref.notes.nostrAdapter.update(
                        [
                          NostrFilter(
                            search: query,
                            since: DateTime.now().subtract(Duration(days: 1)),
                            limit: 10,
                          )
                        ],
                      );
                    }
                  },
                ),
                Padding(
                  padding: const EdgeInsets.all(6.0),
                  child: Text(
                      'Total results: ${value.length}, search term: ${searchState.value}'),
                ),
                Expanded(
                  child: ListView.builder(
                    shrinkWrap: true,
                    itemCount: value.length,
                    itemBuilder: (context, index) {
                      final note = value[index];
                      return Padding(
                        padding: const EdgeInsets.all(8.0),
                        child: Column(
                          children: [
                            Text(note.pubkey),
                            Text(note.content),
                            // Image.network(note.),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        DataState(isLoading: true, exception: null) =>
          CircularProgressIndicator(),
        DataState(:final exception) => Text(exception.toString()),
      },
    );
  }
}
