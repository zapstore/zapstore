import 'package:async_button_builder/async_button_builder.dart';
import 'package:expansion_tile_card/expansion_tile_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_styled_toast/flutter_styled_toast.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:url_launcher/url_launcher_string.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/profile_screen.dart';

class AppDetailScreen extends HookConsumerWidget {
  final Release release;
  const AppDetailScreen({
    required this.release,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final artifacts = release.artifacts.toList();
    return ListView.builder(
      shrinkWrap: true,
      itemCount: release.artifacts.length,
      itemBuilder: (context, index) {
        final event = artifacts[index];
        return CardWidget(fileMetadata: event);
      },
    );
  }
}

class CardWidget extends HookConsumerWidget {
  final FileMetadata fileMetadata;

  const CardWidget({super.key, required this.fileMetadata});

  User? get author => fileMetadata.author.value;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentUser = ref.read(loggedInUser);
    final fetchUser = useMemoized(() => ref.users.findOne(author!.id!));
    useFuture(fetchUser);

    final isWebOfTrust = [...?currentUser?.following.toList()]
        .contains(fileMetadata.author.value);

    return ExpansionTileCard(
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(15.0),
        child: Image.network(
          fileMetadata.release.value!.image,
          width: 80,
          height: 80,
          fit: BoxFit.cover,
          errorBuilder: (context, err, _) => Text(err.toString()),
        ),
      ),
      onExpansionChanged: (_) {
        ref.users.findOne(author!.id!);
      },
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            fileMetadata.content,
            overflow: TextOverflow.ellipsis,
            maxLines: 1,
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 26),
          ),
          SizedBox(height: 10),
          Row(
            children: [
              CircleAvatar(
                backgroundImage: NetworkImage(author?.profilePicture ?? ''),
                maxRadius: 10,
              ),
              SizedBox(width: 10),
              Text(
                '${author?.name ?? author?.id}',
                style: TextStyle(fontSize: 14),
              ),
            ],
          )
        ],
      ),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 16.0,
              vertical: 8.0,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (isWebOfTrust)
                  Text(
                    'You are following this author!',
                    style: TextStyle(color: Colors.green),
                  ),
                if (!isWebOfTrust)
                  Row(
                    children: [
                      Text('Author is not in your web of trust',
                          style: TextStyle(color: Colors.red)),
                      GestureDetector(
                        onTap: () => launchUrlString(
                          'https://primal.net/p/${author?.id.toString().npub}',
                        ),
                        child: Text(
                          'see profile',
                          style: TextStyle(
                            color: Colors.blue,
                            decoration: TextDecoration.underline,
                          ),
                        ),
                      )
                    ],
                  ),
                AsyncButtonBuilder(
                  loadingWidget: const Padding(
                    padding: EdgeInsets.all(8.0),
                    child: SizedBox(
                      height: 16.0,
                      width: 16.0,
                      child: CircularProgressIndicator(
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    ),
                  ),
                  successWidget: const Padding(
                    padding: EdgeInsets.all(4.0),
                    child: Icon(
                      Icons.check,
                      color: Colors.purpleAccent,
                    ),
                  ),
                  onPressed: () async {
                    try {
                      await fileMetadata.install();
                      showToastWidget(
                          Text(
                            'Success!',
                            style: TextStyle(fontSize: 20),
                          ),
                          // ignore: use_build_context_synchronously
                          context: context,
                          position: StyledToastPosition.center,
                          animation: StyledToastAnimation.scale,
                          reverseAnimation: StyledToastAnimation.fade,
                          duration: Duration(seconds: 4),
                          animDuration: Duration(seconds: 1),
                          curve: Curves.elasticOut,
                          reverseCurve: Curves.linear);
                    } on Exception catch (e) {
                      showToastWidget(
                          Text(
                            'Error! $e',
                            style: TextStyle(fontSize: 20),
                          ),
                          // ignore: use_build_context_synchronously
                          context: context,
                          position: StyledToastPosition.center,
                          animation: StyledToastAnimation.scale,
                          reverseAnimation: StyledToastAnimation.fade,
                          duration: Duration(seconds: 4),
                          animDuration: Duration(seconds: 1),
                          curve: Curves.elasticOut,
                          reverseCurve: Curves.linear);
                    }
                  },
                  loadingSwitchInCurve: Curves.bounceInOut,
                  loadingTransitionBuilder: (child, animation) {
                    return SlideTransition(
                      position: Tween<Offset>(
                        begin: const Offset(0, 1.0),
                        end: const Offset(0, 0),
                      ).animate(animation),
                      child: child,
                    );
                  },
                  builder: (context, child, callback, state) {
                    return Material(
                      color: state.maybeWhen(
                        success: () => Colors.purple[100],
                        orElse: () => Colors.black12,
                      ),
                      // This prevents the loading indicator showing below the
                      // button
                      clipBehavior: Clip.hardEdge,
                      shape: const StadiumBorder(),
                      child: InkWell(
                        onTap: callback,
                        child: child,
                      ),
                    );
                  },
                  child: Padding(
                    padding: const EdgeInsets.all(12.0),
                    child: const Icon(Icons.download),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
