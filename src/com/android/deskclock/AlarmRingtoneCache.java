/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deskclock;

import android.content.ContentResolver;
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.UserManager;
import android.util.AtomicFile;

import com.android.deskclock.provider.ClockContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies explicitly selected alarm sounds into device-protected storage. MediaProvider, including
 * its catalog of built-in sounds, is unavailable before first unlock. The original URI remains in
 * the alarm database; only playback while locked uses the copy.
 *
 * Writes and pruning are serialized by ClockProvider on a dedicated background thread. Keep these
 * files out of the evictable cache directory and out of backups containing device-specific URIs.
 */
public final class AlarmRingtoneCache {
    public static final String REFRESH_METHOD = "refresh_alarm_ringtones";

    private static final String DIRECTORY = "alarm_ringtones";
    private static final String SUFFIX = ".audio";
    // Bound disk usage for a single sound, including sources which report no length.
    static final long MAX_SOUND_BYTES = 32L * 1024 * 1024;

    private final File mDirectory;
    private final ContentResolver mResolver;

    public AlarmRingtoneCache(Context context) {
        this(new File(context.createDeviceProtectedStorageContext().getNoBackupFilesDir(), DIRECTORY),
                context.getContentResolver());
    }

    AlarmRingtoneCache(File directory, ContentResolver resolver) {
        mDirectory = directory;
        mResolver = resolver;
    }

    /** Request background migration/refresh after app upgrade, startup, restore or user unlock. */
    public static void refresh(Context context) {
        context.getContentResolver().call(Uri.parse("content://" + ClockContract.AUTHORITY),
                REFRESH_METHOD, null, null);
    }

    public static boolean isUserUnlocked(Context context) {
        return context.getSystemService(UserManager.class).isUserUnlocked();
    }

    /** Defaults already have a system-managed DE cache; silence and APK resources need no copy. */
    private static boolean needsCopy(Uri uri) {
        return uri != null && !Uri.EMPTY.equals(uri) && !RingtoneManager.isDefault(uri)
                && (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || ContentResolver.SCHEME_FILE.equals(uri.getScheme()));
    }

    /** Does not contact the original provider, which may be unavailable in Direct Boot. */
    public Uri getPlaybackUri(Uri original) {
        if (!needsCopy(original)) {
            return original;
        }
        final File file = getFile(original);
        return file.isFile() && file.length() > 0 ? Uri.fromFile(file) : null;
    }

    /**
     * Called off the main thread while unlocked. A failed refresh must leave a previously completed
     * copy intact. File names depend on the full URI, never a transient numeric MediaStore ID alone.
     */
    public void cache(Uri uri, boolean refresh) {
        if (!needsCopy(uri)) {
            return;
        }
        final File file = getFile(uri);
        if (!refresh && file.isFile() && file.length() > 0) {
            return;
        }
        final AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try (InputStream input = mResolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("No stream returned for alarm sound");
            }
            if (!mDirectory.isDirectory() && !mDirectory.mkdirs()) {
                throw new IOException("Cannot create alarm sound directory");
            }
            output = atomicFile.startWrite();
            final byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_SOUND_BYTES) {
                    throw new IOException("Alarm sound exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            if (size == 0) {
                throw new IOException("Empty alarm sound");
            }
            atomicFile.finishWrite(output);
            output = null;
        } catch (IOException | RuntimeException e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            LogUtils.e("Unable to cache alarm sound", e);
        }
    }

    /** The caller must serialize the live-reference query and this method with database writes. */
    public void retain(Set<Uri> referenced) {
        final Set<String> keep = new HashSet<>();
        for (Uri uri : referenced) {
            if (needsCopy(uri)) {
                keep.add(getFile(uri).getName());
            }
        }
        final File[] files = mDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            // Also discard unfinished AtomicFile writes left behind by process death.
            if (!keep.contains(file.getName()) && !file.delete()) {
                LogUtils.w("Unable to remove unused alarm sound");
            }
        }
    }

    private File getFile(Uri uri) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            final char[] hex = "0123456789abcdef".toCharArray();
            final StringBuilder name = new StringBuilder(digest.length * 2 + SUFFIX.length());
            for (byte value : digest) {
                name.append(hex[(value & 0xff) >>> 4]).append(hex[value & 0xf]);
            }
            return new File(mDirectory, name.append(SUFFIX).toString());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
