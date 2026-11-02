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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.deskclock.provider.Alarm;
import com.android.deskclock.provider.AlarmInstance;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Exercises actual provider persistence, rather than only the cache's file operations. */
@RunWith(AndroidJUnit4.class)
public class AlarmRingtonePersistenceTest {
    private Context mContext;
    private AlarmRingtoneCache mCache;
    private final List<Long> mAlarmIds = new ArrayList<>();
    private final List<Long> mInstanceIds = new ArrayList<>();
    private final List<File> mSources = new ArrayList<>();

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mCache = new AlarmRingtoneCache(mContext);
        assertTrue(AlarmRingtoneCache.isUserUnlocked(mContext));
    }

    @After
    public void tearDown() {
        for (long id : mInstanceIds) {
            AlarmInstance.deleteInstance(mContext.getContentResolver(), id);
        }
        for (long id : mAlarmIds) {
            Alarm.deleteAlarm(mContext.getContentResolver(), id);
        }
        for (File file : mSources) {
            file.delete();
        }
    }

    @Test
    public void providerWritesPrepareCopyAndKeepOriginalUri() throws Exception {
        final Uri uri = sound(new byte[] {1, 2, 3});
        final Alarm alarm = insert(uri);
        awaitCopy(uri, true);
        assertEquals(uri, Alarm.getAlarm(mContext.getContentResolver(), alarm.id).alert);
        assertArrayEquals(new byte[] {1, 2, 3}, readCopy(uri));
        assertTrue(new File(mCache.getPlaybackUri(uri).getPath()).getCanonicalPath().startsWith(
                mContext.createDeviceProtectedStorageContext().getNoBackupFilesDir()
                        .getCanonicalPath() + File.separator));
    }

    @Test
    public void changingParentRetainsOldInstanceSoundUntilInstanceDeleted() throws Exception {
        final Uri first = sound(new byte[] {1, 2, 3});
        final Uri second = sound(new byte[] {4, 5, 6});
        final Alarm alarm = insert(first);
        final AlarmInstance instance = alarm.createInstanceAfter(Calendar.getInstance());
        AlarmInstance.addInstance(mContext.getContentResolver(), instance);
        mInstanceIds.add(instance.mId);
        awaitCopy(first, true);

        alarm.alert = second;
        Alarm.updateAlarm(mContext.getContentResolver(), alarm);
        awaitCopy(second, true);
        assertArrayEquals(new byte[] {1, 2, 3}, readCopy(first));
        assertArrayEquals(new byte[] {4, 5, 6}, readCopy(second));
        assertEquals(first, AlarmInstance.getInstance(mContext.getContentResolver(), instance.mId)
                .mRingtone);

        AlarmInstance.deleteInstance(mContext.getContentResolver(), instance.mId);
        awaitCopy(first, false);
        assertArrayEquals(new byte[] {4, 5, 6}, readCopy(second));
    }

    @Test
    public void refreshMigratesAlarmThatPredatesCacheSupport() throws Exception {
        final Uri uri = sound(new byte[] {7, 8, 9});
        final Alarm oldAlarm = newAlarm();
        oldAlarm.alert = uri;
        // Simulate an existing database from a version without sound caching: bypass provider
        // mutation hooks. No scheduling is involved and only this test's row is removed later.
        final File database = mContext.createDeviceProtectedStorageContext()
                .getDatabasePath("alarms.db");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            mAlarmIds.add(db.insertOrThrow("alarm_templates", null,
                    Alarm.createContentValues(oldAlarm)));
        }
        AlarmRingtoneCache.refresh(mContext);
        awaitCopy(uri, true);
        assertArrayEquals(new byte[] {7, 8, 9}, readCopy(uri));
    }

    private Alarm insert(Uri uri) {
        final Alarm alarm = newAlarm();
        alarm.alert = uri;
        alarm.enabled = false;
        Alarm.addAlarm(mContext.getContentResolver(), alarm);
        mAlarmIds.add(alarm.id);
        return alarm;
    }

    private Uri sound(byte[] bytes) throws Exception {
        final File file = File.createTempFile("alarm-test-source", ".ogg", mContext.getCacheDir());
        Files.write(file.toPath(), bytes);
        mSources.add(file);
        return Uri.fromFile(file);
    }

    private Alarm newAlarm() {
        final Alarm[] result = new Alarm[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result[0] = new Alarm());
        return result[0];
    }

    private byte[] readCopy(Uri original) throws Exception {
        final Uri local = mCache.getPlaybackUri(original);
        assertNotNull(local);
        return Files.readAllBytes(new File(local.getPath()).toPath());
    }

    private void awaitCopy(Uri uri, boolean present) {
        final long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ((mCache.getPlaybackUri(uri) != null) == present) {
                return;
            }
            SystemClock.sleep(20);
        }
        fail("Timed out waiting for alarm sound copy, expected present=" + present);
    }
}
