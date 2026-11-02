# Alarm sounds in Direct Boot

Platform build: `atest DeskClockDirectBootTests`.

Run instrumentation on a disposable/unlocked test installation. The persistence
tests create disabled alarms and unscheduled instances, then remove their own rows.
They do not clear the existing alarm database.

The automated tests cover:

- Preserving sound bytes after the original provider disappears and the cache
  object is recreated.
- Keeping system-default URIs dynamic, silence silent, and APK resources direct.
- Distinguishing canonical URIs with identical numeric IDs.
- Atomic refresh failure and empty/oversized sources.
- Discarding incomplete copies.
- Reusing completed copies on alarm state changes.
- Actual provider insertion, update, pruning and existing-database migration.
- Keeping a sound referenced by an instance after its parent selects another sound.
- Storing the copy in persistent device-protected, non-backup storage.

## On-device acceptance checks

1. Select a built-in tone explicitly (e.g. Beep-Beep-Beep) and set an alarm a few
   minutes ahead. Allow its background copy to complete, then reboot without
   entering the device PIN. The selected tone must play. Repeat with a different
   tone and compare a recording if needed: a playing audio stream alone does not
   establish that the correct sound was selected.
2. Repeat after unlocking following reboot. The original URI must play normally.
3. Snooze the BFU alarm and verify the same tone at the next firing. Test two
   distinct alarms with different sounds and a repeated alarm.
4. Set a system-default alarm tone and select **Default** in the alarm. After
   changing the system default and rebooting, the alarm must use the new default.
5. Repeat with a custom audio/document URI, crescendo, vibration-only, zero alarm
   volume, and an active phone call.
6. Upgrade an installation with existing explicit tones, unlock/start Clock, then
   reboot. Existing alarms must work without manually selecting their tones again.
7. Delete/corrupt a copy in a debuggable test installation. An explicit sound with
   no usable copy must use the bundled alarm fallback in BFU. AFU must still use
   the original sound.

## Scope and timing

The provider schedules copying on its own worker after persisted changes and
refreshes existing sounds after unlocked startup/boot/update/restore. File I/O is
not done on the main thread or the alarm state-machine thread. A successful alarm
database write is **not** an acknowledgement that its background audio copy has
finished; an immediate reboot/process kill can still require the bundled fallback.
Unreadable, empty, or larger-than-32-MiB sources also use that fallback in BFU.
The original selection and normal unlocked playback remain available.

Copies are private to the app/user, live in DE no-backup storage, and are pruned
against both template and instance references. Explicit user audio selected as an
alarm is consequently available before first unlock, like system-default tones.
Timers are outside the scope of this patch.
