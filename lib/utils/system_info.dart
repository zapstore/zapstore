import 'package:android_package_manager/android_package_manager.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:system_info2/system_info2.dart';
import 'package:zapstore/main.dart';

final deviceInfoPlugin = DeviceInfoPlugin();
final packageManager = AndroidPackageManager();

final systemInfoProvider = FutureProvider((ref) async {
  final zsInfo =
      await packageManager.getPackageInfo(packageName: 'store.zap.app');
  return SystemInfo(
    androidInfo: await deviceInfoPlugin.androidInfo,
    zsInfo: zsInfo!,
  );
});

class SystemInfo {
  final AndroidDeviceInfo androidInfo;
  final PackageInfo zsInfo;

  SystemInfo({required this.androidInfo, required this.zsInfo});

  @override
  String toString() {
    return '''
Version name: ${zsInfo.versionName}
Version code: ${zsInfo.versionCode}
DB version: $kDbVersion
SDK version: ${androidInfo.version.sdkInt}
Device: ${androidInfo.brand} ${androidInfo.device} (${androidInfo.hardware})
Display: ${androidInfo.display}
Model: ${androidInfo.manufacturer} ${androidInfo.model} ${androidInfo.product}
Host: ${androidInfo.host}
Supported ABIs: ${androidInfo.supported64BitAbis}

Free/total memory: ${SysInfo.getFreePhysicalMemory() ~/ (1024 * 1024)}/${SysInfo.getTotalPhysicalMemory() ~/ (1024 * 1024)} MB
Free/total virtual: ${SysInfo.getFreeVirtualMemory() ~/ (1024 * 1024)}/${SysInfo.getTotalVirtualMemory() ~/ (1024 * 1024)} MB
Low RAM device? ${androidInfo.isLowRamDevice}
''';
  }
}
