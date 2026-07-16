const _cdnHost = 'cdn.zapstore.dev';

/// CDN image variants supported by `cdn.zapstore.dev`.
enum CdnImageVariant {
  icon,
  iconsm,
  thumbsm,
  thumblg,
}

/// Add a CDN image variant only for Zapstore's CDN.
///
/// Non-CDN hosts, null/empty values, and unparseable URLs are returned unchanged.
String? getCdnImageUrl(String? imageUrl, CdnImageVariant variant) {
  if (imageUrl == null || imageUrl.isEmpty) return imageUrl;

  final uri = Uri.tryParse(imageUrl);
  if (uri == null || uri.host != _cdnHost) return imageUrl;

  final params = Map<String, String>.from(uri.queryParameters);
  params['class'] = variant.name;
  return uri.replace(queryParameters: params).toString();
}
