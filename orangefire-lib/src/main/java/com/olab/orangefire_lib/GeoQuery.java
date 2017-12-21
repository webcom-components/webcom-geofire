package com.olab.orangefire_lib;

import android.support.annotation.Nullable;

import com.olab.orangefire_lib.core.GeoHash;
import com.olab.orangefire_lib.core.GeoHashQuery;
import com.olab.orangefire_lib.orangefire.Utility;
import com.olab.orangefire_lib.util.GeoUtils;
import com.olab.orangefire_lib.orangefire.ChildEventListener;

import com.orange.webcom.sdk.DataSnapshot;
import com.orange.webcom.sdk.OnComplete;
import com.orange.webcom.sdk.OnQuery;
import com.orange.webcom.sdk.Query;
import com.orange.webcom.sdk.WebcomError;
import com.orange.webcom.sdk.WebcomException;

import java.util.*;

/**
 * A GeoQuery object can be used for geo queries in a given circle. The GeoQuery class is thread safe.
 */
public class GeoQuery {

    private static class LocationInfo {
        final GeoLocation location;
        final boolean inGeoQuery;
        final GeoHash geoHash;

        public LocationInfo(GeoLocation location, boolean inGeoQuery) {
            this.location = location;
            this.inGeoQuery = inGeoQuery;
            this.geoHash = new GeoHash(location);
        }
    }

    private final ChildEventListener childEventLister = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childAdded(dataSnapshot);
            }
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childChanged(dataSnapshot);
            }
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childRemoved(dataSnapshot);
            }
        }

    };

    private final GeoFire geoFire;
    private final Set<GeoQueryEventListener> eventListeners = new HashSet<GeoQueryEventListener>();
    private final Map<GeoHashQuery, Query> webcomQueries = new HashMap<GeoHashQuery, Query>();
    private final Set<GeoHashQuery> outstandingQueries = new HashSet<GeoHashQuery>();
    private final Map<String, LocationInfo> locationInfos = new HashMap<String, LocationInfo>();
    private GeoLocation center;
    private double radius;
    private Set<GeoHashQuery> queries;

    /**
     * Creates a new GeoQuery object centered at the given location and with the given radius.
     *
     * @param geoFire The GeoFire object this GeoQuery uses
     * @param center  The center of this query
     * @param radius  The radius of this query, in kilometers
     */
    GeoQuery(GeoFire geoFire, GeoLocation center, double radius) {
        this.geoFire = geoFire;
        this.center = center;
        // convert from kilometers to meters
        this.radius = radius * 1000;
    }

    private boolean locationIsInQuery(GeoLocation location) {
        return GeoUtils.distance(location, center) <= this.radius;
    }

    private void updateLocationInfo(final String key, final GeoLocation location) {
        LocationInfo oldInfo = this.locationInfos.get(key);
        boolean isNew = (oldInfo == null);
        boolean changedLocation = (oldInfo != null && !oldInfo.location.equals(location));
        boolean wasInQuery = (oldInfo != null && oldInfo.inGeoQuery);

        boolean isInQuery = this.locationIsInQuery(location);
        if ((isNew || !wasInQuery) && isInQuery) {
            for (final GeoQueryEventListener listener : this.eventListeners) {
                this.geoFire.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onKeyEntered(key, location);
                    }
                });
            }
        } else if (!isNew && changedLocation && isInQuery) {
            for (final GeoQueryEventListener listener : this.eventListeners) {
                this.geoFire.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onKeyMoved(key, location);
                    }
                });
            }
        } else if (wasInQuery && !isInQuery) {
            for (final GeoQueryEventListener listener : this.eventListeners) {
                this.geoFire.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onKeyExited(key);
                    }
                });
            }
        }
        LocationInfo newInfo = new LocationInfo(location, this.locationIsInQuery(location));
        this.locationInfos.put(key, newInfo);
    }

    private boolean geoHashQueriesContainGeoHash(GeoHash geoHash) {
        if (this.queries == null) {
            return false;
        }
        for (GeoHashQuery query : this.queries) {
            if (query.containsGeoHash(geoHash)) {
                return true;
            }
        }
        return false;
    }

    private void reset() {
        for (Map.Entry<GeoHashQuery, Query> entry : this.webcomQueries.entrySet()) {
            removeChildEventListener(entry.getValue());
        }
        this.outstandingQueries.clear();
        this.webcomQueries.clear();
        this.queries = null;
        this.locationInfos.clear();
    }

    private boolean hasListeners() {
        return !this.eventListeners.isEmpty();
    }

    private boolean canFireReady() {
        return this.outstandingQueries.isEmpty();
    }

    private void checkAndFireReady() {
        if (canFireReady()) {
            for (final GeoQueryEventListener listener : this.eventListeners) {
                this.geoFire.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onGeoQueryReady();
                    }
                });
            }
        }
    }

    private void addValueToReadyListener(final Query webcom, final GeoHashQuery query) {
        Utility.AddListenerForSingleValueEvent(webcom, new Utility.ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                synchronized (GeoQuery.this) {
                    GeoQuery.this.outstandingQueries.remove(query);
                    GeoQuery.this.checkAndFireReady();
                }
            }

            @Override
            public void onCancelled(final WebcomError webcomError) {
                synchronized (GeoQuery.this) {
                    for (final GeoQueryEventListener listener : GeoQuery.this.eventListeners) {
                        GeoQuery.this.geoFire.raiseEvent(new Runnable() {
                            @Override
                            public void run() {
                                listener.onGeoQueryError(webcomError);
                            }
                        });
                    }
                }
            }
        });
    }

    private void setupQueries() {
        Set<GeoHashQuery> oldQueries = (this.queries == null) ? new HashSet<GeoHashQuery>() : this.queries;
        Set<GeoHashQuery> newQueries = GeoHashQuery.queriesAtLocation(center, radius);
        this.queries = newQueries;

        for (GeoHashQuery query : oldQueries) {
            if (!newQueries.contains(query)) {

                Set<String> hashSet;
                try {
                    hashSet = query.GetGeohashSet();
                    for ( String hashElement: hashSet ) {
                        Query webcomQuery = geoFire.getDatabaseRefForGeoHash(hashElement);
                        removeChildEventListener(webcomQuery);

                        webcomQueries.remove(query);
                        outstandingQueries.remove(query);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }

        for (final GeoHashQuery query : newQueries) {
            if (!oldQueries.contains(query)) {
                outstandingQueries.add(query);
                Set<String> hashSet;
                try {
                    hashSet = query.GetGeohashSet();
                    for ( String hashElement: hashSet ) {

                        Query webcomQuery = geoFire.getDatabaseRefForGeoHash(hashElement);
                        addChildEventListener(webcomQuery);
                        addValueToReadyListener(webcomQuery, query);
                        webcomQueries.put(query, webcomQuery);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }
        for (Map.Entry<String, LocationInfo> info : this.locationInfos.entrySet()) {
            LocationInfo oldLocationInfo = info.getValue();
            this.updateLocationInfo(info.getKey(), oldLocationInfo.location);
        }
        // remove locations that are not part of the geo query anymore
        Iterator<Map.Entry<String, LocationInfo>> it = this.locationInfos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LocationInfo> entry = it.next();
            if (!this.geoHashQueriesContainGeoHash(entry.getValue().geoHash)) {
                it.remove();
            }
        }
        checkAndFireReady();
    }

    private void childAdded(DataSnapshot dataSnapshot) {
        //  add new listner for this key in geohash
        GeoLocation location = GeoFire.getLocationValue(dataSnapshot);
        if (location != null) {
            try {
                this.updateLocationInfo(dataSnapshot.name(), location);
            } catch (WebcomException e) {
                e.printStackTrace();
            }
        } else {
            // throw an error in future?
        }
    }

    private void childChanged(DataSnapshot dataSnapshot) {
        GeoLocation location = GeoFire.getLocationValue(dataSnapshot);
        if (location != null) {
            try {
                this.updateLocationInfo(dataSnapshot.name(), location);
            } catch (WebcomException e) {
                e.printStackTrace();
            }
        } else {
            // throw an error in future?
        }
    }

    private void childRemoved(DataSnapshot dataSnapshot) {
        final String key;
        try {
            key = dataSnapshot.name();
            final LocationInfo info = this.locationInfos.get(key);
            if (info != null && info.inGeoQuery) {
                for (final GeoQueryEventListener listener : GeoQuery.this.eventListeners) {
                    GeoQuery.this.geoFire.raiseEvent(new Runnable() {
                        @Override
                        public void run() {
                            listener.onKeyExited(key);
                        }
                    });
                }
            }
        } catch (WebcomException e) {
            e.printStackTrace();
        }
    }

    /**
     * Adds a new GeoQueryEventListener to this GeoQuery.
     *
     * @param listener The listener to add
     * @throws java.lang.IllegalArgumentException If this listener was already added
     */
    public synchronized void addGeoQueryEventListener(final GeoQueryEventListener listener) {
        if (eventListeners.contains(listener)) {
            throw new IllegalArgumentException("Added the same listener twice to a GeoQuery!");
        }
        eventListeners.add(listener);
        if (this.queries == null) {
            this.setupQueries();
        } else {
            for (final Map.Entry<String, LocationInfo> entry : this.locationInfos.entrySet()) {
                final String key = entry.getKey();
                final LocationInfo info = entry.getValue();
                if (info.inGeoQuery) {
                    this.geoFire.raiseEvent(new Runnable() {
                        @Override
                        public void run() {
                            listener.onKeyEntered(key, info.location);
                        }
                    });
                }
            }
            if (this.canFireReady()) {
                this.geoFire.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onGeoQueryReady();
                    }
                });
            }
        }
    }

    /**
     * Removes an event listener.
     *
     * @param listener The listener to remove
     * @throws java.lang.IllegalArgumentException If the listener was removed already or never added
     */
    public synchronized void removeGeoQueryEventListener(GeoQueryEventListener listener) {
        if (!eventListeners.contains(listener)) {
            throw new IllegalArgumentException("Trying to remove listener that was removed or not added!");
        }
        eventListeners.remove(listener);
        if (!this.hasListeners()) {
            reset();
        }
    }

    /**
     * Removes all event listeners from this GeoQuery.
     */
    public synchronized void removeAllListeners() {
        eventListeners.clear();
        reset();
    }

    /**
     * Returns the current center of this query.
     *
     * @return The current center
     */
    public synchronized GeoLocation getCenter() {
        return center;
    }

    /**
     * Sets the new center of this query and triggers new events if necessary.
     *
     * @param center The new center
     */
    public synchronized void setCenter(GeoLocation center) {
        this.center = center;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }

    /**
     * Returns the radius of the query, in kilometers.
     *
     * @return The radius of this query, in kilometers
     */
    public synchronized double getRadius() {
        // convert from meters
        return radius / 1000;
    }

    /**
     * Sets the radius of this query, in kilometers, and triggers new events if necessary.
     *
     * @param radius The new radius value of this query in kilometers
     */
    public synchronized void setRadius(double radius) {
        // convert to meters
        this.radius = radius * 1000;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }

    /**
     * Sets the center and radius (in kilometers) of this query, and triggers new events if necessary.
     *
     * @param center The new center
     * @param radius The new radius value of this query in kilometers
     */
    public synchronized void setLocation(GeoLocation center, double radius) {
        this.center = center;
        // convert radius to meters
        this.radius = radius * 1000;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }

    /**
     * Registers ChildEventListener for selected query.
     *
     * @param query Query instance to which we attach ChildEventListener.
     */
    void addChildEventListener(Query query) {
        // register to all events to query -> this.childEventLister();
        try {
            query.on(Query.Event.CHILD_ADDED, new OnQuery() {
                @Override
                public void onComplete(DataSnapshot dataSnapshot, @Nullable String s) {
                    childEventLister.onChildAdded(dataSnapshot, s);
                }

                @Override
                public void onCancel(WebcomError webcomError) {
                }

                @Override
                public void onError(WebcomError webcomError) {
                }
            });
            query.on(Query.Event.CHILD_CHANGED, new OnQuery() {
                @Override
                public void onComplete(DataSnapshot dataSnapshot, @Nullable String s) {
                    childEventLister.onChildChanged(dataSnapshot, s);
                }

                @Override
                public void onCancel(WebcomError webcomError) {

                }

                @Override
                public void onError(WebcomError webcomError) {

                }
            });
            query.on(Query.Event.CHILD_REMOVED, new OnQuery() {
                @Override
                public void onComplete(DataSnapshot dataSnapshot, @Nullable String s) {
                    childEventLister.onChildRemoved(dataSnapshot);
                }

                @Override
                public void onCancel(WebcomError webcomError) {

                }

                @Override
                public void onError(WebcomError webcomError) {

                }
            });
        } catch (WebcomException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unregisters ChildEventListener from selected query.
     *
     * @param query Query to unregister.
     */
    void removeChildEventListener(Query query) {
        // unregister event listener
        try {
            query.off();
        } catch (WebcomException e) {
            e.printStackTrace();
        }
    }

    /**
     *  Return location info for selected key, if it's location is present within current query area.
     * @param key Searhced key value.
     * @return LocationInfo for selected key if present, otherwise null.
     */
    public LocationInfo getLocationOfKey( String key) {
        if( locationInfos.containsKey(key)) {
            return locationInfos.get(key);
        }
        return null;
    }

    /** Removes location for selected key, if if it's location is present within current query area.
     *
     * @param key Value of key which is to be removed.
     * @param completionListener Callback listener.
     */
    public void RemoveLocationForKey ( String key, final OnComplete completionListener){
        if( locationInfos.containsKey(key)) {
            geoFire.removeLocation( key, locationInfos.get(key).geoHash.getGeoHashString(), completionListener);
            return;
        }
        if( completionListener != null){
            completionListener.onError( new WebcomError("RemoveError", "Key " + key + " not present in current query"));
        }
    }

    /** Update location for selected key, if if it's location is present within current query area.
     *
     * @param key Value of key which is to be removed.
     * @param newLocation Updated location for key.
     * @param completionListener Callback listener.
     */
    public void UpdatetLocationForKey(final String key, final GeoLocation newLocation, final OnComplete completionListener){
        if( locationInfos.containsKey(key)) {
            geoFire.removeLocation(key, locationInfos.get(key).geoHash.getGeoHashString(), new OnComplete() {
                @Override
                public void onComplete() {
                    geoFire.addNewLocation( key, newLocation, completionListener);
                }

                @Override
                public void onError(WebcomError webcomError) {
                    completionListener.onError( new WebcomError("UpdateError", "Failed to update key " + key));
                }
            });
            return;
        }
        if( completionListener != null){
            completionListener.onError( new WebcomError("UpdateError", "Key " + key + " not present in current query"));
        }

    }

}
