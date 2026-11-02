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

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
public class AlarmRingtoneCacheTest {
    private static final Uri FIRST = Uri.parse("content://sound/36?title=First&canonical=1");
    private static final Uri SECOND = Uri.parse("content://sound/36?title=Second&canonical=1");
    private static final byte[] SOUND = {1, 2, 3, 4, 5};

    private File mRoot;
    private File mSource;
    private File mCacheDirectory;
    private AlarmRingtoneCache mCache;
    private int mOpens;
    private boolean mLocked;

    @Before
    public void setUp() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        mRoot = Files.createTempDirectory(context.getCacheDir().toPath(), "alarm-sound-test").toFile();
        mSource = new File(mRoot, "source");
        Files.write(mSource.toPath(), SOUND);
        mCacheDirectory = new File(mRoot, "persistent");
        final MockContentResolver resolver = new MockContentResolver(context);
        final ContentProvider provider = new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri uri, String[] projection, String selection,
                    String[] args, String sort) { throw new UnsupportedOperationException(); }
            @Override public String getType(Uri uri) { return "audio/ogg"; }
            @Override public Uri insert(Uri uri, ContentValues values) { return null; }
            @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
            @Override public int update(Uri uri, ContentValues values, String selection,
                    String[] args) { return 0; }
            @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode)
                    throws FileNotFoundException {
                mOpens++;
                if (mLocked) {
                    throw new FileNotFoundException("MediaProvider unavailable before unlock");
                }
                return new AssetFileDescriptor(ParcelFileDescriptor.open(mSource,
                        ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            }
        };
        final ProviderInfo info = new ProviderInfo();
        info.authority = "sound";
        provider.attachInfo(context, info);
        resolver.addProvider("sound", provider);
        mCache = new AlarmRingtoneCache(mCacheDirectory, resolver);
    }

    @After
    public void tearDown() throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(mRoot.toPath())) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void cachedBytesSurviveUnavailableProviderAndProcessRecreation() throws Exception {
        mCache.cache(FIRST, false);
        mLocked = true;
        assertTrue(mSource.delete());
        final AlarmRingtoneCache reopened = new AlarmRingtoneCache(mCacheDirectory,
                new MockContentResolver());
        assertArrayEquals(SOUND, read(reopened.getPlaybackUri(FIRST)));
        assertEquals(1, mOpens);
    }

    @Test
    public void symbolicDefaultSilenceAndResourcesAreNotSnapshotted() {
        final Uri resource = Uri.parse("android.resource://com.android.deskclock/raw/alarm_expire");
        for (Uri uri : Arrays.asList(null, Uri.EMPTY, Settings.System.DEFAULT_ALARM_ALERT_URI,
                Settings.System.DEFAULT_RINGTONE_URI, resource)) {
            mCache.cache(uri, true);
            assertEquals(uri, mCache.getPlaybackUri(uri));
        }
        assertEquals(0, mOpens);
        assertFalse(mCacheDirectory.exists());
    }

    @Test
    public void completeCanonicalUriDistinguishesSoundsWithSameNumericId() throws Exception {
        mCache.cache(FIRST, false);
        final byte[] secondSound = {7, 8, 9};
        Files.write(mSource.toPath(), secondSound);
        mCache.cache(SECOND, false);
        assertNotEquals(mCache.getPlaybackUri(FIRST), mCache.getPlaybackUri(SECOND));
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
        assertArrayEquals(secondSound, read(mCache.getPlaybackUri(SECOND)));
    }

    @Test
    public void failedRefreshPreservesCompletedCopy() throws Exception {
        mCache.cache(FIRST, false);
        Files.write(mSource.toPath(), new byte[0]);
        mCache.cache(FIRST, true);
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
        mLocked = true;
        mCache.cache(FIRST, true);
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
    }

    @Test
    public void oversizedSourceCannotPublishPartialCopy() throws Exception {
        mCache.cache(FIRST, false);
        try (RandomAccessFile file = new RandomAccessFile(mSource, "rw")) {
            file.setLength(AlarmRingtoneCache.MAX_SOUND_BYTES + 1);
        }
        mCache.cache(FIRST, true);
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
        mCache.cache(SECOND, false);
        assertNull(mCache.getPlaybackUri(SECOND));
    }

    @Test
    public void newSelectionDoesNotDiscardSoundStillReferencedByAnInstance() throws Exception {
        mCache.cache(FIRST, false);
        mCache.cache(SECOND, false);
        mCache.retain(new HashSet<>(Arrays.asList(FIRST, SECOND)));
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(SECOND)));
        mCache.retain(Collections.singleton(SECOND));
        assertNull(mCache.getPlaybackUri(FIRST));
        assertNotNull(mCache.getPlaybackUri(SECOND));
        mCache.retain(Collections.emptySet());
        assertEquals(0, mCacheDirectory.list().length);
    }

    @Test
    public void interruptedFirstCopyIsNotPlayable() throws Exception {
        mCache.cache(FIRST, false);
        final File complete = new File(mCache.getPlaybackUri(FIRST).getPath());
        assertTrue(complete.renameTo(new File(complete.getPath() + ".new")));
        assertNull(mCache.getPlaybackUri(FIRST));
        mCache.retain(Collections.singleton(FIRST));
        assertEquals(0, mCacheDirectory.list().length);
    }

    @Test
    public void stateChangesReuseCopyAndExplicitRefreshReplacesIt() throws Exception {
        mCache.cache(FIRST, false);
        Files.write(mSource.toPath(), new byte[] {9, 8, 7});
        mCache.cache(FIRST, false);
        assertEquals(1, mOpens);
        assertArrayEquals(SOUND, read(mCache.getPlaybackUri(FIRST)));
        mCache.cache(FIRST, true);
        assertEquals(2, mOpens);
        assertArrayEquals(new byte[] {9, 8, 7}, read(mCache.getPlaybackUri(FIRST)));
    }

    private static byte[] read(Uri uri) throws Exception {
        assertNotNull(uri);
        return Files.readAllBytes(new File(uri.getPath()).toPath());
    }
}
