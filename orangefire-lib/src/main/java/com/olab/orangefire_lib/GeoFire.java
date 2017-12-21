/*
 * Firebase GeoFire Java Library
 *
 * Copyright © 2014 Firebase - All Rights Reserved
 * https://www.firebase.com
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binaryform must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY FIREBASE AS IS AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL FIREBASE BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.olab.orangefire_lib;

import android.util.Log;

import com.olab.orangefire_lib.core.GeoHash;
import com.olab.orangefire_lib.orangefire.Utility;
import com.orange.webcom.sdk.DataSnapshot;
import com.orange.webcom.sdk.OnComplete;
import com.orange.webcom.sdk.Webcom;
import com.orange.webcom.sdk.WebcomError;
import com.orange.webcom.sdk.WebcomException;

import java.lang.Throwable;
import java.util.*;

/**
 * A GeoFire instance is used to store geo location data in Webcom database.
 */

public class GeoFire {

    /**
     * A small wrapper class to forward any events to the LocationEventListener.
     */
    private static class LocationValueEventListener implements Utility.ValueEventListener {

        private final LocationCallback callback;

        LocationValueEventListener(LocationCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onDataChange(DataSnapshot dataSnapshot) {
            try {
                if (dataSnapshot.value() == null) {
                    this.callback.onLocationResult(dataSnapshot.name(), null);
                } else {
                    GeoLocation location = GeoFire.getLocationValue(dataSnapshot);
                    if (location != null) {
                        this.callback.onLocationResult(dataSnapshot.name(), location);
                    } else {
                        String message = "GeoFire data has invalid format: " + String.valueOf(dataSnapshot.value());
                        this.callback.onCancelled( new WebcomError("Invalid format", message));
                    }
                }
            } catch (WebcomException e) {
                this.callback.onCancelled(e.getError());
            }
        }

        @Override
        public void onCancelled(WebcomError webcomError) {
            this.callback.onCancelled(webcomError);
        }
    }

    static GeoLocation getLocationValue(DataSnapshot dataSnapshot) {
        try {
            Map<String, Object> data = null;
            try {
                data = dataSnapshot.valueMap(Object.class);
                Number latitudeObj = (Number) data.get("0");
                Number longitudeObj = (Number) data.get("1");
                double latitude = latitudeObj.doubleValue();
                double longitude = longitudeObj.doubleValue();
                if (data.size() == 2 && GeoLocation.coordinatesValid(latitude, longitude)) {
                    return new GeoLocation(latitude, longitude);
                } else {
                    return null;
                }
            } catch (WebcomException e) {
                e.printStackTrace();
            }

        } catch (NullPointerException | ClassCastException e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    private final Webcom databaseReference;
    private final EventRaiser eventRaiser;

    /**
     * Creates a new GeoFire instance at the given Webcom database reference.
     *
     * @param databaseReference The Webcom database reference this GeoFire instance uses
     */
    public GeoFire(Webcom databaseReference) {
        this.databaseReference = databaseReference;
        EventRaiser eventRaiser;
        try {
            eventRaiser = new AndroidEventRaiser();
        } catch (Throwable e) {
            // We're not on Android, use the ThreadEventRaiser
            eventRaiser = new ThreadEventRaiser();
        }
        this.eventRaiser = eventRaiser;
    }

    /**
     * @return The Webcom reference this GeoFire instance uses.
     */
    public Webcom getDatabaseReference() {
        return this.databaseReference;
    }

    Webcom getDatabaseRefForGeoHash(String geohash ) {
        try {
            return this.databaseReference.child(geohash);
        } catch (WebcomException e) {
            e.printStackTrace();
            Log.e("OrangeFire","OrangeFire:getDatabaseRefForGeoHash  Error when getting reference for key.");
            return null;
        }
    }

    /**
     * Adds new location with a given key.
     *
     * @param key      The key to save the location for
     * @param location The location of this key
     */
    public void addNewLocation(String key, GeoLocation location) {
        this.addNewLocation(key, location, null);
    }

    /**
     * Adds new location with a given key.
     *
     * @param key               The key to save the location for
     * @param location          The location of this key
     * @param onComplete        A listener that is called once the location was successfully saved on the server or an
     *                          error occurred
     */
    public void addNewLocation(final String key, final GeoLocation location, final OnComplete onComplete) {
        if (key == null) {
            throw new NullPointerException();
        }
        GeoHash geoHash = new GeoHash(location);
        Webcom hashRef = this.getDatabaseRefForGeoHash( geoHash.getGeoHashString());
        Map<String, Object> updates = new HashMap<String, Object>();
        updates.put(key, Arrays.asList(location.latitude, location.longitude));
        try {
            if (onComplete != null) {
                hashRef.update(updates, onComplete);
            } else {
                hashRef.update(updates );
            }
        } catch (WebcomException e) {
            e.printStackTrace();
            Log.e("OrangeFire","OrangeFire:addNewLocation Failed to push values to database.");
        }
    }


    /** Removes selected key from database in certain GeoHash area
     *
     * @param key key The key to remove from this GeoFire
     * @param GeohashString hash of position for this key
     */
    public void removeLocation(String key, String GeohashString) {
        this.removeLocation(key, null);
    }

    /** Removes selected key from database in certain GeoHash area
     *
     * @param key key The key to remove from this GeoFire
     * @param GeohashString hash of position for this key
     * @param completionListener A completion listener that is called once the location is successfully removed
     *                           from the server or an error occurred
     */
    public void removeLocation(final String key, String GeohashString, final OnComplete completionListener) {
        if (key == null || GeohashString == null) {
            throw new NullPointerException();
        }
        Webcom keyRef = this.getDatabaseRefForGeoHash(GeohashString);
        try {
            if (completionListener != null) {
                keyRef.child(key).remove( completionListener);
            } else {
                keyRef.child(key).remove( );
            }
        } catch (WebcomException e) {
            e.printStackTrace();
            Log.e("OrangeFire","OrangeFire:removeLocation Failed to remove value from database.");
        }
    }

    /**
     * Returns a new Query object centered at the given location and with the given radius.
     *
     * @param center The center of the query
     * @param radius The radius of the query, in kilometers
     * @return The new GeoQuery object
     */
    public GeoQuery queryAtLocation(GeoLocation center, double radius) {
        return new GeoQuery(this, center, radius);
    }

    void raiseEvent(Runnable r) {
        this.eventRaiser.raiseEvent(r);
    }
}
