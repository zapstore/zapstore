import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/screens/app_detail_screen.dart';

import '../main.data.dart';

const kAndroidMimeType = 'application/vnd.android.package-archive';

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchState = useState<String?>(null);
    final labelState = useState<DataRequestLabel?>(null);

    final value = ref.fileMetadata.watchAll(remote: false);
    final artifacts = value.model
        .where((a) => a.release.isPresent && a.mimeType == kAndroidMimeType)
        .where((a) => searchState.value != null
            ? a.release.value!.title
                .toLowerCase()
                .contains(searchState.value!.toLowerCase())
            : true)
        .toList();

    return Padding(
      padding: const EdgeInsets.all(18.0),
      child: Column(
        children: [
          SearchBar(
            elevation: const MaterialStatePropertyAll(2.2),
            onSubmitted: (query) async {
              searchState.value = query;

              await ref.releases.findAll(params: {
                'since': 1708658457,
              });
              await ref.fileMetadata.findAll(
                params: {
                  // 'kinds': {1063},
                  // 'limit': 20,
                  // 'search': query,
                  '#m': [kAndroidMimeType],
                  'since': 1708658457,
                },
                label: labelState.value,
              );
            },
          ),
          const SizedBox(height: 20),
          Expanded(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: artifacts.length,
              itemBuilder: (context, index) {
                final event = artifacts[index];
                return CardWidget(fileMetadata: event);
              },
            ),
          ),
        ],
      ),
    );
  }
}
