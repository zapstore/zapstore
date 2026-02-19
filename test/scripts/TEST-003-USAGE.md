# TEST-003 Script Usage

Script: `test/scripts/run-test-003.sh`

## Quick Start

```bash
cd /Users/hvmelo/Developer/codecode/Zapstore/zapstore
adb devices
./test/scripts/run-test-003.sh --device <DEVICE_ID> --apps 2
```

## Device Selection

- List devices:

```bash
adb devices
```

- Pass the device with `--device`:

```bash
./test/scripts/run-test-003.sh --device RQCT1029N9J --apps 2
```

## Stage-Based Execution

Stages:

- `0` clean_state
- `1` auth
- `2` install_old
- `3` update_all
- `4` post_verify

Run examples:

- Install old versions + update + verify:

```bash
./test/scripts/run-test-003.sh --device RQCT1029N9J --apps 2 --from 2 --to 4
```

- Only update-all + post verification (when apps are already prepared):

```bash
./test/scripts/run-test-003.sh --device RQCT1029N9J --apps 2 --from 3 --to 4
```

- Only old-version install:

```bash
./test/scripts/run-test-003.sh --device RQCT1029N9J --apps 2 --from 2 --to 2
```

## Help

```bash
./test/scripts/run-test-003.sh --help
```

## Notes

- The script enforces a minimum of 2 apps (`--apps 2`) for `Update All` validation.
- Reports are written to `test/runs/`.
