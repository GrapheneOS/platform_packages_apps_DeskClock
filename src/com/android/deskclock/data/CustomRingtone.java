/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.deskclock.data;

import android.net.Uri;
import androidx.annotation.NonNull;

/**
 * A read-only domain object representing a custom ringtone chosen from the file system.
 */
public final class CustomRingtone implements Comparable<CustomRingtone> {

    /** The unique identifier of the custom ringtone. */
    private final long mId;

    /** The uri that can be used to play the ringtone. */
    private final Uri mUri;

    /** The uri that was originally selected (copied) for the ringtone. */
    private final Uri mOriginalUri;

    /** The title describing the file at the given uri; typically the file name. */
    private final String mTitle;

    /** {@code true} iff the application has permission to read the content of {@code mUri uri}. */
    private final boolean mHasPermissions;

    CustomRingtone(long id, Uri uri, Uri originalUri, String title, boolean hasPermissions) {
        mId = id;
        mUri = uri;
        mOriginalUri = originalUri;
        mTitle = title;
        mHasPermissions = hasPermissions;
    }

    public long getId() { return mId; }
    public Uri getUri() { return mUri; }
    public Uri getOriginalUri() { return mOriginalUri; }
    public String getTitle() { return mTitle; }
    public boolean hasPermissions() { return mHasPermissions; }

    CustomRingtone setHasPermissions(boolean hasPermissions) {
        if (mHasPermissions == hasPermissions) {
            return this;
        }

        return new CustomRingtone(mId, mUri, mOriginalUri, mTitle, hasPermissions);
    }

    @Override
    public int compareTo(@NonNull CustomRingtone other) {
        return String.CASE_INSENSITIVE_ORDER.compare(getTitle(), other.getTitle());
    }
}