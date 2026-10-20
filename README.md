<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# DFRoot [DirtyFrag (CVE-2026-43284)]

DFRoot is an Android rooting tool that exploits CVE-2026-43284 (DirtyFrag) — a kernel page cache write primitive — to load a custom kernel module without requiring an unlocked bootloader. It is intentionally designed to be SU Manager agnostic and works with any KernelSU-compatible SU manager.

> [!IMPORTANT]
> Before hitting that fork button, consider making a pull request instead :)

## FAQ

**Q: Log says patching failed**

A: A patch failure alone does not establish the cause. Save the complete log and check the preceding errors. Kernel mitigations can prevent the exploit, including:

  - A newer kernel containing the official mitigation
  - A manufacturer backport of the official mitigation
  - [Accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290) (typically seen on kernel 6.1)

**Q: The launcher returned successfully, but I don't have root**

A: A successful launcher exit means the launch command returned zero; it does not verify usable root. Older versions called this "SUCCESS". Check the selected SU manager's status. Turn off **Auto Soft Reboot** while investigating and save the logs before another attempt. See also [the troubleshooting discussion](https://github.com/diabl0w/DFRoot/discussions/69).

**Q: The log says the previous attempt's outcome is unknown**

A: DFRoot found an unfinished run record after the app process stopped. A reboot, process termination, or another interruption may leave such a record. It does not identify a kernel panic or prove root is available. Save the logs and collect additional diagnostics below.

**Q: I installed a module and now I can't launch root without crashing**

A: Enable **Disable KSU Modules** before the next run. DFRoot will mark installed modules disabled before launching the SU daemon, and stop startup if a module cannot be disabled. This may help with a broken module; it does not prevent failures earlier in startup or establish the cause of a crash.

**Q: I installed a module and have "Start at Boot" enabled and now I am bootlooping**

A: Follow your device manufacturer's instructions for entering Safe Mode. If you can open DFRoot in normal mode, turn off **Start at Boot** before further testing. Downloaded apps may be unavailable in Safe Mode; use Android Settings to uninstall DFRoot if needed to stop automatic startup. Uninstalling also removes its saved logs. Restore stable startup before trying again. **Start at Boot** is disabled by default.

## Saving and collecting logs

DFRoot keeps the current and previous run in private, device-protected storage, including manual and boot attempts. Open the app and choose **Save logs** to export a text snapshot through Android's file picker. The export includes the saved app output and available native bootstrap logs. It needs neither root nor storage permission.

A foreground service owns active runs independently of the app screen. If notifications are disabled, Android may show the running service only in Task Manager instead of the notification drawer. Interrupted work is not automatically restarted. **Start at Boot** makes at most one automatic attempt per boot, with a wake lock capped at two minutes; unlocking does not retry a failed attempt.

App output is limited to 128 KiB per run and bootstrap output to 64 KiB per run. Save the failed run before further attempts rotate it out. Startup stops before native work if it cannot prepare separate logs for the new attempt, preserving the previous record. Later logging failures are reported without aborting native execution or cleanup. A force-stop or abrupt reset can leave the final messages unwritten; an incomplete record is reported as an unknown outcome, not a diagnosis of the interruption.

For additional diagnostics, connect a computer with Python 3 and ADB, enable USB debugging, and authorize the computer. Capture the available DFRoot logcat messages and device metadata:

```sh
python3 tools/collect_logs.py --serial DEVICE --output dfroot-logs
```

To capture messages while reproducing a problem, start a timed capture first, then run DFRoot yourself:

```sh
python3 tools/collect_logs.py --serial DEVICE --watch --duration 120 --output dfroot-watch
```

Replace `DEVICE` with the serial shown by `adb devices`. Use `--adb PATH` if ADB is not on your PATH. Each capture creates a local session directory under `--output` (default: `out/diagnostics`). Stop a watch early with Ctrl+C to keep the partial capture.

The default capture filters logcat to DFRoot's tag and includes selected version and boot metadata. On a device with working root, add `--root` to request current `dmesg`, available pstore/last-kmsg records, and saved private app logs. Unavailable or denied sources are recorded as collection failures; their absence does not rule out a kernel panic.

The collector reads diagnostics without clearing logs, changing settings, rebooting, or starting DFRoot. It does not upload anything. Review the files for personal information before sharing them, and include the selected SU manager/version, DFRoot settings, and steps that led to the failure.

## Usage

> [!WARNING]
> I am not responsible for any damage to your device.

1. Install [DFRoot](https://github.com/diabl0w/DFRoot/releases/latest)
2. Install a SU Manager from below:
   - Samsung:
     - [diabl0w's KernelSU](https://github.com/diabl0w/KernelSU/releases/latest)
   - Other:
     - [KernelSU](https://github.com/tiann/KernelSU/releases/latest)
     - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest)
     - [KowSU](https://github.com/KOWX712/KernelSU/releases/latest)

> If you have a custom KernelSU fork for a specific manufacturer, please make a pull request to add it here.

## Features

- Optional Start at Boot, disabled by default
- Optional soft reboot when supported by your SU Manager
- RO Partition Protection
- Disable KernelSU modules before daemon launch to help recover from a broken module
- Saved run logs and export through Android's file picker
- No Shizuku or Wi-Fi connection required

## How it works

The Android kernel decrypts AES-CBC ESP packets directly into the page cache of files open for `splice()`. By crafting `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content`, any 16-byte-aligned block in a mapped shared library can be overwritten without write permission and without copy-on-write.

The exploit uses this primitive to patch shellcode into `libc++.so` in the kernel's page cache. The next privileged call to the hooked function runs the shellcode, which loads our custom kernel module via `insmod`.

### Exploit chain

1. **IpSec transform** — App allocates a `UdpEncapsulationSocket` + SPI and builds an AES-CBC/HMAC-SHA256 ESP transform via `IpSecManager`.

2. **splicehelper → crash_dump64** — The splicehelper binary is spliced into `crash_dump64` via the CBC primitive. `crash_dump64` runs in the `crash_dump` SELinux domain (via exec label transition), which can open `vendor_file` labeled files (untrusted_app context cannot read these files so we need this bridge). The splicehelper serves two modes: splice mode (pipe a 16-byte page chunk out to the parent for write) and read mode (`argv[3]="r"`, write 16 bytes of file content to a pipe fd for IV computation).

3. **dfroot.ko → vendor_file** — The kernel module is written via the crash_dump bridge (splicehelper splice mode) into a `vendor_file`-labeled file.

4. **libc++ hook** (fires in init, uid=0, tid=1) — Shellcode is patched into `libc++.so` at `std::ostream::sentry::sentry()` (`_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_`). Triggered by: `createOrphanProcess()` double-forks so the grandchild is adopted by PID 1 (init); when init reaps the orphan its main thread (tid=1) calls through the hooked function. The shellcode:
   - Checks `getuid()==0` and `gettid()==1`; returns immediately otherwise
   - Creates `/dev/df` as a one-shot mutex (O_CREAT|O_EXCL) to prevent re-entry
   - Clones a worker child (parent returns to init immediately)
   - Worker forks a grandchild; grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec` then execs `/vendor/bin/insmod <ko_target>`

5. **dfroot.ko init** (runs as `vendor_modprobe`, uid=0) — The KO is loaded by `insmod` in the `vendor_modprobe` SELinux domain:
   - Writes `false` to `selinux_state` (global permissive)
   - Bypasses DEFEX via kprobes (if applicable)
   - Calls `call_usermodehelper` to run our custom bootstrap code

6. **bootstrap.c** — performs various functions and finally launches the su daemon from your installed SU Manager.

## Building

```sh
make
```

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
