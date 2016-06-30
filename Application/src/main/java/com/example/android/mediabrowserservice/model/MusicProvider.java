/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.mediabrowserservice.model;

import android.media.MediaMetadata;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.example.android.mediabrowserservice.utils.LogHelper;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Utility class to get a list of MusicTrack's based on a server-side JSON
 * configuration.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

//    private static final String CATALOG_URL =
//        "http://storage.googleapis.com/automotive-media/music.json";
    private static final String CATALOG_URL = "https://firebasestorage.googleapis.com/v0/b/project-8042746893150109988.appspot.com/o/music.json?alt=media&token=260f77b8-da44-4df1-ab15-8aed30cc8cff";

    public static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";

    private static final String JSON_MUSIC = "music";
    private static final String JSON_TITLE = "title";
    private static final String JSON_ALBUM = "album";
    private static final String JSON_ARTIST = "artist";
    private static final String JSON_GENRE = "genre";
    private static final String JSON_SOURCE = "source";
    private static final String JSON_IMAGE = "image";
    private static final String JSON_TRACK_NUMBER = "trackNumber";
    private static final String JSON_TOTAL_TRACK_COUNT = "totalTrackCount";
    private static final String JSON_DURATION = "duration";

    // Categorized caches for music track data:
    private ConcurrentMap<String, List<MediaMetadata>> mMusicListByGenre;
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;

    private final Set<String> mFavoriteTracks;

    private FirebaseStorage firebaseStorage;
    private FirebaseAuth firebaseAuth;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider() {
        mMusicListByGenre = new ConcurrentHashMap<>();
        mMusicListById = new ConcurrentHashMap<>();
        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        // Firebase init
//        firebaseStorage = FirebaseStorage.getInstance();
//        firebaseAuth = FirebaseAuth.getInstance();
//        // authenticate
//        firebaseAuth.signInAnonymously()
//                .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
//                    @Override
//                    public void onSuccess(AuthResult authResult) {
//                        Log.d(TAG, "signInAnonymously:onComplete:" + authResult.getUser().getUid());
//                    }
//                })
//                .addOnFailureListener(new OnFailureListener() {
//                    @Override
//                    public void onFailure(@NonNull Exception e) {
//                        Log.w(TAG, "signInAnonymously:onFailure:", e);
//                    }
//                });
    }

    /**
     * Get an iterator over the list of genres
     *
     * @return genres
     */
    public Iterable<String> getGenres() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mMusicListByGenre.keySet();
    }

    /**
     * Get music tracks of the given genre
     *
     */
    public Iterable<MediaMetadata> getMusicsByGenre(String genre) {
        if (mCurrentState != State.INITIALIZED || !mMusicListByGenre.containsKey(genre)) {
            return Collections.emptyList();
        }
        return mMusicListByGenre.get(genre);
    }

    /**
     * Very basic implementation of a search that filter music tracks which title containing
     * the given query.
     *
     */
    public Iterable<MediaMetadata> searchMusic(String titleQuery) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadata> result = new ArrayList<>();
        titleQuery = titleQuery.toLowerCase();
        for (MutableMediaMetadata track : mMusicListById.values()) {
            if (track.metadata.getString(MediaMetadata.METADATA_KEY_TITLE).toLowerCase()
                    .contains(titleQuery)) {
                result.add(track.metadata);
            }
        }
        return result;
    }

    /**
     * Return the MediaMetadata for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public MediaMetadata getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId).metadata : null;
    }

    public synchronized void updateMusic(String musicId, MediaMetadata metadata) {
        MutableMediaMetadata track = mMusicListById.get(musicId);
        if (track == null) {
            return;
        }

        String oldGenre = track.metadata.getString(MediaMetadata.METADATA_KEY_GENRE);
        String newGenre = metadata.getString(MediaMetadata.METADATA_KEY_GENRE);

        track.metadata = metadata;

        // if genre has changed, we need to rebuild the list by genre
        if (!oldGenre.equals(newGenre)) {
            buildListsByGenre();
        }
    }

    public void setFavorite(String musicId, boolean favorite) {
        if (favorite) {
            mFavoriteTracks.add(musicId);
        } else {
            mFavoriteTracks.remove(musicId);
        }
    }

    public boolean isFavorite(String musicId) {
        return mFavoriteTracks.contains(musicId);
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        LogHelper.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            // Nothing to do, execute callback immediately
            callback.onMusicCatalogReady(true);
            return;
        }

        // get file Url
//        final StorageReference storageRef = firebaseStorage.getReferenceFromUrl("gs://project-8042746893150109988.appspot.com/Tracks/Thea & Miguel - Yellow (Coldplay Cover).mp3");
//        storageRef.getDownloadUrl()
//                .addOnSuccessListener(new OnSuccessListener<Uri>() {
//                    @Override
//                    public void onSuccess(Uri uri) {
//                        Log.d(TAG, "getDownloadUrl:onSuccess:");
////                        pathToSong = uri;
//                        if (mCurrentState == State.NON_INITIALIZED) {
//                            mCurrentState = State.INITIALIZING;
//                            MediaMetadata item = new MediaMetadata.Builder()
//                                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, String.valueOf(storageRef.getName().hashCode()))
//                                    .putString(CUSTOM_METADATA_TRACK_SOURCE, uri.toString())
//                                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Vino Jams Album")
//                                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Vino Jams")
//                                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 221000)
//                                    .putString(MediaMetadata.METADATA_KEY_GENRE, "Rock")
////                                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, iconUrl)
//                                    .putString(MediaMetadata.METADATA_KEY_TITLE, storageRef.getName())
//                                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 1)
//                                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 1)
//                                    .build();
//                            String musicId = item.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
//                            mMusicListById.put(musicId, new MutableMediaMetadata(musicId, item));
//
//                            buildListsByGenre();
//
//                            mCurrentState = State.INITIALIZED;
//                        }
//                        if (callback != null) {
//                            callback.onMusicCatalogReady(true);
//                        }
//                    }
//                })
//                .addOnFailureListener(new OnFailureListener() {
//                    @Override
//                    public void onFailure(@NonNull Exception e) {
//                        Log.w(TAG, "getDownloadUrl:onFailure:", e);
//                    }
//                });

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveMedia();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    private synchronized void buildListsByGenre() {
        ConcurrentMap<String, List<MediaMetadata>> newMusicListByGenre = new ConcurrentHashMap<>();

        for (MutableMediaMetadata m : mMusicListById.values()) {
            String genre = m.metadata.getString(MediaMetadata.METADATA_KEY_GENRE);
            List<MediaMetadata> list = newMusicListByGenre.get(genre);
            if (list == null) {
                list = new ArrayList<>();
                newMusicListByGenre.put(genre, list);
            }
            list.add(m.metadata);
        }
        mMusicListByGenre = newMusicListByGenre;
    }

    private synchronized void retrieveMedia() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                JSONObject jsonObj = fetchJSONFromUrl(CATALOG_URL);
                if (jsonObj == null) {
                    return;
                }
                JSONArray tracks = jsonObj.getJSONArray(JSON_MUSIC);
                if (tracks != null) {
                    for (int j = 0; j < tracks.length(); j++) {
                        MediaMetadata item = buildFromJSON(tracks.getJSONObject(j));
                        String musicId = item.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                        mMusicListById.put(musicId, new MutableMediaMetadata(musicId, item));
                    }
                    buildListsByGenre();
                }
                mCurrentState = State.INITIALIZED;
            }
        } catch (JSONException e) {
            LogHelper.e(TAG, e, "Could not retrieve music list");
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }

    private MediaMetadata buildFromJSON(JSONObject json) throws JSONException {
        String title = json.getString(JSON_TITLE);
        String album = json.getString(JSON_ALBUM);
        String artist = json.getString(JSON_ARTIST);
        String genre = json.getString(JSON_GENRE);
        String source = json.getString(JSON_SOURCE);
        String image = json.getString(JSON_IMAGE);
        int trackNumber = json.getInt(JSON_TRACK_NUMBER);
        int totalTrackCount = json.getInt(JSON_TOTAL_TRACK_COUNT);
        int duration = json.getInt(JSON_DURATION) * 1000; // ms

        LogHelper.d(TAG, "Found music track: ", json);

        // Since we don't have a unique ID in the server, we fake one using the hashcode of
        // the music source. In a real world app, this could come from the server.
        String id = String.valueOf(source.hashCode());

        // Adding the music source to the MediaMetadata (and consequently using it in the
        // mediaSession.setMetadata) is not a good idea for a real world music app, because
        // the session metadata can be accessed by notification listeners. This is done in this
        // sample for convenience only.
        return new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putString(CUSTOM_METADATA_TRACK_SOURCE, source)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, image)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .putString(MediaMetadata.METADATA_KEY_GENRE, genre)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, trackNumber)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, totalTrackCount)
                .build();
    }

    /**
     * Download a JSON file from a server, parse the content and return the JSON
     * object.
     *
     * @return result JSONObject containing the parsed representation.
     */
    private JSONObject fetchJSONFromUrl(String urlString) {
        InputStream is = null;
        try {
            URL url = new URL(urlString);
            URLConnection urlConnection = url.openConnection();
            is = new BufferedInputStream(urlConnection.getInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    urlConnection.getInputStream(), "iso-8859-1"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            LogHelper.e(TAG, "Failed to parse the json for media list", e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
